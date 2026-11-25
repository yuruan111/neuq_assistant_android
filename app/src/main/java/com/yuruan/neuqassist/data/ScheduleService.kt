package com.yuruan.neuqassist.data

import com.yuruan.neuqassist.net.ClassroomQueryConfig
import com.yuruan.neuqassist.net.HttpService
import com.yuruan.neuqassist.net.ParamPair
import com.yuruan.neuqassist.net.WebvpnConfig
import com.yuruan.neuqassist.net.WebvpnSession
import com.yuruan.neuqassist.net.extractSaltFromScript

/**
 * 课表导入服务 —— 移植自 HarmonyOS 端 service/ScheduleService.ets
 *
 * 从树维教务（eams）拉取并解析个人课表
 *
 * 流程与解析规则移植自参考实现（neuq-classroom-app 的 inject.js，
 * 其解析逻辑源自 shiguang_warehouse 的 NEUQ 适配，同一套 EAMS）：
 *   1. GET  courseTableForStd.action?&sf_request_type=ajax
 *      → 提取学号 ids（bg.form.addInput(form,"ids","N")）与学期组件 tagId
 *   2. POST dataQuery.action?sf_request_type=ajax（tagId + dataType=semesterCalendar）
 *      → 学期列表（宽松 JSON，优先 JSON.parse，失败回退正则提取）
 *   3. POST courseTableForStd!courseTable.action?sf_request_type=ajax
 *      （ignoreHead=1&setting.kind=std&semester.id=N&ids=N）
 *      → 响应为空表格 + new TaskActivity(...) 脚本，只能正则提取
 *
 * 会话：完全复用工程既有链路（HttpService CookieStore + WebvpnSession），
 * 支持网页登录 Cookie 注入（applyWebSession）或账号密码登录（login）。
 */

/** 学期信息 */
data class TermInfo(val id: String, val name: String)

/** 解析出的单条课目（周次为 1 起始的数组） */
data class ImportedCourse(
    val name: String,
    val teacher: String,
    val position: String,
    val day: Int,            // 1=周一 ... 7=周日
    val startSection: Int,
    val endSection: Int,
    val weeks: List<Int>
)

/** 导入结果：课表 + 东秦权威作息（与教务一致） */
data class ImportResult(
    val courses: List<ImportedCourse>,
    val timeSlots: List<TimeSlot>,
    val termName: String
)

/** 会话过期特征 */
private fun isSessionPage(html: String): Boolean =
    html.contains("请登录") ||
        html.contains("actionError") ||
        html.contains("casLoginForm") ||
        html.contains("pwdDefaultEncryptSalt") ||
        html.contains("/do-login") ||
        html.contains("/set-fingerprint") ||
        html.contains("form['password']")

private val IDS_RE =
    Regex("bg\\.form\\.addInput\\(\\s*form\\s*,\\s*[\"']ids[\"']\\s*,\\s*[\"'](\\d+)[\"']\\s*\\)")

private val SEMESTER_TAG_RE =
    Regex("id\\s*=\\s*[\"'](semesterBar\\d+Semester)[\"']")

/** 取 JS 字面量字符串内容；变量拼接回退为变量名 */
private fun unquoteJsLiteral(tokenIn: String): String {
    val token = tokenIn.trim()
    if (token.isEmpty() || token == "null" || token == "undefined") return ""
    if ((token.startsWith("\"") && token.endsWith("\"")) ||
        (token.startsWith("'") && token.endsWith("'"))
    ) {
        return token.substring(1, token.length - 1)
    }
    return token
}

/** 按逗号分割 JS 实参，忽略引号内的逗号与转义 */
private fun splitJsArgs(argsText: String): List<String> {
    val args = mutableListOf<String>()
    val cur = StringBuilder()
    var inQuote = ' '
    var escaped = false
    for (i in argsText.indices) {
        val ch = argsText[i]
        if (escaped) {
            cur.append(ch)
            escaped = false
            continue
        }
        if (ch == '\\') {
            cur.append(ch)
            escaped = true
            continue
        }
        if (inQuote != ' ') {
            cur.append(ch)
            if (ch == inQuote) inQuote = ' '
            continue
        }
        if (ch == '"' || ch == '\'') {
            cur.append(ch)
            inQuote = ch
            continue
        }
        if (ch == ',') {
            args.add(cur.toString().trim())
            cur.clear()
            continue
        }
        cur.append(ch)
    }
    if (cur.toString().trim().isNotEmpty()) args.add(cur.toString().trim())
    return args
}

