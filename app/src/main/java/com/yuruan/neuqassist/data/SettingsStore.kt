package com.yuruan.neuqassist.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 设置仓库 —— 移植自 HarmonyOS 端各页的 preferences 读写逻辑
 * （Index.ets / ScheduleView.ets / XiaoQinView.ets）
 *
 * 统一封装 SharedPreferences 访问，避免各处手工维护键名导致静默 bug。
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(Keys.PREF_FILE, Context.MODE_PRIVATE)

    // ===== 通用 =====

    fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()

    fun getString(key: String, default: String = ""): String = prefs.getString(key, default) ?: default

    fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    fun getBoolean(key: String, default: Boolean = false): Boolean =
        prefs.getBoolean(key, default)

    fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()

    fun getInt(key: String, default: Int = 0): Int = prefs.getInt(key, default)

    // ===== 隐私闸门 =====

    fun isPrivacyAgreed(): Boolean = getBoolean(Keys.KEY_PRIVACY_AGREED, false)

    fun setPrivacyAgreed(agreed: Boolean) = putBoolean(Keys.KEY_PRIVACY_AGREED, agreed)

    // ===== 空教室设置 =====

    var username: String
        get() = getString(Keys.KEY_USERNAME)
        set(v) = putString(Keys.KEY_USERNAME, v)

    var authType: String
        get() = getString(Keys.KEY_AUTH_TYPE, "cas")
        set(v) = putString(Keys.KEY_AUTH_TYPE, v)

    var webvpnUsername: String
        get() = getString(Keys.KEY_WEBVPN_USERNAME)
        set(v) = putString(Keys.KEY_WEBVPN_USERNAME, v)

    var useWebvpn: Boolean
        get() = getBoolean(Keys.KEY_USE_WEBVPN, false)
        set(v) = putBoolean(Keys.KEY_USE_WEBVPN, v)

    /** 数据来源：false=实时查询（主通道，默认）；true=云端数据（备用通道） */
    var cloudSource: Boolean
        get() = getBoolean(Keys.KEY_CLOUD_SOURCE, false)
        set(v) = putBoolean(Keys.KEY_CLOUD_SOURCE, v)

    var serverUrl: String
        get() = getString(Keys.KEY_SERVER_URL)
        set(v) = putString(Keys.KEY_SERVER_URL, v)

    var sessionCookies: String
        get() = getString(Keys.KEY_SESSION_COOKIES)
        set(v) = putString(Keys.KEY_SESSION_COOKIES, v)

    var sessionMode: String
        get() = getString(Keys.KEY_SESSION_MODE)
        set(v) = putString(Keys.KEY_SESSION_MODE, v)

    // ===== 课表 =====

    var termStartDate: String
        get() = getString(Keys.KEY_TERM_START, "2026-08-31")
        set(v) = putString(Keys.KEY_TERM_START, v)

    fun saveTimeSlots(slots: List<TimeSlot>) {
        val arr = JSONArray()
        for (s in slots) {
            arr.put(JSONObject().put("startTime", s.startTime).put("endTime", s.endTime))
        }
        putString(Keys.KEY_TIME_SLOTS, arr.toString())
    }

    fun loadTimeSlots(): List<TimeSlot> {
        val raw = getString(Keys.KEY_TIME_SLOTS)
        if (raw.isEmpty()) return NEUQ_TIME_SLOTS
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<TimeSlot>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val st = o.optString("startTime", "")
                val et = o.optString("endTime", "")
                if (ScheduleViewUtil.isValidTime(st) && ScheduleViewUtil.isValidTime(et)) {
                    out.add(TimeSlot(st, et))
                }
            }
            if (out.isEmpty()) NEUQ_TIME_SLOTS else out
        } catch (e: Exception) {
            Logger.warn("课表 JSON 解析失败: ${e.message}")
            NEUQ_TIME_SLOTS
        }
    }

    fun saveCourses(courses: List<CourseItem>) {
        val arr = JSONArray()
        for (c in courses) {
            arr.put(
                JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("teacher", c.teacher)
                    put("position", c.position)
                    put("day", c.day)
                    put("startSection", c.startSection)
                    put("endSection", c.endSection)
                    put("colorIndex", c.colorIndex)
                    put("remark", c.remark)
                    put("weeks", JSONArray(c.weeks))
                }
            )
        }
        putString(Keys.KEY_SCHEDULE_COURSES, arr.toString())
    }

    fun loadCourses(): List<CourseItem> {
        val raw = getString(Keys.KEY_SCHEDULE_COURSES)
        if (raw.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<CourseItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val day = o.optInt("day", 0)
                val startSection = o.optInt("startSection", 0)
                val endSection = o.optInt("endSection", 0)
                val name = o.optString("name", "")
                if (name.isEmpty() || day < 1 || day > 7 ||
                    startSection < 1 || endSection < startSection
                ) continue
                val weeksRaw = o.optJSONArray("weeks") ?: JSONArray()
                val weeks = mutableListOf<Int>()
                for (j in 0 until weeksRaw.length()) {
                    val n = weeksRaw.optInt(j, 0)
                    if (n >= 1) weeks.add(n)
                }
                out.add(
                    CourseItem(
                        id = o.optString("id", "imp-${out.size + 1}"),
                        name = name,
                        teacher = o.optString("teacher", ""),
                        position = o.optString("position", ""),
                        day = day,
                        startSection = startSection,
                        endSection = endSection,
                        colorIndex = o.optInt("colorIndex", 0),
                        weeks = weeks,
                        remark = o.optString("remark", "")
                    )
                )
            }
            ScheduleViewUtil.dedupeCourseIds(out)
            out
        } catch (e: Exception) {
            Logger.warn("课表 JSON 解析失败: ${e.message}")
            emptyList()
        }
    }

    // ===== 外观 =====

    var bgEnabled: Boolean
        get() = getBoolean(Keys.KEY_BG_ENABLED, true)
        set(v) = putBoolean(Keys.KEY_BG_ENABLED, v)

    var bgImagePath: String
        get() = getString(Keys.KEY_BG_IMAGE_PATH)
        set(v) = putString(Keys.KEY_BG_IMAGE_PATH, v)

    var bgDim: Float
        get() = Personalize.normalizeBgDim(getFloatCompat(Keys.KEY_BG_DIM, Personalize.DEFAULT_BG_DIM))
        set(v) = putFloatCompat(Keys.KEY_BG_DIM, v)

    var lineWeight: String
        get() = Personalize.normalizeLineWeight(getString(Keys.KEY_LINE_WEIGHT, Personalize.DEFAULT_LINE_WEIGHT))
        set(v) = putString(Keys.KEY_LINE_WEIGHT, v)

    // ===== 个性化（校验规则与 hap common/Personalize.ets 一致，读取侧即回退默认值） =====

    var themePreset: String
        get() = Personalize.normalizeThemeKey(getString(Keys.KEY_THEME_PRESET, Personalize.DEFAULT_THEME_KEY))
        set(v) = putString(Keys.KEY_THEME_PRESET, v)

    var scheduleFontScale: String
        get() = Personalize.normalizeFontScale(getString(Keys.KEY_SCHEDULE_FONT_SCALE, Personalize.DEFAULT_FONT_SCALE))
        set(v) = putString(Keys.KEY_SCHEDULE_FONT_SCALE, v)

    var scheduleCardRadius: Int
        get() = Personalize.normalizeCardRadius(getIntCompat(Keys.KEY_SCHEDULE_CARD_RADIUS, Personalize.DEFAULT_CARD_RADIUS))
        set(v) = putInt(Keys.KEY_SCHEDULE_CARD_RADIUS, v)

    var scheduleRowHeight: String
        get() = Personalize.normalizeRowHeight(getString(Keys.KEY_SCHEDULE_ROW_HEIGHT, Personalize.DEFAULT_ROW_HEIGHT))
        set(v) = putString(Keys.KEY_SCHEDULE_ROW_HEIGHT, v)

    var autoHideWeekend: Boolean
        get() = getBoolean(Keys.KEY_AUTO_HIDE_WEEKEND, Personalize.DEFAULT_AUTO_HIDE_WEEKEND)
        set(v) = putBoolean(Keys.KEY_AUTO_HIDE_WEEKEND, v)

    var hideCourseTeacher: Boolean
        get() = getBoolean(Keys.KEY_HIDE_COURSE_TEACHER, Personalize.DEFAULT_HIDE_TEACHER)
        set(v) = putBoolean(Keys.KEY_HIDE_COURSE_TEACHER, v)

    var defaultLaunchTab: Int
        get() = Personalize.normalizeLaunchTab(getIntCompat(Keys.KEY_DEFAULT_LAUNCH_TAB, Personalize.DEFAULT_LAUNCH_TAB))
        set(v) = putInt(Keys.KEY_DEFAULT_LAUNCH_TAB, v)

    private fun getFloatCompat(key: String, default: Float): Float =
        try {
            prefs.getFloat(key, default)
        } catch (e: ClassCastException) {
            // 旧数据以字符串存放时的兼容路径
            prefs.getString(key, null)?.toFloatOrNull() ?: default
        }

    /** Int 兼容读取：历史数据以字符串 / 浮点落盘时转换为 Int，无法转换回退默认 */
    private fun getIntCompat(key: String, default: Int): Int =
        try {
            prefs.getInt(key, default)
        } catch (e: ClassCastException) {
            val raw = try {
                prefs.getFloat(key, Float.NaN)
            } catch (e: ClassCastException) {
                prefs.getString(key, null)?.toFloatOrNull() ?: Float.NaN
            }
            if (raw.isNaN()) default else raw.roundToInt()
        }

    private fun putFloatCompat(key: String, value: Float) =
        prefs.edit().putFloat(key, value).apply()
}

