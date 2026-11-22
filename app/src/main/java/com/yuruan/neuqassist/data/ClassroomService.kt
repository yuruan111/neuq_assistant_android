package com.yuruan.neuqassist.data

import com.yuruan.neuqassist.net.ClassroomClient
import com.yuruan.neuqassist.net.ClassroomQueryConfig
import com.yuruan.neuqassist.net.parseClassroomHtml

/**
 * 查询服务编排层 —— 移植自 HarmonyOS 端 service/ClassroomService.ets
 * （对应 Rust src/fetcher.rs + processor.rs 的组合语义）
 *
 * Rust 端为 CI 批处理流水线（抓取全部天/时段 → 落盘 → 过滤 → 生成 HTML），
 * App 端改为交互式按需查询：用户选择日期与时段后即时抓取、过滤、分组展示。
 * 保留原语义：登录一次全程串行、时段级重试、严格区分"空结果"与"异常响应"。
 */

/** 按教学楼分组的展示数据 */
class BuildingGroup(
    val building: String = "",
    val rooms: List<ProcessedClassroomData> = emptyList(),
    /** 校区标签（'本部' / '南校区' / 空串=未知），由 groupByBuilding 一并填充 */
    val campus: String = "",
    /** 楼层子分组（仅 FLOOR_GROUPED_BUILDINGS 内的楼填充；仅用于展示细分） */
    val floors: List<FloorGroup> = emptyList()
)

/** 楼内楼层子分组：floor 为楼层标签（如 "8层"，无法判定归 "其他"） */
class FloorGroup(
    val floor: String = "",
    val rooms: List<ProcessedClassroomData> = emptyList()
)

/** 单次查询结果（供 UI 展示） */
class QueryResult(
    val dateStr: String = "",
    val slotLabel: String = "",
    val totalRooms: Int = 0,
    val groups: List<BuildingGroup> = emptyList()
)

/** 从房间号提取楼层：纯数字且 ≥3 位时取首位；否则返回空串 */
private fun floorOfRoom(name: String): String {
    if (name.length >= 3 && name.all { it.isDigit() }) return name[0].toString()
    return ""
}

/** 楼层标签排序值：数字层按数值升序，「其他」垫底 */
private fun floorSortValue(label: String): Int {
    if (label == "其他") return 999
    val n = label.toIntOrNull() ?: return 999
    return n
}

class ClassroomService(private val config: ClassroomQueryConfig) {

    private val client: ClassroomClient = ClassroomClient(config)
    private val processor: Processor = Processor
    private var loggedIn = false
    private var webQueryImpl: (suspend (date: String, timeBegin: Int, timeEnd: Int) -> String)? = null

    /**
     * 注入 WebView 同源查询实现（非 null 时实时查询走隐形 WebView，
     * 并跳过原生脚本登录；传 null 恢复原生路径）
     */
    fun setWebQuery(fn: (suspend (date: String, timeBegin: Int, timeEnd: Int) -> String)?) {
        webQueryImpl = fn
        loggedIn = fn != null
    }

    /** 单时段原始数据获取：WebView 同源 XHR 优先，原生客户端兜底 */
    private suspend fun runSlotQuery(
        dateStr: String,
        begin: Int,
        end: Int
    ): List<RawClassroomData> {
        val fn = webQueryImpl
        if (fn != null) return parseClassroomHtml(fn(dateStr, begin, end))
        return client.searchFreeClassrooms(dateStr, begin, end)
    }

    /** 注入网页登录会话：直接使用 WebView 取回的 Cookie，跳过脚本登录 */
    fun applyWebSession(cookieHeader: String) {
        client.applyWebSession(cookieHeader)
        loggedIn = true
    }

    /** 确保已登录（幂等）；会话失效的自动重登由 client 内部处理 */
    private suspend fun ensureLogin() {
        if (loggedIn) return
        client.login(config.username, config.password)
        loggedIn = true
    }

    /**
     * 查询单个时段：抓取 → 过滤 → 按教学楼分组
     *
     * webQuery 提供时走 WebView 同源查询（原生 http 被网络环境重置时的方案），
     * 否则走原生 http + 脚本登录。
     */
    suspend fun querySlot(
        dayOffset: Int,
        slot: AppConfig.TimeSlot,
        onProgress: ((String) -> Unit)? = null
    ): QueryResult {
        if (webQueryImpl == null) ensureLogin()

        val dateStr = DateUtil.beijingDate(dayOffset)
        onProgress?.invoke("正在查询 $dateStr ${slot.label}...")

        // 时段级重试（指数退避），与 Rust with_retry 语义一致
        var raws: List<RawClassroomData> = emptyList()
        for (attempt in 0..2) {
            try {
                raws = runSlotQuery(dateStr, slot.begin, slot.end)
                break
            } catch (e: Throwable) {
                if (attempt == 2) throw e
                Logger.warn("查询 ${slot.fileSuffix} 失败，重试: ${e.message}")
                kotlinx.coroutines.delay(1000L * (attempt + 1))
            }
        }

        // 为每条数据标注时段
        val withSlot = raws.map { it.copyWithSlot(slot.fileSuffix) }
        val processed = processor.applyRules(withSlot)

        return QueryResult(
            dateStr = dateStr,
            slotLabel = slot.label,
            totalRooms = processed.size,
            groups = groupByBuilding(processed)
        )
    }