/** 周次位图：charAt(i) === '1' 表示第 i 周有课（保留 ≥1，与参考实现一致） */
private fun parseWeeksBitmap(bitmap: String): List<Int> {
    val weeks = mutableListOf<Int>()
    for (i in bitmap.indices) {
        if (bitmap[i] == '1' && i > 0) weeks.add(i)
    }
    return weeks
}

/** 去掉课名末尾的课程序号：「高等数学(01)」→「高等数学」 */
private fun cleanCourseName(name: String): String =
    name.replace(Regex("\\([\\d.]+\\)\\s*$"), "").trim()

/** 教师为变量拼接时，从前文 actTeachers 数组取真实姓名 */
private fun resolveActTeachers(src: String, endIdx: Int): String {
    val seg = src.substring(maxOf(0, endIdx - 2200), endIdx)
    val re = Regex("var\\s+actTeachers\\s*=\\s*\\[([\\s\\S]*?)\\]\\s*;")
    val last = re.findAll(seg).lastOrNull()?.groupValues?.get(1) ?: return ""

    val names = mutableListOf<String>()
    val nameRe = Regex("name\\s*:\\s*(?:\"([^\"]*)\"|'([^']*)')")
    for (nm in nameRe.findAll(last)) {
        val v = (nm.groupValues[1].ifEmpty { nm.groupValues[2] }).trim()
        if (v.isNotEmpty() && v !in names) names.add(v)
    }
    return names.joinToString(",")
}

/** 课名为变量拼接时，取前文 courseName 字面量 */
private fun resolveCourseNameVar(src: String, endIdx: Int): String {
    val seg = src.substring(maxOf(0, endIdx - 3000), endIdx)
    val re = Regex("(?:var\\s+)?courseName\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')(?:\\s*;)?")
    var value = ""
    for (m in re.findAll(seg)) {
        val v = (m.groupValues[1].ifEmpty { m.groupValues[2] }).trim()
        if (v.isNotEmpty()) value = v
    }
    return value
}

/**
 * 节次间隔是否允许合并为连堂：仅正常课间（间隔 ≤45 分钟）才合并。
 * 东秦作息第4节 11:40 结束、第5节 14:00 开始（午休），第8节与第9节之间隔晚饭——
 * 节次号相邻 ≠ 时间相邻，「上午理论课 + 下午实验课」不能拼成一张跨午休的大卡。
 */
private fun canMergeSections(prevEnd: Int, nextStart: Int): Boolean {
    if (prevEnd < 1 || prevEnd > NEUQ_TIME_SLOTS.size ||
        nextStart < 1 || nextStart > NEUQ_TIME_SLOTS.size
    ) {
        return true // 超出作息表的节次无法判定间隔，保持旧行为
    }
    fun toMin(t: String): Int {
        val parts = t.split(":")
        return parts[0].toInt() * 60 + parts[1].toInt()
    }
    val gap = toMin(NEUQ_TIME_SLOTS[nextStart - 1].startTime) -
        toMin(NEUQ_TIME_SLOTS[prevEnd - 1].endTime)
    return gap <= 45
}

/** 相邻节次同课合并 */
private fun mergeContiguous(list: List<ImportedCourse>): List<ImportedCourse> {
    val arr = list.filter {
        it.name.isNotEmpty() && it.day >= 1 && it.startSection >= 1 && it.endSection >= it.startSection
    }.sortedWith(compareBy({ it.day }, { it.startSection }))

    val out = mutableListOf<ImportedCourse>()
    for (item in arr) {
        val prev = out.lastOrNull()
        // same 已包含 prev != null；此处用 ?.let 让 Kotlin 完成 smart cast，
        // 避免出现恒真条件告警
        prev?.takeIf {
            it.name == item.name && it.teacher == item.teacher &&
                it.position == item.position && it.day == item.day &&
                it.weeks.joinToString(",") == item.weeks.joinToString(",") &&
                it.endSection + 1 == item.startSection &&
                canMergeSections(it.endSection, item.startSection)
        }?.let { matched ->
            out[out.lastIndex] = matched.copy(endSection = item.endSection)
        } ?: out.add(item)
    }
    return out
}