/**
 * 课表视图工具类 —— 移植自 HarmonyOS 端 ScheduleView.ets 的 ScheduleViewUtil
 * 与 DateUtilEx
 */
object ScheduleViewUtil {

    /** 'HH:MM' → 当日分钟数（格式或取值非法返回 -1） */
    fun toMinutes(t: String): Int {
        if (!isValidTime(t)) return -1
        val parts = t.split(":")
        return parts[0].toInt() * 60 + parts[1].toInt()
    }

    /** 当日分钟数 → 'HH:MM'（跨日自动回绕到一天内） */
    fun fromMinutes(total: Int): String {
        val day = ((total % 1440) + 1440) % 1440
        val h = day / 60
        val m = day % 60
        return "${if (h < 10) "0$h" else "$h"}:${if (m < 10) "0$m" else "$m"}"
    }

    /** 校验 HH:MM（24 小时制） */
    fun isValidTime(text: String): Boolean {
        if (!Regex("^\\d{2}:\\d{2}$").matches(text)) return false
        val parts = text.split(":")
        val h = parts[0].toInt()
        val m = parts[1].toInt()
        return h in 0..23 && m in 0..59
    }

    /** 生成 1~20 周的完整周次数组 */
    fun allWeeks(): List<Int> = (1..20).toList()

