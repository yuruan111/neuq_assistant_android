package com.yuruan.neuqassist.data

import android.content.Context
import com.yuruan.neuqassist.net.HttpService
import com.yuruan.neuqassist.net.WebvpnCrypto
import kotlinx.coroutines.CompletableDeferred
import java.util.Calendar

/**
 * 云端数据服务 —— 移植自 HarmonyOS 端 service/CloudService.ets
 *
 * 读取原项目公开发布的空教室页面
 *
 * 数据源：https://neuq.tsiao.io/（TsiaohanWang/neuq-classroom-query 每小时
 * 由 CI 抓取教务系统并生成的静态页面，公开可访问，无需登录/WebVPN）。
 *
 * 页面结构（对应 Rust generator + template.tera.html，输出经 minify）：
 * - 单页内嵌 7 天数据，day-N 为生成日起第 N 天
 * - 工学馆单元格 id：day-{N}-GXG{楼层}F{时段}（时段如 1-2）
 * - 其他教学楼单元格 id：day-{N}-{代码}{时段}
 *   代码：JCL基础楼 / ZHSYL综合实验楼 / DZL地质楼 / GLL管理楼 / KJL科技楼 / RWL人文楼
 * - 单元格内容：教室名以空格或 <br> 分隔，可含 <u>/<del>/<strong> 样式标记，
 *   空时为 "无"；更新时间位于 id="update-time-placeholder" 的 span 中
 */
class CloudService(private val http: HttpService = HttpService()) {