    /**
     * 查询全天空闲：逐个顺序时段（1-2/3-4/5-6/7-8/9-10/11-12）抓取，
     * 取所有时段空闲教室的交集 —— 对应 Rust get_all_day_free_rooms。
     */
    suspend fun queryAllDay(
        dayOffset: Int,
        onProgress: ((String) -> Unit)? = null
    ): QueryResult {
        if (webQueryImpl == null) ensureLogin()

        val dateStr = DateUtil.beijingDate(dayOffset)

        // WebVPN/WebView 模式：单次请求 1-12 节整段（1 次请求替代 6 次——
        // 教务接口语义即"该区间全程空闲"，与逐时段取交集等价）
        if (webQueryImpl != null) {
            onProgress?.invoke("全天空闲：单次查询 1-12 节（减少 WebVPN 请求次数）...")
            var raws: List<RawClassroomData> = emptyList()
            for (attempt in 0..2) {
                try {
                    raws = runSlotQuery(dateStr, 1, 12)
                    break
                } catch (e: Throwable) {
                    if (attempt == 2) throw e
                    kotlinx.coroutines.delay(1000L * (attempt + 1))
                }
            }
            val withSlot = raws.map { it.copyWithSlot("1-12") }
            val processed = processor.applyRules(withSlot)
            return QueryResult(
                dateStr = dateStr,
                slotLabel = "全天空闲（1-12 节单次查询）",
                totalRooms = processed.size,
                groups = groupByBuilding(processed)
            )
        }

        // 原生路径：保持 Rust 的 6 时段逐段交集语义
        val agg = TimeSlotBuildingData()
        // 记录教室属性（容量/设备配置），展示时使用
        val roomInfo = HashMap<String, ProcessedClassroomData>()

        for (i in AppConfig.SEQUENTIAL_SLOTS.indices) {
            val slotKey = AppConfig.SEQUENTIAL_SLOTS[i]
            val slot = AppConfig.TIME_SLOTS.firstOrNull { it.fileSuffix == slotKey } ?: continue
            onProgress?.invoke(
                "全天空闲：正在查询 ${slot.label}" +
                    "（${i + 1}/${AppConfig.SEQUENTIAL_SLOTS.size}）..."
            )

            var raws: List<RawClassroomData> = emptyList()
            for (attempt in 0..2) {
                try {
                    raws = runSlotQuery(dateStr, slot.begin, slot.end)
                    break
                } catch (e: Throwable) {
                    if (attempt == 2) throw e
                    kotlinx.coroutines.delay(1000L * (attempt + 1))
                }
            }

            val withSlot = raws.map { it.copyWithSlot(slot.fileSuffix) }
            val processed = processor.applyRules(withSlot)

            for (p in processed) {
                agg.add(p.timeSlot, p.building, p.name)
                roomInfo["${p.building}|${p.name}"] = p
            }
            // 与 Rust 端一致：请求间隔带随机抖动，降低对教务系统的瞬时压力
            kotlinx.coroutines.delay((1500 + Math.random() * 800).toLong())
        }

        // 汇总交集结果
        val buildings = LinkedHashSet<String>()
        roomInfo.values.forEach { buildings.add(it.building) }

        val allDay = mutableListOf<ProcessedClassroomData>()
        buildings.forEach { building ->
            agg.getAllDayFreeRooms(building).forEach { roomName ->
                roomInfo["$building|$roomName"]?.let { info ->
                    allDay.add(
                        ProcessedClassroomData(
                            building = building,
                            name = roomName,
                            capacity = info.capacity,
                            equipmentConfig = info.equipmentConfig,
                            timeSlot = "全天空闲"
                        )
                    )
                }
            }
        }

        allDay.sortWith(compareBy(ZH_COLLATOR) { it.building })

        return QueryResult(
            dateStr = dateStr,
            slotLabel = "全天空闲（所有时段均空闲）",
            totalRooms = allDay.size,
            groups = groupByBuilding(allDay)
        )
    }

    companion object {
        /** 按教学楼分组（按中文排序），供 UI 展示 */
        fun groupByBuilding(rooms: List<ProcessedClassroomData>): List<BuildingGroup> {
            val map = LinkedHashMap<String, MutableList<ProcessedClassroomData>>()
            for (r in rooms) map.getOrPut(r.building) { mutableListOf() }.add(r)

            val names = map.keys.sortedWith(ZH_COLLATOR)
            val groups = mutableListOf<BuildingGroup>()
            for (name in names) {
                val list = map[name]!!.sortedWith(compareBy(::compareZhNumeric) { it.name })
                groups.add(
                    BuildingGroup(
                        building = name,
                        rooms = list,
                        campus = AppConfig.campusOfBuilding(name),
                        // 仅空教室多的楼做楼层细分（其余楼房间少，平铺）；list 已排序，子分组内顺序自然保持
                        floors = if (name in AppConfig.FLOOR_GROUPED_BUILDINGS) {
                            groupByFloor(list)
                        } else emptyList()
                    )
                )
            }
            return groups
        }

        /** 楼内按楼层子分组：数字房号取首位为楼层，非数字归「其他」；楼层升序、其他垫底 */
        private fun groupByFloor(rooms: List<ProcessedClassroomData>): List<FloorGroup> {
            val map = LinkedHashMap<String, MutableList<ProcessedClassroomData>>()
            for (r in rooms) {
                val f = floorOfRoom(r.name)
                val key = if (f.isEmpty()) "其他" else "${f}层"
                map.getOrPut(key) { mutableListOf() }.add(r)
            }
            return map.keys.sortedBy { floorSortValue(it) }
                .map { FloorGroup(floor = it, rooms = map[it]!!) }
        }
    }
}