    /** 单周 */
    fun oddWeeks(): List<Int> = (1..20 step 2).toList()

    /** 双周 */
    fun evenWeeks(): List<Int> = (2..20 step 2).toList()

    /** 前半 */
    fun firstHalfWeeks(): List<Int> = (1..10).toList()

    /** 后半 */
    fun lastHalfWeeks(): List<Int> = (11..20).toList()

    /**
     * 从学期名解析学期序号：1=秋季/第一学期，2=春季/第二学期，0=无法识别。
     * 兼容树维教务的多种命名（真实学期名无样本可查，按超集匹配：
     * 含「秋季/第一」判 1，含「春季/第二」判 2，否则取「N学期」形态的 N）。
     */
    fun termSeqFromName(name: String): Int {
        if (name.contains("秋季") || name.contains("第一")) return 1
        if (name.contains("春季") || name.contains("第二")) return 2
        val m = Regex("([12])\\s*学期").find(name) ?: return 0
        return m.groupValues[1].toInt()
    }

    /**
     * 课程 id 去撞车归一化：老版本数据 / 教务极端数据可能出现重复 id
     * （同名、同天、同起节、仅周次不同的单双周分班）。
     * 发现重复时给后出现者追加唯一后缀。
     */
    fun dedupeCourseIds(courses: MutableList<CourseItem>) {
        val seen = HashSet<String>()
        for (i in courses.indices) {
            val c = courses[i]
            if (c.id in seen) {
                var n = 1
                while ("${c.id}~$n" in seen) n++
                courses[i] = c.copy(id = "${c.id}~$n")
            }
            seen.add(courses[i].id)
        }
    }

