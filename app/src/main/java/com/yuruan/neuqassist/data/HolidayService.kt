package com.yuruan.neuqassist.data

import com.yuruan.neuqassist.net.HttpService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 法定节假日 / 调休日 联网查询服务 —— 移植自 HarmonyOS 端 service/HolidayService.ets
 *
 * 数据源：timor.tech 免费节假日 API（无需密钥）
 *   GET https://timor.tech/api/holiday/year/{year}
 *   返回 { code:0, holiday: { "MM-dd": { holiday:boolean, name:string, ... } } }
 *   holiday=true 表示法定休假日；false 表示调休补班日。
 *
 * 实现说明：
 *   - 缓存为扁平 Map（键 "yyyy-MM-dd"），查询时直接 get
 *   - loadedYears 记录已加载年份；失败时移除记录以允许重试
 *   - 离线/超时返回空结果，UI 自动无徽标降级，不阻塞课表展示
 */

/** 单条节假日信息 */
data class HolidayInfo(
    val name: String,    // 如 "国庆节"
    val isRest: Boolean  // true=法定休假（休）；false=调休补班（班）
)

/** 一周 7 天的休/班标注结果（与传入日期数组一一对应） */
data class WeekHolidayFlags(
    val restFlags: List<Boolean>,
    val workFlags: List<Boolean>
)

class HolidayService(private val http: HttpService = HttpService()) {

    /** 扁平缓存：键 "yyyy-MM-dd" */
    private val cache = ConcurrentHashMap<String, HolidayInfo>()

    /** 已成功加载的年份 */
    private val loadedYears: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** 进行中的同年份请求（并发调用共享同一 Deferred，防止重复请求或读取半成品缓存） */
    private val pending = HashMap<Int, CompletableDeferred<Unit>>()
    private val pendingLock = Any()

    /** 加载某年节假日数据（缓存优先；同年份并发去重；失败可重试） */
    suspend fun getYearMap(year: Int) {
        if (year in loadedYears) return
        var isOwner = false
        val deferred = synchronized(pendingLock) {
            if (year in loadedYears) return
            pending[year] ?: CompletableDeferred<Unit>().also {
                pending[year] = it
                isOwner = true
            }
        }
        if (!isOwner) {
            deferred.await()
            return
        }
        try {
            fetchYear(year)
            deferred.complete(Unit)
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            if (t is CancellationException) throw t
        } finally {
            synchronized(pendingLock) {
                if (pending[year] === deferred) {
                    pending.remove(year)
                }
            }
        }
    }

    /** 拉取并解析单年节假日数据，全部解析且校验 code == 0 成功后再写入 loadedYears */
    private suspend fun fetchYear(year: Int) {
        try {
            val resp = http.get("https://timor.tech/api/holiday/year/$year")
            if (resp.status != 200) return
            val root = JSONObject(resp.body)
            if (!root.has("code") || root.optInt("code", -1) != 0) {
                Logger.warn("节假日接口返回异常码(年份 $year): ${root.opt("code")}")
                return
            }
            val holidayRaw = root.optJSONObject("holiday")
            if (holidayRaw == null) {
                Logger.warn("节假日数据格式异常(年份 $year)")
                return
            }
            var count = 0
            for (k in holidayRaw.keys()) {
                val item = holidayRaw.optJSONObject(k) ?: continue
                val name = item.opt("name") as? String ?: continue
                val isRest = item.opt("holiday") as? Boolean ?: continue
                cache["$year-$k"] = HolidayInfo(name = name, isRest = isRest)
                count++
            }
            loadedYears.add(year)
            Logger.info("节假日数据加载成功: $year 年 $count 条")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.warn("节假日数据获取失败(年份 $year): ${e.message}")
        }
    }

    /** 计算 7 天（或任意天）的休/班标注 */
    fun getFlags(dates: List<Date>): WeekHolidayFlags {
        val cal = Calendar.getInstance()
        val rest = mutableListOf<Boolean>()
        val work = mutableListOf<Boolean>()
        for (d in dates) {
            cal.time = d
            val key = String.format(
                Locale.US,
                "%04d-%02d-%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH)
            )
            val info = cache[key]
            if (info == null) {
                rest.add(false)
                work.add(false)
            } else {
                rest.add(info.isRest)
                work.add(!info.isRest)
            }
        }
        return WeekHolidayFlags(rest, work)
    }
}
