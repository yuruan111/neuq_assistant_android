package com.yuruan.neuqassist.data

/**
 * 教务「导出」学生课表文件解析器（免登录导入通道）—— 移植自 HarmonyOS 端
 * parser/CourseFileParser.ets
 *
 * 树维 EAMS 导出的 .xls 实为 HTML 表格文本（UTF-8），结构（依据 2026-10 真实样本）：
 *   表1 信息头：学期名（如 2026-2027学年秋季）/ 学号 / 姓名 / 班级 / 总学分
 *   表2 主网格：<table id="manualArrangeCourseTable" class="gridtable">，
 *       行头「第X节」（一~十二），列头「星期一~星期日」；课程格以 rowspan
 *       表达连堂（实测 1/2/3），格内多门冲突课以
 *       <br style="mso-data-placement: same-cell"> 分隔
 *   表3+：课程列表 / 上下课时间等（本解析器不使用，靠主网格的 </table> 截断）
 *
 * 单元格条目（逐行，行内空白已规整）：
 *   课名        (课程序号)
 *           (教师)
 *           (周次 教室(校区))      周次写法（2026-11 样本实测）：
 *             '15' / '1-14' / '2-14双'（单同构）/ '1-2,4' / '1-3单,4-8'
 *             ——逗号分段、段可带单双；教室可整体为空（如 '(15  )'，换教师
 *             或待定教室时段），此时只展开周次、地点留空
 */

/** 从导出文件解析出的结果 */
data class CourseFileImport(
    val semesterName: String,
    val courses: List<CourseItem>
)

private val WEEKDAY_CHARS = listOf("一", "二", "三", "四", "五", "六", "日")

/** 中文数字（一~三十）→ 阿拉伯数字，失败返回 -1 */
private fun cnToNumber(cn: String): Int {
    val digits = listOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九")
    if (cn == "十") return 10
    val tenIdx = cn.indexOf("十")
    if (tenIdx < 0) return digits.indexOf(cn)
    var value = 0
    val before = cn.substring(0, tenIdx)
    val after = cn.substring(tenIdx + 1)
    if (before.isNotEmpty()) {
        val i = digits.indexOf(before)
        if (i <= 0) return -1
        value += i * 10
    } else {
        value += 10
    }
    if (after.isNotEmpty()) {
        val i = digits.indexOf(after)
        if (i <= 0) return -1
        value += i
    }
    return value
}

/** 行头「第X节」→ 节次序号，失败返回 -1 */
private fun periodFromHeader(plain: String): Int {
    val m = Regex("第\\s*([一二三四五六七八九十]+)\\s*节").find(plain) ?: return -1
    return cnToNumber(m.groupValues[1])
}

/** 去标签 + 实体解码；横向空白规整为单空格，保留换行（条目内分行靠它还原） */
private fun toPlain(html: String): String = html
    .replace("&nbsp;", " ")
    .replace(Regex("<[^>]*>"), " ")
    .replace(Regex("[ \\t\\u3000]+"), " ")

/** 周次写法展开：'15'→[15]，'1-14'→[1..14]，'2-14双'→其中偶数（单→奇数） */
private fun expandWeeks(loIn: Int, hiIn: Int, parity: String): List<Int> {
    val lo = minOf(loIn, hiIn)
    val hi = maxOf(loIn, hiIn)
    val out = mutableListOf<Int>()
    var w = lo
    while (w <= hi && w <= 30) {
        if (parity == "双" && w % 2 != 0) {
            w++
            continue
        }
        if (parity == "单" && w % 2 != 1) {
            w++
            continue
        }
        out.add(w)
        w++
    }
    return out
}

/** 单段周次 '15' / '1-14' / '2-14双' → 周次数组，格式不符返回空列表 */
private fun parseWeekSegment(seg: String): List<Int> {
    val m = Regex("^(\\d{1,2})(?:-(\\d{1,2}))?(单|双)?$").find(seg.trim()) ?: return emptyList()
    val hi = if (m.groupValues[2].isEmpty()) m.groupValues[1].toInt() else m.groupValues[2].toInt()
    return expandWeeks(m.groupValues[1].toInt(), hi, m.groupValues[3])
}