    /**
     * 从校历 OCR 文本中提取开学日期候选。
     * 策略：找「第一周 / 第1周 / 开学 / 报到 / 上课」等关键词，
     * 取与关键词距离最近的「X月X日」或「X.X」日期；无关键词时取全文首个日期。
     */
    fun extractTermStart(text: String, fallbackYear: Int): java.util.Date? {
        val keywords = listOf("第一周", "第1周", "第１周", "开学", "报到", "上课")
        val monthDay = Regex("(\\d{1,2})月(\\d{1,2})日")
        val dotDay = Regex("(\\d{1,2})\\.(\\d{1,2})(?!\\d)")

        data class DateHit(val index: Int, val month: Int, val day: Int)

        val hits = mutableListOf<DateHit>()
        for (m in monthDay.findAll(text)) {
            hits.add(DateHit(m.range.first, m.groupValues[1].toInt(), m.groupValues[2].toInt()))
        }
        for (m in dotDay.findAll(text)) {
            hits.add(DateHit(m.range.first, m.groupValues[1].toInt(), m.groupValues[2].toInt()))
        }
        if (hits.isEmpty()) return null

        // 关键词位置
        val kwIdx = mutableListOf<Int>()
        for (kw in keywords) {
            val i = text.indexOf(kw)
            if (i >= 0) kwIdx.add(i)
        }

        var best: DateHit? = null
        if (kwIdx.isNotEmpty()) {
            var bestDist = Int.MAX_VALUE
            for (hit in hits) {
                for (ki in kwIdx) {
                    val dist = Math.abs(hit.index - ki)
                    if (dist < bestDist) {
                        bestDist = dist
                        best = hit
                    }
                }
            }
        } else {
            best = hits.first()
        }
        val b = best ?: return null

        // 年份启发：1 月开学多属上一学年秋季学期
        val month = b.month
        var year = fallbackYear
        if (month < 2) year = fallbackYear - 1
        if (month < 1 || month > 12 || b.day < 1 || b.day > 31) return null
        return java.util.Calendar.getInstance().apply {
            clear()
            set(year, month - 1, b.day)
        }.time
    }

    /** 取某日所在周的周一（0 点） */
    fun mondayOf(date: java.util.Date): java.util.Date {
        val cal = java.util.Calendar.getInstance().apply {
            time = date
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val w = cal.get(java.util.Calendar.DAY_OF_WEEK)
        val offset = if (w == java.util.Calendar.SUNDAY) 6 else w - java.util.Calendar.MONDAY
        cal.add(java.util.Calendar.DAY_OF_MONTH, -offset)
        return cal.time
    }

    /** yyyy-MM-dd 格式 */
    fun toKey(date: java.util.Date): String {
        val cal = java.util.Calendar.getInstance().apply { time = date }
        val y = cal.get(java.util.Calendar.YEAR)
        val m = cal.get(java.util.Calendar.MONTH) + 1
        val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
        return "$y-${if (m < 10) "0$m" else "$m"}-${if (d < 10) "0$d" else "$d"}"
    }

    /** 由学期起点推算当前真实周次（钳制到 1~20） */
    fun computeRealWeek(termStart: java.util.Date, maxWeek: Int = 20): Int {
        val now = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.time
        val startCal = java.util.Calendar.getInstance().apply { time = termStart }
        startCal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        startCal.set(java.util.Calendar.MINUTE, 0)
        startCal.set(java.util.Calendar.SECOND, 0)
        startCal.set(java.util.Calendar.MILLISECOND, 0)
        val days = ((now.time - startCal.time.time) / 86400000L).toInt()
        var w = days / 7 + 1
        if (w < 1) w = 1
        if (w > maxWeek) w = maxWeek
        return w
    }

    /** 第 week 周的 7 个日期（周一~周日） */
    fun weekDates(termStart: java.util.Date, week: Int): List<java.util.Date> {
        val cal = java.util.Calendar.getInstance().apply { time = termStart }
        cal.add(java.util.Calendar.DAY_OF_MONTH, (week - 1) * 7)
        return (0 until 7).map {
            val d = cal.time
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            d
        }
    }
}
