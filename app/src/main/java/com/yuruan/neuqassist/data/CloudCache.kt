package com.yuruan.neuqassist.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

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
    private const val CLOUD_FILE = "cloud_cache.txt"
    private const val LOCAL_FILE = "local_cache.txt"

    /** 独立缓存桶：隔离云端（cloud_cache.txt）与本地一键更新（local_cache.txt）的内存、写锁与版本号 */
    private class CacheBucket(val fileName: String) {
        @Volatile
        var memory: CloudCacheData? = null
        val writeMutex = Mutex()
        val revLock = Any()
        var revision: Long = 0L
    }

    private val cloudBucket = CacheBucket(CLOUD_FILE)
    private val localBucket = CacheBucket(LOCAL_FILE)

    private fun cachePath(context: Context, fileName: String) = File(context.filesDir, fileName)

    fun isFresh(data: CloudCacheData): Boolean =
        data.fetchedAt > 0 && (System.currentTimeMillis() - data.fetchedAt) < MAX_AGE_MS

    // 说明：不设 invalidate() 之类的手动失效接口 —— 缓存与会话无关，
    // 内存缓存由 save()/saveLocal() 落盘时整体刷新、load()/loadLocal() 进程内只读一次盘，生命周期已闭环。

    /** 写入云端缓存（cloud_cache.txt），与本地一键更新缓存物理隔离 */
    suspend fun save(
        context: Context,
        updateTime: String,
        rooms: List<ProcessedClassroomData>,
        roomDays: List<Int>
    ) {
        saveToBucket(cloudBucket, context, updateTime, rooms, roomDays, System.currentTimeMillis())
    }

    /** 读取云端缓存（cloud_cache.txt） */
    suspend fun load(context: Context): CloudCacheData? = loadFromBucket(cloudBucket, context)

    /** 写入本地一键更新缓存（local_cache.txt），不污染云端 cloud_cache.txt */
    suspend fun saveLocal(context: Context, data: CloudCacheData) {
        val ts = if (data.fetchedAt > 0) data.fetchedAt else System.currentTimeMillis()
        if (data.fetchedAt <= 0) data.fetchedAt = ts
        saveToBucket(localBucket, context, data.updateTime, data.rooms, data.roomDays, ts)
    }

    /** 写入本地一键更新缓存重载（与 save 参数风格一致） */
    suspend fun saveLocal(
        context: Context,
        updateTime: String,
        rooms: List<ProcessedClassroomData>,
        roomDays: List<Int>
    ) {
        saveToBucket(localBucket, context, updateTime, rooms, roomDays, System.currentTimeMillis())
    }

    /** 读取本地一键更新缓存（local_cache.txt） */
    suspend fun loadLocal(context: Context): CloudCacheData? = loadFromBucket(localBucket, context)

    private suspend fun saveToBucket(
        bucket: CacheBucket,
        context: Context,
        updateTime: String,
        rooms: List<ProcessedClassroomData>,
        roomDays: List<Int>,
        fetchedAt: Long
    ) {
        val roomsCopy = rooms.toMutableList()
        val daysCopy = roomDays.toMutableList()
        bucket.writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val saved = writeAtomic(
                    cachePath(context, bucket.fileName),
                    updateTime,
                    roomsCopy,
                    daysCopy,
                    fetchedAt
                )
                if (saved != null) {
                    synchronized(bucket.revLock) {
                        bucket.revision++
                        bucket.memory = saved
                    }
                }
            }
        }
    }

    private suspend fun loadFromBucket(bucket: CacheBucket, context: Context): CloudCacheData? =
        withContext(Dispatchers.IO) {
            // 内存命中即返回：进程内只读一次盘。save/saveLocal 落盘时会同步刷新内存缓存，
            // 并通过版本号保护防止并发旧读覆盖新写
            bucket.memory?.let { return@withContext it }
            val rev = synchronized(bucket.revLock) {
                bucket.memory?.let { return@withContext it }
                bucket.revision
            }
            val data = readFromDisk(cachePath(context, bucket.fileName))
            synchronized(bucket.revLock) {
                if (bucket.memory != null) return@withContext bucket.memory
                if (data != null && bucket.revision == rev) {
                    bucket.memory = data
                }
                bucket.memory ?: data
            }
        }

    /** 写临时文件后原子 rename，避免并发读取或异常中断导致缓存文件截断损坏 */
    private fun writeAtomic(
        target: File,
        updateTime: String,
        rooms: MutableList<ProcessedClassroomData>,
        roomDays: MutableList<Int>,
        fetchedAt: Long
    ): CloudCacheData? {
        val tmpFile = File(target.parentFile, "${target.name}.tmp")
        return try {
            val sb = StringBuilder()
            sb.append(HEADER).append('\n')
            sb.append("updateTime=").append(updateTime).append('\n')
            sb.append("fetchedAt=").append(fetchedAt).append('\n')
            val count = minOf(rooms.size, roomDays.size)
            for (i in 0 until count) {
                val r = rooms[i]
                sb.append("${roomDays[i]}|${r.timeSlot}|${r.building}|${r.name}").append('\n')
            }
            target.parentFile?.mkdirs()
            tmpFile.writeText(sb.toString(), Charsets.UTF_8)
            if (!tmpFile.renameTo(target)) {
                if (target.exists()) target.delete()
                if (!tmpFile.renameTo(target)) {
                    throw IOException("原子重命名失败: ${tmpFile.name} -> ${target.name}")
                }
            }
            // 同步构造内存缓存对象，保持 fetchedAt 与落盘一致
            CloudCacheData().apply {
                this.updateTime = updateTime
                this.fetchedAt = fetchedAt
                this.rooms = rooms
                this.roomDays = roomDays
                buildIndex()
            }
        } catch (e: Exception) {
            Logger.warn("缓存写入失败(${target.name}): ${e.message}")
            try {
                if (tmpFile.exists()) tmpFile.delete()
            } catch (_: Exception) {
            }
            null
        }
    }

    private fun readFromDisk(file: File): CloudCacheData? = try {
        if (!file.exists() || file.length() <= 0) {
            null
        } else {
            fromText(file.readText(Charsets.UTF_8))
        }
    } catch (e: Exception) {
        Logger.warn("缓存读取失败(${file.name}): ${e.message}")
        null
    }

    /** 从行文本解析（本地缓存文件与自建服务器接口共用此格式） */
    fun fromText(content: String): CloudCacheData? {
        val lines = content.replace("\r\n", "\n").split("\n")
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
