package com.yuruan.neuqassist.data

import android.content.Context
import java.io.File

/**
 * 云端数据本地缓存 —— 移植自 HarmonyOS 端 service/CloudCache.ets
 * （方案 ③④：整页解析结果落盘，切日期/时段零请求）
 *
 * 存储格式（自定义行文本，规避 JSON 类型限制）：
 *   NEUQ_CACHE_V1
 *   updateTime=2026/09/11 19:00
 *   fetchedAt=1780000000000
 *   day|slot|building|name   （重复行，day 为相对生成日的索引 0-6；
 *                             实际可用窗口由调用方决定：
 *                             云端通道 7 天、网页通道一键更新 5 天）
 *
 * 文件位置：{filesDir}/cloud_cache.txt
 * 新鲜度：fetchedAt 距今 1 小时内视为新鲜（与上游 CI 更新周期一致）
 */
class CloudCacheData {
    var updateTime: String = ""
    var fetchedAt: Long = 0
    var rooms: MutableList<ProcessedClassroomData> = mutableListOf()
    var roomDays: MutableList<Int> = mutableListOf()
    /** 按天预分组索引（dayIndex[dayOffset] = 该天所有 rooms 下标），避免每次查询遍历全量 */
    var dayIndex: MutableList<MutableList<Int>> = MutableList(7) { mutableListOf() }

    /**
     * 重建按天预分组索引。
     * 手工构造 rooms/roomDays（不经 save/fromText）后必须调用，
     * 否则 dayIndex 为空，按天查询会恒返回空结果。
     */
    fun buildIndex() {
        dayIndex = MutableList(7) { mutableListOf() }
        for (i in rooms.indices) {
            val d = roomDays[i]
            if (d in 0 until dayIndex.size) dayIndex[d].add(i)
        }
    }
}

object CloudCache {
    private const val HEADER = "NEUQ_CACHE_V1"
    private const val MAX_AGE_MS = 3600000L

    /** 内存缓存：避免每次查询都重新读盘+解析（数千行数据） */
    @Volatile
    private var memoryCache: CloudCacheData? = null

    private fun cachePath(context: Context) = File(context.filesDir, "cloud_cache.txt")

    fun isFresh(data: CloudCacheData): Boolean =
        data.fetchedAt > 0 && (System.currentTimeMillis() - data.fetchedAt) < MAX_AGE_MS

    // 说明：不设 invalidate() 之类的手动失效接口 —— 云端缓存与会话无关，
    // 内存缓存由 save() 落盘时整体刷新、load() 进程内只读一次盘，生命周期已闭环。

    suspend fun save(
        context: Context,
        updateTime: String,
        rooms: List<ProcessedClassroomData>,
        roomDays: List<Int>
    ) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val sb = StringBuilder()
            sb.append(HEADER).append('\n')
            sb.append("updateTime=$updateTime").append('\n')
            sb.append("fetchedAt=${System.currentTimeMillis()}").append('\n')
            for (i in rooms.indices) {
                val r = rooms[i]
                sb.append("${roomDays[i]}|${r.timeSlot}|${r.building}|${r.name}").append('\n')
            }
            cachePath(context).writeText(sb.toString(), Charsets.UTF_8)
            // 同步刷新内存缓存，后续查询直接命中
            val data = CloudCacheData().apply {
                this.updateTime = updateTime
                fetchedAt = System.currentTimeMillis()
                this.rooms = rooms.toMutableList()
                this.roomDays = roomDays.toMutableList()
                buildIndex()
            }
            memoryCache = data
        } catch (e: Exception) {
            Logger.warn("云端缓存写入失败: ${e.message}")
        }
    }

    suspend fun load(context: Context): CloudCacheData? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // 内存命中即返回：进程内只读一次盘。save() 落盘时会同步刷新内存缓存，
            // 两者始终一致，无需 TTL 过期重读
            memoryCache?.let { return@withContext it }
            try {
                val f = cachePath(context)
                if (!f.exists() || f.length() <= 0) return@withContext null
                val content = f.readText(Charsets.UTF_8)
                val data = fromText(content)
                memoryCache = data
                data
            } catch (e: Exception) {
                Logger.warn("云端缓存读取失败: ${e.message}")
                null
            }
        }

    /** 从行文本解析（本地缓存文件与自建服务器接口共用此格式） */
    fun fromText(content: String): CloudCacheData? {
        val lines = content.split("\n")
        if (lines.size < 3 || lines[0] != HEADER) return null

        val data = CloudCacheData()
        // 初始化 7 天的索引
        data.dayIndex = MutableList(7) { mutableListOf() }

        for (line in lines) {
            when {
                line.startsWith("updateTime=") ->
                    data.updateTime = line.substring("updateTime=".length)
                line.startsWith("fetchedAt=") -> {
                    val n = line.substring("fetchedAt=".length).toLongOrNull()
                    data.fetchedAt = n ?: 0
                }
            }
        }
        for (line in lines) {
            if (line.isEmpty() || line == HEADER ||
                line.startsWith("updateTime=") || line.startsWith("fetchedAt=")
            ) continue
            val parts = line.split("|")
            if (parts.size < 4) continue
            val day = parts[0].toIntOrNull() ?: continue
            // 容器只做基本范围校验（0-6）；具体窗口（5/7 天）由调用方按需过滤
            if (day < 0 || day > 6) continue
            val p = ProcessedClassroomData(
                timeSlot = parts[1],
                building = parts[2],
                name = parts[3]
            )
            val idx = data.rooms.size
            data.rooms.add(p)
            data.roomDays.add(day)
            data.dayIndex[day].add(idx)
        }
        return data
    }
}