    /** 镜像列表（依次尝试）：tsiao.io 自定义域 / Cloudflare Pages 默认域 */
    companion object {
        const val CLOUD_URL = "https://neuq.tsiao.io/"
        const val CLOUD_URL_BACKUP = "https://neuq-classroom-query-2kb.pages.dev/"
        const val WEBVPN_BASE = "https://vpn.neuq.edu.cn"

        val MIRRORS = listOf(CLOUD_URL, CLOUD_URL_BACKUP)

        /** 结果缓存：key 含日期/偏移/时段/来源/新鲜度/更新时间，避免重复分组/排序与跨态串用 */
        private val resultCache = HashMap<String, QueryResult>()
        /** 各缓存键的写入时间（每键独立 30 秒 TTL，避免互相"续命"） */
        private val resultCacheAt = HashMap<String, Long>()
        private val resultCacheLock = Any()
        private const val RESULT_CACHE_TTL_MS = 30000L // 30 秒 TTL

        /** 并发云端刷新去重池：避免同一通道并发请求重复拉取并竞态写盘 */
        private val inFlightFetches = HashMap<String, CompletableDeferred<CloudFetchResult>>()
        private val inFlightLock = Any()

        /** 云端抓取并落盘的内部结果（含来源标识供结果构建与并发合并复用） */
        private class CloudFetchResult(
            val data: CloudCacheData,
            val sourceTag: String
        )

        /** 数据单元格命中记录（内部解析用） */
        private class CloudCellHit(
            val code: String,
            val slot: String,
            var start: Int,
            /** day-N- 标记在原文中的起始位置（切片时作为上一格的终点） */
            var markerIdx: Int
        )

        /** 计算缓存相对今天的基准日偏移（跨天后取数下标 = dayOffset + shift） */
        fun cacheDayShift(cached: CloudCacheData): Int {
            if (cached.fetchedAt <= 0) return 0
            val base0 = Calendar.getInstance().apply {
                timeInMillis = cached.fetchedAt
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val today0 = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val shift = Math.round((today0 - base0) / 86400000.0).toInt()
            return if (shift < 0) 0 else shift
        }

        /**
         * 获取指定日期的云端教室数据（缓存优先）
         *
         * ① 本地缓存 1 小时内 → 直接使用（零网络请求）
         * ② 镜像直连（tsiao.io → pages.dev）→ 解析全部 7 天 → 落盘缓存
         * ③ 直连均失败且有 WebVPN 会话 → 经 vpn.neuq.edu.cn 网关代理获取
         *    （校园网侧访问 Cloudflare 通常可达）
         *
         * @param dayOffset 日期偏移（0=今天，最大 6）
         * @param slotKey 时段（AppConfig.TIME_SLOTS 的 fileSuffix，或 'allday' 表示全天空闲）
         * @param serverUrl 自建服务器接口地址（可选，如 http://ip:8734/api/data/token，
         *   返回与本地缓存同构的行文本；配置后优先使用）
         * @param webvpnCookies 有效的 WebVPN 会话 Cookie（可选，供代理兜底）
         * @param webFetcher 经隐形 WebView 拉取页面的兜底函数（可选，原生栈被
         *   运营商重置时由系统浏览器内核完成请求）
         */
        suspend fun fetchDay(
            context: Context,
            dayOffset: Int,
            slotKey: String,
            serverUrl: String = "",
            webvpnCookies: String = "",
            webFetcher: (suspend (url: String) -> String)? = null,
            onProgress: ((String) -> Unit)? = null
        ): QueryResult {
            if (dayOffset < 0 || dayOffset > 6) {
                throw AppError.config("云端数据仅覆盖今天起 7 天（day 0-6）")
            }

            // ① 缓存优先（1 小时内新鲜直接复用，且跨天自动换算 shift）
            val cached = CloudCache.load(context)
            if (cached != null && cached.rooms.isNotEmpty() && CloudCache.isFresh(cached)) {
                val shift = cacheDayShift(cached)
                val cacheResult = resultFromCache(cached, dayOffset, slotKey, shift, false)
                if (cacheResult != null) {
                    onProgress?.invoke("使用本地缓存（每小时自动过期）...")
                    return cacheResult
                }
                // 缓存数据异常（无法构建结果）时落到网络路径
            }

            // ②③ 并发请求合并：相同通道参数正在刷新时复用同一 Deferred，防止竞态覆盖缓存
            val fetchKey =
                "$serverUrl|${if (webvpnCookies.isNotEmpty()) "vpn" else "direct"}|" +
                    if (webFetcher != null) "web" else "http"
            var isOwner = false
            val deferred = synchronized(inFlightLock) {
                inFlightFetches[fetchKey] ?: CompletableDeferred<CloudFetchResult>().also {
                    inFlightFetches[fetchKey] = it
                    isOwner = true
                }
            }

            val fetched = if (isOwner) {
                try {
                    val res = CloudService().fetchAndSave(
                        context, serverUrl, webvpnCookies, webFetcher, onProgress
                    )
                    deferred.complete(res)
                    res
                } catch (t: Throwable) {
                    deferred.completeExceptionally(t)
                    throw t
                } finally {
                    synchronized(inFlightLock) {
                        if (inFlightFetches[fetchKey] === deferred) {
                            inFlightFetches.remove(fetchKey)
                        }
                    }
                }
            } else {
                deferred.await()
            }

            val dayRooms = filterByDayIndexed(fetched.data, dayOffset)
            return buildForDay(
                dayRooms, fetched.data.updateTime, dayOffset, slotKey,
                fetched.sourceTag, false, "day=$dayOffset"
            )
        }

        /**
         * 从本地缓存构建展示结果（App 启动时秒显上次数据，不联网）。
         *
         * @param dayOffset 展示日偏移（0=今天，同时用于结果里的日期标签）
         * @param dayShift  缓存基准日偏移：缓存生成日早于今天时为正，
         *                  实际取数下标 = dayOffset + dayShift
         * @param stale     是否展示过期提示（首屏未检查新鲜度时传 true，1 小时内新鲜缓存传 false）
         * @returns null 表示缓存未覆盖该日（空桶/越界），调用方应视为未命中
         *          落到联网路径；注意不能用 totalRooms 判空——"该时段确实无空闲
         *          教室"的正常空结果 dayRooms 非空，不会被误判成 miss
         */
        fun resultFromCache(
            data: CloudCacheData,
            dayOffset: Int,
            slotKey: String,
            dayShift: Int = 0,
            stale: Boolean = false
        ): QueryResult? {
            if (data.rooms.isEmpty()) return null
            val targetDay = dayOffset + dayShift
            // 使用预分组索引，避免遍历全量数据
            val dayRooms = filterByDayIndexed(data, targetDay)
            if (dayRooms.isEmpty()) return null
            return buildForDay(
                dayRooms, data.updateTime, dayOffset, slotKey, "本地缓存",
                stale, "day=$targetDay|at=${data.fetchedAt}"
            )
        }

        /** 按天过滤（优先预分组索引 O(1)；索引未构建时回退平行数组遍历） */
        private fun filterByDayIndexed(
            cached: CloudCacheData,
            dayOffset: Int
        ): List<ProcessedClassroomData> {
            if (cached.dayIndex.isEmpty()) {
                // 手工构造的缓存数据可能未建索引：回退遍历，避免恒返回空结果
                return filterByDay(cached.rooms, cached.roomDays, dayOffset)
            }
            if (dayOffset < 0 || dayOffset >= cached.dayIndex.size) return emptyList()
            return cached.dayIndex[dayOffset].map { cached.rooms[it] }
        }

        /** 按天过滤（rooms 与 days 平行，备用） */
        private fun filterByDay(
            rooms: List<ProcessedClassroomData>,
            days: List<Int>,
            dayOffset: Int
        ): List<ProcessedClassroomData> {
            val out = mutableListOf<ProcessedClassroomData>()
            for (i in rooms.indices) {
                if (days[i] == dayOffset) out.add(rooms[i])
            }
            return out
        }

        /** 从单日教室数据构建展示结果（带结果缓存，避免重复分组/排序） */
        private fun buildForDay(
            dayRooms: List<ProcessedClassroomData>,
            updateTime: String,
            dayOffset: Int,
            slotKey: String,
            sourceTag: String,
            stale: Boolean,
            cacheVariant: String = ""
        ): QueryResult {
            val dateStr = DateUtil.beijingDate(dayOffset)
            val cacheKey =
                "$dateStr|$dayOffset|$slotKey|$sourceTag|${if (stale) "1" else "0"}|$updateTime|$cacheVariant"
            // 30 秒内命中缓存直接返回（分组/排序只执行一次，按键独立计时）
            synchronized(resultCacheLock) {
                val cachedAt = resultCacheAt[cacheKey]
                if (resultCache.containsKey(cacheKey) && cachedAt != null &&
                    (System.currentTimeMillis() - cachedAt) < RESULT_CACHE_TTL_MS
                ) {
                    return resultCache[cacheKey]!!
                }
            }

            var selected: List<ProcessedClassroomData>
            var label: String
            when (slotKey) {
                "allday" -> {
                    selected = intersectAllDay(dayRooms)
                    label = "全天空闲"
                }
                "am" -> {
                    // 上午空闲：1-2 与 3-4 的交集
                    selected = intersectSlots(dayRooms, listOf("1-2", "3-4"))
                    label = "上午空闲"
                }
                "pm" -> {
                    // 下午空闲：5-6 与 7-8 的交集
                    selected = intersectSlots(dayRooms, listOf("5-6", "7-8"))
                    label = "下午空闲"
                }
                "night" -> {
                    // 夜间空闲：9-10 与 11-12 的交集
                    selected = intersectSlots(dayRooms, listOf("9-10", "11-12"))
                    label = "夜间空闲"
                }
                "1-8" -> {
                    // 昼间第1-8节：云端数据只有 1-2/3-4/5-6/7-8 四个独立时段，
                    // 需取这四个时段的交集（与全天空闲逻辑一致，只是范围缩小到前4个时段）
                    selected = intersectSlots(dayRooms, listOf("1-2", "3-4", "5-6", "7-8"))
                    label = "昼间第1-8节"
                }
                else -> {
                    selected = dayRooms.filter { it.timeSlot == slotKey }
                    val slot = AppConfig.TIME_SLOTS.firstOrNull { it.fileSuffix == slotKey }
                    label = slot?.label ?: slotKey
                }
            }

            val staleTag = if (stale) "（数据可能已过期，点查询刷新）" else ""
            val result = QueryResult(
                dateStr = dateStr,
                slotLabel = "$label · $sourceTag" +
                    "${if (updateTime.isEmpty()) "" else "（更新于 $updateTime）"}$staleTag",
                totalRooms = selected.size,
                groups = ClassroomService.groupByBuilding(selected)
            )

            synchronized(resultCacheLock) {
                resultCache[cacheKey] = result
                resultCacheAt[cacheKey] = System.currentTimeMillis()
            }
            return result
        }

        /** 清空结果缓存（本地缓存数据刷新后调用，防止 30 秒内旧结果遮蔽新数据） */
        fun clearResultCache() {
            synchronized(resultCacheLock) {
                resultCache.clear()
                resultCacheAt.clear()
            }
        }

        /** 提取页面"本表更新于"时间 */
        fun extractUpdateTime(html: String): String {
            val m = Regex("本表更新于\\s*<span[^>]*>\\s*([\\d/: ]+?)\\s*<").find(html)
            return m?.groupValues?.get(1) ?: ""
        }

        /**
         * 解析指定天全部时段的教室数据。
         * 采用 indexOf 逐标记扫描 + 位置切片，完全不依赖正则，
         * 对 minify 原始页与 WebView 序列化页均有效。
         */
        fun parseDayHtml(html: String, dayOffset: Int): List<ProcessedClassroomData> {
            val out = mutableListOf<ProcessedClassroomData>()
            val prefix = "day-$dayOffset-"
            val hits = mutableListOf<CloudCellHit>()

            // 扫描所有 day-N- 开头标记，解析出数据单元格
            var searchFrom = 0
            while (true) {
                val idx = html.indexOf(prefix, searchFrom)
                if (idx < 0) break
                searchFrom = idx + prefix.length
                // 提取标记 token（到引号/空白/尖括号为止）
                var j = idx + prefix.length
                val token = StringBuilder()
                while (j < html.length) {
                    val ch = html[j]
                    if (ch == '"' || ch == '\'' || ch == ' ' || ch == '\t' ||
                        ch == '\r' || ch == '\n' || ch == '>'
                    ) break
                    token.append(ch)
                    j++
                }
                val hit = parseCellToken(token.toString())
                if (hit != null) {
                    hit.markerIdx = idx
                    // 内容起点跳到本标记所在标签的闭合 '>' 之后：标记可能位于 id/onclick 等
                    // 属性值里，从引号后直接切片会把 `">` 这类属性收尾混进格文本。
                    var k = j
                    while (k < html.length && html[k] != '>') k++
                    hit.start = if (k < html.length) k + 1 else j
                    hits.add(hit)
                }
            }

            // 单元格内容 = 本格标记结束 → 下一格标记开始（不越过 </table> 边界）
            for (i in hits.indices) {
                var end = html.length
                if (i + 1 < hits.size) {
                    // 终点取「下一格标记的起始处」而非其属性引号之后：否则下一格的
                    // `<td id="day-N-…` 开标签残段会整段混入本格文本，被当成教室名。
                    end = hits[i + 1].markerIdx
                }
                val tIdx = html.indexOf("</table>", hits[i].start)
                if (tIdx >= 0 && tIdx < end) end = tIdx
                if (end < hits[i].start) end = hits[i].start
                val cellHtml = html.substring(hits[i].start, end)
                val names = extractRoomNames(cellHtml)
                val building = if (hits[i].code == "GXG") "工学馆" else buildingName(hits[i].code)
                for (name in names) {
                    out.add(
                        ProcessedClassroomData(
                            building = building,
                            name = name,
                            capacity = "",
                            equipmentConfig = "",
                            timeSlot = hits[i].slot
                        )
                    )
                }
            }
            return out
        }

        /** 解析 id 标记 token（如 GXG1F1-2 / JCL3-4）为楼栋代码与时段，非数据格返回 null */
        private fun parseCellToken(token: String): CloudCellHit? {
            if (token.startsWith("GXG")) {
                if (token.length > 5 && token[4] == 'F' &&
                    token[3] in '0'..'9'
                ) {
                    val slot = token.substring(5)
                    if (Regex("^\\d{1,2}-\\d{1,2}$").matches(slot)) {
                        return CloudCellHit("GXG", slot, 0, 0)
                    }
                }
                return null
            }
            val codes = listOf("JCL", "ZHSYL", "DZL", "GLL", "KJL", "RWL")
            for (c in codes) {
                if (token.startsWith(c)) {
                    val slot = token.substring(c.length)
                    if (Regex("^\\d{1,2}-\\d{1,2}$").matches(slot)) {
                        return CloudCellHit(c, slot, 0, 0)
                    }
                    return null
                }
            }
            return null
        }

        private fun buildingName(code: String): String = when (code) {
            "JCL" -> "基础楼"
            "ZHSYL" -> "综合实验楼"
            "DZL" -> "地质楼"
            "GLL" -> "管理楼"
            "KJL" -> "科技楼"
            "RWL" -> "人文楼"
            else -> code
        }

        fun extractRoomNames(cellHtmlIn: String): List<String> {
            var cellHtml = cellHtmlIn
            // 切片可能停在标签中途：先剪掉尾部未闭合的 `<…` 残段
            val cut = cellHtml.lastIndexOf('<')
            if (cut >= 0 && cellHtml.indexOf('>', cut) < 0) {
                cellHtml = cellHtml.substring(0, cut)
            }
            val text = cellHtml
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<[^>]*>"), " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
            // 二次防御：教室名不会含 HTML 语法字符，属性残段（onclick=、id=" 等）整体拦截
            return text.split(Regex("\\s+")).filter {
                it.isNotEmpty() && it != "无" && !Regex("^\\d{1,2}F$").matches(it) &&
                    !it.contains("节") && !Regex("[<>\"'=&]").containsMatchIn(it) &&
                    !it.contains("day-")
            }
        }

        /** 全天空闲：六个顺序时段取交集（复用 Rust 的 TimeSlotBuildingData 语义） */
        private fun intersectAllDay(all: List<ProcessedClassroomData>) =
            intersectSlots(all, AppConfig.SEQUENTIAL_SLOTS)

        /** 指定时段取交集（复用 TimeSlotBuildingData 语义） */
        private fun intersectSlots(
            all: List<ProcessedClassroomData>,
            slots: List<String>
        ): List<ProcessedClassroomData> {
            val agg = TimeSlotBuildingData()
            val info = HashMap<String, ProcessedClassroomData>()
            val buildings = LinkedHashSet<String>()
            for (p in all) {
                agg.add(p.timeSlot, p.building, p.name)
                info["${p.building}|${p.name}"] = p
                buildings.add(p.building)
            }
            val result = mutableListOf<ProcessedClassroomData>()
            buildings.forEach { building ->
                intersectByBuilding(agg, building, slots).forEach { name ->
                    val src = info["$building|$name"]
                    result.add(
                        ProcessedClassroomData(
                            building = building,
                            name = name,
                            capacity = src?.capacity ?: "",
                            equipmentConfig = "",
                            timeSlot = if (slots.size == 6) "全天空闲" else slots.joinToString("-")
                        )
                    )
                }
            }
            return result
        }

        /** 指定教学楼在指定时段列表中的交集 */
        private fun intersectByBuilding(
            agg: TimeSlotBuildingData,
            building: String,
            slots: List<String>
        ): Set<String> {
            var commonRooms: Set<String>? = null
            for (slot in slots) {
                val current = agg.getRooms(slot, building)
                if (commonRooms == null) {
                    commonRooms = current
                } else {
                    val intersection = commonRooms.intersect(current)
                    if (intersection.isEmpty()) return emptySet()
                    commonRooms = intersection
                }
            }
            return commonRooms ?: emptySet()
        }
    }

    /** 执行自建服务器 / 公共镜像 / WebVPN 代理抓取并原子落盘 */
    private suspend fun fetchAndSave(
        context: Context,
        serverUrl: String,
        webvpnCookies: String,
        webFetcher: (suspend (url: String) -> String)?,
        onProgress: ((String) -> Unit)?
    ): CloudFetchResult {
        // ② 自建服务器（境内可达性最好；文本格式与本地缓存一致，零解析成本）。
        //    该网络环境下原生 http 可能被整体重置，因此原生与 WebView 双通道都尝试。
        var serverErr = "未配置"
        if (serverUrl.isNotEmpty()) {
            var body = ""
            var via = ""
            if (webFetcher != null) {
                try {
                    body = webFetcher(serverUrl)
                    via = "WebView"
                } catch (_: Exception) {
                    body = ""
                }
            }
            if (!body.startsWith("NEUQ_CACHE_V1")) {
                // WebView 渠道未取得有效文本时，再用原生 http 尝试
                try {
                    val resp = http.get(serverUrl)
                    if (resp.status < 400) {
                        body = resp.body
                        via = "原生"
                    }
                } catch (_: Exception) {
                    // 忽略，走最终诊断
                }
            }
            // WebView 以 text/plain 渲染时会把文本包进 <pre>，此处还原纯文本
            val sIdx = body.indexOf("NEUQ_CACHE_V1")
            if (sIdx > 0) {
                val eIdx = body.indexOf("</pre>", sIdx)
                body = if (eIdx >= 0) body.substring(sIdx, eIdx) else body.substring(sIdx)
            }
            if (body.startsWith("NEUQ_CACHE_V1")) {
                val data = CloudCache.fromText(body)
                if (data != null && data.rooms.isNotEmpty()) {
                    // 同步刷新本地缓存文件
                    CloudCache.save(context, data.updateTime, data.rooms, data.roomDays)
                    clearResultCache() // 新数据落盘：丢弃 30 秒内的旧结果缓存
                    return CloudFetchResult(data, "自建服务器（$via）")
                }
                serverErr = "${via}通道响应 ${body.length} 字节但无有效数据"
            } else {
                serverErr = "${via}通道无有效内容（响应 ${body.length} 字节）"
            }
            Logger.warn("自建服务器获取失败: $serverErr，回退公共镜像")
        }

        // ③ 拉取整页并解析全部 7 天（多镜像依次尝试）
        onProgress?.invoke("正在获取云端数据（每小时更新）...")
        var html = ""
        var lastErr = ""
        for (mirror in MIRRORS) {
            try {
                var body = ""
                if (webFetcher != null) {
                    body = webFetcher(mirror)
                } else {
                    val resp = http.get(mirror)
                    body = if (resp.status < 400) resp.body else ""
                }
                if (body.isNotEmpty() && body.contains("day-0")) {
                    html = body
                    break
                }
                lastErr = "$mirror（获取 ${body.length} 字节，" +
                    "含day-0:${body.contains("day-0")}）"
            } catch (e: Exception) {
                lastErr = "$mirror: ${e.message}"
                Logger.warn("云端镜像不可达 $lastErr，尝试下一个")
            }
        }
        // WebView 优先失败后，再用原生 http 对两个镜像兜底一次（不同网络栈表现可能不同）
        if (html.isEmpty() && webFetcher != null) {
            for (mirror in MIRRORS) {
                try {
                    val resp = http.get(mirror)
                    if (resp.status < 400 && resp.body.contains("day-0")) {
                        html = resp.body
                        break
                    }
                    lastErr = "$mirror（原生，状态码 ${resp.status}）"
                } catch (e: Exception) {
                    lastErr = "$mirror（原生）: ${e.message}"
                }
            }
        }
        // ③ 兜底：直连镜像均被重置且有 WebVPN 会话时，经网关代理获取
        //    （校园网侧访问 Cloudflare 通常可达；需要有效的 WebVPN 会话 Cookie）
        if (html.isEmpty() && webvpnCookies.isNotEmpty()) {
            try {
                onProgress?.invoke("直连镜像不可达，尝试经 WebVPN 代理获取...")
                val proxied = WebvpnCrypto.buildResourceUrl(WEBVPN_BASE, CLOUD_URL)
                val vpnHttp = HttpService()
                vpnHttp.cookieStore.importFromHeader(webvpnCookies)
                val vpnResp = vpnHttp.get(proxied)
                if (vpnResp.status < 400 && vpnResp.body.contains("day-0")) {
                    html = vpnResp.body
                }
            } catch (e: Exception) {
                Logger.warn("WebVPN 代理获取云端失败: ${e.message}")
            }
        }
        if (html.isEmpty()) {
            val viaVpn = if (webvpnCookies.isNotEmpty()) "（含 WebVPN 代理路径）" else ""
            throw AppError.fetch(
                "云端数据获取失败$viaVpn。服务器:$serverErr；" +
                    "镜像:$lastErr。" +
                    "建议：核对服务器接口地址与TOKEN；更换网络重试；或切实时查询来源。"
            )
        }

        val updateTime = extractUpdateTime(html)
        val rooms = mutableListOf<ProcessedClassroomData>()
        val roomDays = mutableListOf<Int>()
        var totalCells = 0
        for (d in 0..6) {
            val dayRooms = parseDayHtml(html, d)
            Logger.info("[cloud] day-$d 解析 ${dayRooms.size} 格")
            totalCells += dayRooms.size
            for (r in dayRooms) {
                rooms.add(r)
                roomDays.add(d)
            }
        }
        Logger.info(
            "[cloud] 解析完成: $totalCells 格 / ${rooms.size} 间, " +
                "页面 ${html.length} 字节, 更新时间 $updateTime"
        )
        if (totalCells == 0) {
            val gIdx = html.indexOf("GXG1F1-2")
            val snippet = if (gIdx >= 0) {
                html.substring(maxOf(0, gIdx - 60), minOf(html.length, gIdx + 40))
                    .replace(Regex("\\s+"), " ")
            } else "页面中未找到 GXG1F1-2"
            throw AppError.fetch(
                "云端页面解析到 0 个数据格（页面 ${html.length} 字节）。" +
                    "服务器分支:$serverErr。" +
                    "GXG1F1-2 附近内容: $snippet。请完整截图反馈"
            )
        }
        CloudCache.save(context, updateTime, rooms, roomDays)
        clearResultCache() // 新数据落盘：丢弃 30 秒内的旧结果缓存

        val data = CloudCacheData().apply {
            this.updateTime = updateTime
            this.fetchedAt = System.currentTimeMillis()
            this.rooms = rooms
            this.roomDays = roomDays
            buildIndex()
        }
        return CloudFetchResult(data, "云端")
    }
}