/** 从课表页脚本解析全部 TaskActivity */
private fun parseTaskActivities(src: String): List<ImportedCourse> {
    val courses = mutableListOf<ImportedCourse>()
    if (src.isEmpty()) return courses

    val re = Regex(
        "activity\\s*=\\s*new\\s+TaskActivity\\(([\\s\\S]*?)\\);" +
            "([\\s\\S]*?)(?=var\\s+taskId|activity\\s*=\\s*new|$)"
    )
    for (m in re.findAll(src)) {
        val args = splitJsArgs(m.groupValues[1])
        if (args.size < 7) continue

        var teacher = unquoteJsLiteral(args[1])
        if (!Regex("^['\"]").containsMatchIn(args[1].trim()) &&
            Regex("join\\s*\\(|actTeachers").containsMatchIn(args[1])
        ) {
            val rt = resolveActTeachers(src, m.range.first)
            if (rt.isNotEmpty()) teacher = rt
        }

        var name = unquoteJsLiteral(args[3])
        if (!Regex("^['\"]").containsMatchIn(args[3].trim()) &&
            args[3].contains("courseName")
        ) {
            val rn = resolveCourseNameVar(src, m.range.first)
            if (rn.isNotEmpty()) {
                val sm = Regex("\\+\\s*[\"']([^)]+)[\"']$").find(args[3])
                name = rn + (if (sm != null) "(${sm.groupValues[1]})" else "")
            }
        }
        name = cleanCourseName(name)

        val position = unquoteJsLiteral(args[5])
            .replace("\"", "").replace(Regex("\\(.*\\)"), "").trim()
        val weeks = parseWeeksBitmap(unquoteJsLiteral(args[6]))

        // 后续代码块：index = 星期 * unitCount + 节次
        val idxRe = Regex("index\\s*=\\s*(\\d+)\\s*\\*\\s*unitCount\\s*\\+\\s*(\\d+)")
        var day = -1
        val sections = mutableListOf<Int>()
        for (im in idxRe.findAll(m.groupValues[2])) {
            day = im.groupValues[1].toInt() + 1
            sections.add(im.groupValues[2].toInt() + 1)
        }

        if (day != -1 && sections.isNotEmpty()) {
            sections.sort()
            courses.add(
                ImportedCourse(
                    name = name,
                    teacher = teacher,
                    position = position,
                    day = day,
                    startSection = sections.first(),
                    endSection = sections.last(),
                    weeks = weeks
                )
            )
        }
    }
    return mergeContiguous(courses)
}

/** 宽松解析学期列表：优先 JSON.parse，失败回退正则提取 */
private fun parseLooseSemesters(raw: String): List<TermInfo> {
    val terms = mutableListOf<TermInfo>()
    try {
        val root = org.json.JSONObject(raw)
        val semesters = root.getJSONObject("semesters")
        for (gk in semesters.keys()) {
            val arr = semesters.getJSONArray(gk)
            for (i in 0 until arr.length()) {
                val rec = arr.getJSONObject(i)
                val id = if (rec.has("id")) rec.optString("id", "") else ""
                if (id.isEmpty()) continue
                val schoolYear = rec.optString("schoolYear", "")
                val name = rec.optString("name", "")
                terms.add(TermInfo(id, "$schoolYear ${name}学期".trim()))
            }
        }
        if (terms.isNotEmpty()) return terms
    } catch (e: Exception) {
        // 回退正则提取
    }
    val re = Regex(
        "\"id\"\\s*:\\s*(\\d+)[^{}]*?\"schoolYear\"\\s*:\\s*\"([^\"]*)\"" +
            "[^{}]*?\"name\"\\s*:\\s*\"([^\"]*)\""
    )
    for (m in re.findAll(raw)) {
        terms.add(TermInfo(m.groupValues[1], "${m.groupValues[2]} ${m.groupValues[3]}学期".trim()))
    }
    return terms
}

class ScheduleService(private val config: ClassroomQueryConfig) {

    private val http = HttpService().apply { timeoutMs = 45000 }
    private val webvpn: WebvpnSession
    private var loggedIn = false

    init {
        val wc = WebvpnConfig().apply {
            enabled = config.webvpnEnabled
            baseUrl = config.webvpnBaseUrl
            username = config.webvpnUsername
            password = config.webvpnPassword
            authType = config.webvpnAuthType
        }
        webvpn = WebvpnSession(http, wc)
    }