/** 去掉教室尾部的校区括注：'管理楼3110(学校本部)' → '管理楼3110' */
private fun stripCampus(room: String): String =
    room.replace(Regex("\\((学校本部|南校区|秦皇岛校区)[^)]*\\)$"), "").trim()

/** 组装并落库一条课程；字段不完整（缺课名/周次、节次越界）时静默丢弃 */
private fun pushCourse(
    name: String,
    teacher: String,
    room: String,
    weeks: List<Int>,
    day: Int,
    startSection: Int,
    endSection: Int,
    courses: MutableList<CourseItem>,
    nameColor: MutableMap<String, Int>
) {
    if (name.isEmpty() || weeks.isEmpty() || day < 1 || day > 7 ||
        startSection < 1 || endSection < startSection
    ) return
    if (!nameColor.containsKey(name)) {
        nameColor[name] = nameColor.size % 8
    }
    val cIdx = nameColor[name] ?: 0
    courses.add(
        CourseItem(
            id = "$name-$day-$startSection-${weeks.joinToString(".")}",
            name = name,
            teacher = teacher,
            position = room,
            day = day,
            startSection = startSection,
            endSection = endSection,
            colorIndex = cIdx,
            weeks = weeks,
            remark = ""
        )
    )
}

/**
 * 解析整个课程格：格内是扁平行序列（课名(序号) → (教师) → (周次 教室)），
 * 冲突课同格堆叠时分界是「非括号行 = 新条目的课名行」——
 * 注意 <br> 分隔的是视觉行而非条目，不能按 <br> 分组。
 */
private fun parseCellEntries(
    cellHtml: String,
    day: Int,
    startSection: Int,
    endSection: Int,
    courses: MutableList<CourseItem>,
    nameColor: MutableMap<String, Int>
) {
    val lines = toPlain(cellHtml).split("\n")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    var name = ""
    var teacher = ""
    var room = ""
    var weeks: List<Int> = emptyList()
    for (line in lines) {
        val bm = Regex("^\\((.*)\\)$").find(line)
        if (bm == null) {
            // 非括号行 = 新条目的课名行：先落库上一条，再开新条目
            pushCourse(name, teacher, room, weeks, day, startSection, endSection, courses, nameColor)
            name = ""; teacher = ""; room = ""; weeks = emptyList()
            val nm = Regex("^(.*?)\\s*\\((\\d[\\d.]*)\\)\\s*$").find(line)
            name = if (nm == null) line else nm.groupValues[1].trim()
            continue
        }
        val inner = bm.groupValues[1].trim()
        // 周次行：数字段（可逗号复合、段可带单双）开头，教室可缺省——
        // '(15  )' 这类空教室条目也要能落库；教室必须以空白与周次分隔，
        // 防止把独立成行的课程序号 '(3030412050.01)' 误判为周次
        val wm = Regex(
            "^(\\d{1,2}(?:-\\d{1,2})?(?:单|双)?(?:[,，]\\s*\\d{1,2}(?:-\\d{1,2})?(?:单|双)?)*)(?:\\s+(.*))?$"
        ).find(inner)
        if (wm != null) {
            weeks = wm.groupValues[1].split(Regex("[,，]"))
                .flatMap { parseWeekSegment(it) }
                .distinct()
                .sorted()
            room = stripCampus(wm.groupValues[2])
        } else if (teacher.isEmpty()) {
            teacher = inner
        }
    }
    pushCourse(name, teacher, room, weeks, day, startSection, endSection, courses, nameColor)
}

