package com.yuruan.neuqassist.data

import com.yuruan.neuqassist.net.HttpService
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private val cache = HashMap<String, HolidayInfo>()

    /** 已成功加载的年份 */
    private val loadedYears = HashSet<Int>()

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    /** 加载某年节假日数据（缓存优先；失败可重试） */
    suspend fun getYearMap(year: Int) {
        if (year in loadedYears) return
        loadedYears.add(year)
        try {
            val resp = http.get("https://timor.tech/api/holiday/year/$year")
            if (resp.status != 200) {
                loadedYears.remove(year)
                return
            }
            val root = JSONObject(resp.body)
            val holidayRaw = root.optJSONObject("holiday") ?: JSONObject()
            for (k in holidayRaw.keys()) {
                val item = holidayRaw.optJSONObject(k) ?: continue
                cache["$year-$k"] = HolidayInfo(
                    name = item.optString("name"),
                    isRest = item.optBoolean("holiday", false)
                )
            }
            Logger.info("节假日数据加载成功: $year 年 ${holidayRaw.length()} 条")
        } catch (e: Exception) {
            loadedYears.remove(year)
            Logger.warn("节假日数据获取失败(年份 $year): ${e.message}")
        }
    }

    /** 计算 7 天（或任意天）的休/班标注 */
    fun getFlags(dates: List<Date>): WeekHolidayFlags {
        val rest = mutableListOf<Boolean>()
        val work = mutableListOf<Boolean>()
        for (d in dates) {
            val key = dateFmt.format(d)
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