    /** 注入网页登录会话 Cookie（跳过账号密码登录） */
    fun applyWebSession(cookieHeader: String) {
        http.cookieStore.clear()
        http.cookieStore.importFromHeader(cookieHeader)
        loggedIn = true
    }

    /** 账号密码登录教务（2026-10 起教务走统一身份认证 CAS；内含 WebVPN 会话建立） */
    suspend fun login(username: String, password: String) {
        webvpn.ensureLogin()
        val casLoginUrl = webvpn.proxiedAsync(
            com.yuruan.neuqassist.net.CasAuth.EAMS_CAS_LOGIN_URL
        )
        com.yuruan.neuqassist.net.CasAuth.loginEamsVia(
            casLoginUrl = casLoginUrl,
            client = http,
            eamsBaseUrl = config.baseUrl,
            eamsHomePath = "homeExt.action",
            username = username,
            password = password
        )
        loggedIn = true
    }

    /** 确保可访问教务（WebVPN 会话 + 已登录或已有 Cookie） */
    private suspend fun ensureAccess(hasCredentials: Boolean) {
        webvpn.ensureLogin()
        if (!loggedIn && hasCredentials) {
            login(config.username, config.password)
        }
    }

    /** GET 课表入口页并做会话校验 */
    private suspend fun getEntryHtml(): String {
        val url = webvpn.proxiedAsync(
            "${config.baseUrl}courseTableForStd.action?&sf_request_type=ajax"
        )
        val resp = http.get(url)
        if (isSessionPage(resp.body)) {
            loggedIn = false
            throw AppError.loginFailed("教务会话已过期，请重新登录后重试")
        }
        return resp.body
    }

    /** 第一步：拉取可选学期列表 */
    suspend fun fetchTerms(): List<TermInfo> {
        ensureAccess(config.username.isNotEmpty() && config.password.isNotEmpty())
        val html = getEntryHtml()
        val idsM = IDS_RE.find(html)
        val tagM = SEMESTER_TAG_RE.find(html)
        if (idsM == null || tagM == null) {
            throw AppError.process("未能识别学号或学期组件，请确认已登录教务")
        }

        val url = webvpn.proxiedAsync("${config.baseUrl}dataQuery.action?sf_request_type=ajax")
        val params = listOf(
            ParamPair("tagId", tagM.groupValues[1]),
            ParamPair("dataType", "semesterCalendar")
        )
        val resp = http.postForm(url, params)
        val terms = parseLooseSemesters(resp.body)
        if (terms.isEmpty()) {
            throw AppError.process("学期列表为空（响应 ${resp.body.length} 字节）")
        }
        return terms
    }

    /** 第二步：按学期拉取课表并解析 */
    suspend fun fetchSchedule(semesterId: String, termName: String): ImportResult {
        ensureAccess(config.username.isNotEmpty() && config.password.isNotEmpty())
        val html = getEntryHtml()
        val idsM = IDS_RE.find(html)
            ?: throw AppError.process("未能识别学号，请确认已登录")

        val url = webvpn.proxiedAsync(
            "${config.baseUrl}courseTableForStd!courseTable.action?sf_request_type=ajax"
        )
        val params = listOf(
            ParamPair("ignoreHead", "1"),
            ParamPair("setting.kind", "std"),
            ParamPair("startWeek", ""),
            ParamPair("semester.id", semesterId),
            ParamPair("ids", idsM.groupValues[1])
        )
        val resp = http.postForm(url, params)
        if (isSessionPage(resp.body)) {
            loggedIn = false
            throw AppError.loginFailed("教务会话已过期，请重新登录后重试")
        }

        val courses = parseTaskActivities(resp.body)
        if (courses.isEmpty()) {
            val count = Regex("TaskActivity").findAll(resp.body).count()
            throw AppError.process(
                "未解析到课程（响应 ${resp.body.length} 字节，" +
                    "${if (count > 0) "含 TaskActivity $count 处" else "无 TaskActivity"}）。" +
                    "教务可能已改版，请稍后重试或反馈"
            )
        }
        return ImportResult(
            courses = courses,
            timeSlots = NEUQ_TIME_SLOTS,
            termName = termName
        )
    }
}