object CourseFileParser {
    /**
     * 解析导出文件全文。识别不到主网格返回 null；
     * 网格存在但无可识别课程时返回 courses 为空的结果，由调用方提示。
     */
    fun parse(contentIn: String): CourseFileImport? {
        val content = contentIn.replace("\r\n", "\n")
        val anchor = content.indexOf("manualArrangeCourseTable")
        if (anchor < 0) return null
        val tableStart = content.lastIndexOf("<table", anchor)
        val tableEnd = content.indexOf("</table>", anchor)
        if (tableStart < 0 || tableEnd < 0) return null
        val table = content.substring(tableStart, tableEnd)

        var semesterName = ""
        Regex("(\\d{4}-\\d{4}学年[春夏秋]季)").find(content)?.let {
            semesterName = it.groupValues[1]
        }

        // 表头：列号 → 星期（col 0 = 节次列）；仅扫描首个含「星期X」的表头行，避免数据行含 <th> 错位
        val dayByCol = HashMap<Int, Int>()
        val weekdayHeaderRe = Regex("星期[一二三四五六日]")
        for (headerM in Regex("<tr[^>]*>([\\s\\S]*?)</tr>").findAll(table)) {
            val headerRowHtml = headerM.groupValues[1]
            if (weekdayHeaderRe.containsMatchIn(toPlain(headerRowHtml))) {
                var thCol = 0
                for (thM in Regex("<(td|th)[^>]*>([\\s\\S]*?)</\\1>").findAll(headerRowHtml)) {
                    val plainTh = toPlain(thM.groupValues[2])
                    for (d in 0 until 7) {
                        if (plainTh.contains("星期${WEEKDAY_CHARS[d]}")) dayByCol[thCol] = d + 1
                    }
                    thCol++
                }
                break
            }
        }
        if (dayByCol.isEmpty()) {
            for (c in 1..7) dayByCol[c] = c
        }

        val courses = mutableListOf<CourseItem>()
        val nameColor = mutableMapOf<String, Int>()
        // rowspan 网格重建：occupied[col] = 该列被占用的截止行号（含）
        val occupied = HashMap<Int, Int>()
        var rowIdx = 0
        var lastPeriod = 0

        for (rowM in Regex("<tr[^>]*>([\\s\\S]*?)</tr>").findAll(table)) {
            val rowHtml = rowM.groupValues[1]
            if (!weekdayHeaderRe.containsMatchIn(toPlain(rowHtml))) {
                rowIdx++
                var col = 0
                val cellRe = Regex("<(td|th)([^>]*)>([\\s\\S]*?)</\\1>")
                for (cellM in cellRe.findAll(rowHtml)) {
                    // 跳过被上方 rowspan 占用的列
                    while (true) {
                        val until = occupied[col]
                        if (until != null && until >= rowIdx) col++ else break
                    }
                    val attrs = cellM.groupValues[2]
                    val rsM = Regex("rowspan\\s*=\\s*[\"']?(\\d+)").find(attrs)
                    val rowspan = rsM?.groupValues?.get(1)?.toInt() ?: 1
                    val plain = toPlain(cellM.groupValues[3])

                    val periodM = Regex("第\\s*([一二三四五六七八九十]+)\\s*节").find(plain)
                    if (periodM != null) {
                        lastPeriod = cnToNumber(periodM.groupValues[1]) // 行头格：更新当前节次
                    } else if (plain.trim().isNotEmpty() && lastPeriod >= 1) {
                        val dayDef = dayByCol[col]
                        val day = dayDef ?: col
                        val startSection = lastPeriod
                        val endSection = lastPeriod + rowspan - 1
                        parseCellEntries(
                            cellM.groupValues[3], day, startSection, endSection, courses, nameColor
                        )
                    }
                    if (rowspan > 1) occupied[col] = rowIdx + rowspan - 1
                    col++
                }
            }
        }

        // 导出对冲突时段按周展开，同名同师同室同节会产生多条单周碎片；
        // 按槽位归并周次并集，得到与网页通道等价的逻辑课程
        fun slotKey(c: CourseItem): String =
            "${c.name}|${c.teacher}|${c.position}|${c.day}|${c.startSection}|${c.endSection}"

        val mergedWeeks = HashMap<String, MutableList<Int>>()
        val mergedCourse = HashMap<String, CourseItem>()
        val order = mutableListOf<String>()
        for (c in courses) {
            val key = slotKey(c)
            val exist = mergedWeeks[key]
            if (exist == null) {
                mergedWeeks[key] = c.weeks.toMutableList()
                mergedCourse[key] = c
                order.add(key)
            } else {
                for (w in c.weeks) {
                    if (w !in exist) exist.add(w)
                }
                exist.sort()
            }
        }
        val out = mutableListOf<CourseItem>()
        for (key in order) {
            val c = mergedCourse[key]
            val ws = mergedWeeks[key]
            if (c != null && ws != null) {
                out.add(c.copy(weeks = ws, id = "${c.name}-${c.day}-${c.startSection}-${ws.joinToString(".")}"))
            }
        }

        return CourseFileImport(semesterName = semesterName, courses = out)
    }
}
