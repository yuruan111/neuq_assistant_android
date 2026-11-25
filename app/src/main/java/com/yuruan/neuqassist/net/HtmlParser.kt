package com.yuruan.neuqassist.net

import com.yuruan.neuqassist.data.AppError
import com.yuruan.neuqassist.data.Logger
import com.yuruan.neuqassist.data.extractJsonString
import com.yuruan.neuqassist.net.HttpService
import com.yuruan.neuqassist.net.ParamPair
import com.yuruan.neuqassist.net.WebvpnCrypto

/**
 * HTML 解析模块 —— 移植自 HarmonyOS 端 parser/HtmlParser.ets（Rust src/parser.rs）
 *
 * 负责教务系统查询结果（gridtable 表格）的解析、登录页 salt 提取，
 * 以及异常响应的特征摘要生成（用于错误诊断）。
 */

/** 登录页 salt 提取模式：CryptoJS.SHA1('salt_value-' + form['password'].value) */
private val SALT_PATTERN = Regex("CryptoJS\\.SHA1\\('([^']+)-' \\+ form\\['password'\\]\\.value\\)")

/** 从 script 文本中提取 salt */
fun extractSaltFromScript(text: String): String? =
    SALT_PATTERN.find(text)?.groupValues?.get(1)

/** 提取 HTML 响应的特征摘要（标题 + 去除标签后的前 150 字符） */
fun htmlSnippet(html: String): String {
    var title = ""
    val titleMatch = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(html)
    if (titleMatch != null) title = titleMatch.groupValues[1].trim()

    val text = stripTags(html).take(150)

    if (title.isEmpty() && text.isEmpty()) return "（空响应）"
    return "title='$title' 正文='$text'"
}

/** 去除 HTML 标签并压缩空白（先剔除 script/style 正文，避免诊断串被脚本淹没） */
fun stripTags(html: String): String =
    html.replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<[^>]*>"), " ")
        .split(Regex("\\s+")).filter { it.isNotEmpty() }
        .joinToString(" ")

/** 解码常见 HTML 实体 */
private fun decodeEntities(s: String): String = s
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&nbsp;", " ")

/** 提取单元格内的文本（去标签 + 解码实体 + 去首尾空白） */
private fun cellText(cellHtml: String): String =
    decodeEntities(cellHtml.replace(Regex("<[^>]*>"), "")).trim()

/**
 * 解析教室查询返回的 HTML —— 对应 Rust parse_classroom_html
 *
 * 响应中必须有 gridtable 结果表格；缺失说明响应异常（会话失效的登录页、
 * 服务端错误页等），返回错误而非空结果，以免把异常响应误写成"无空闲教室"。
 * 表格存在但没有数据行，才是合法的空结果。
 */
fun parseClassroomHtml(html: String): List<com.yuruan.neuqassist.data.RawClassroomData> {
    val tableHtml = extractGridTableHtml(html) ?: throw AppError.fetch(
        "响应中未找到结果表格（可能是登录页或错误页），响应特征: ${htmlSnippet(html)}"
    )

    val results = mutableListOf<com.yuruan.neuqassist.data.RawClassroomData>()
    var headerNames: List<String> = emptyList()
    var headerSeen = false
    val rowRe = Regex("<tr\\b[^>]*>([\\s\\S]*?)</tr>", RegexOption.IGNORE_CASE)

    for (rowMatch in rowRe.findAll(tableHtml)) {
        val rowHtml = rowMatch.groupValues[1]
        val thCells = extractCells(rowHtml, "th")
        if (thCells.isNotEmpty()) {
            // 第一行 th 视为表头（后续 th 行忽略，避免表头被当数据）
            if (!headerSeen) {
                headerNames = thCells
                headerSeen = true
            }
            continue
        }

        val tdCells = extractCells(rowHtml, "td")
        if (tdCells.isEmpty()) continue
        // 个别页面表头用 td 书写：按已知列名识别，避免把表头当数据
        if (!headerSeen && looksLikeHeaderRow(tdCells)) {
            headerNames = tdCells
            headerSeen = true
            continue
        }

        var building = ""
        var name = ""
        var rawCapacity = ""
        var equipment = ""
        if (headerSeen) {
            // 按表头名映射字段：不依赖列序，多余列（序号/操作等）自动忽略
            val limit = minOf(headerNames.size, tdCells.size)
            for (c in 0 until limit) {
                when (mapHeaderNameToField(headerNames[c])) {
                    "building" -> building = tdCells[c]
                    "name" -> name = tdCells[c]
                    "capacity" -> rawCapacity = tdCells[c]
                    "equipmentConfig" -> equipment = tdCells[c]
                }
            }
        } else {
            // 无表头时按固定列序（教学楼/名称/容量/教室设备配置）取值，缺列或多余列跳过
            building = tdCells.getOrElse(0) { "" }
            name = tdCells.getOrElse(1) { "" }
            rawCapacity = tdCells.getOrElse(2) { "" }
            equipment = tdCells.getOrElse(3) { "" }
        }

        val meaningful = building.isNotEmpty() || name.isNotEmpty() ||
            rawCapacity.isNotEmpty() || equipment.isNotEmpty()
        val capacity = if (rawCapacity.isEmpty()) "0" else rawCapacity
        if (meaningful) {
            results.add(
                com.yuruan.neuqassist.data.RawClassroomData(
                    building = building,
                    name = name,
                    capacity = capacity,
                    equipmentConfig = equipment
                )
            )
        }
    }

    return results
}

/** 定位 class 含 gridtable 的第一个表格，返回其内部 HTML；找不到返回 null */
private fun extractGridTableHtml(html: String): String? {
    val openRe = Regex("<table\\b[^>]*>", RegexOption.IGNORE_CASE)
    for (m in openRe.findAll(html)) {
        if (m.value.contains("gridtable", ignoreCase = true)) {
            val start = m.range.last + 1
            val close = html.indexOf("</table>", start, ignoreCase = true)
            return if (close < 0) html.substring(start) else html.substring(start, close)
        }
    }
    return null
}

/** 抽取一行中指定标签（"td" 或 "th"）的单元格文本，已去标签压缩空白 */
private fun extractCells(rowHtml: String, tag: String): List<String> {
    val re = Regex("<$tag\\b[^>]*>([\\s\\S]*?)</$tag>", RegexOption.IGNORE_CASE)
    return re.findAll(rowHtml).map { cellText(it.groupValues[1]) }.toList()
}

/** 表头名映射到字段名；映射不到返回 null（与鸿蒙 HtmlParser.mapHeaderNameToField 一致） */
private fun mapHeaderNameToField(header: String): String? {
    val name = header.replace(Regex("\\s+"), "")
    return when (name) {
        "教学楼" -> "building"
        "名称" -> "name"
        "容量" -> "capacity"
        "教室设备配置" -> "equipmentConfig"
        else -> null
    }
}

/** 识别用 td 书写的表头行（出现任一已知列名即认定） */
private fun looksLikeHeaderRow(cells: List<String>): Boolean =
    cells.any { mapHeaderNameToField(it) != null }

/** 从 JSON 文本中提取顶层字符串字段（避免依赖 JSON.parse 整体解析） */
fun extractJsonStringTop(json: String, key: String): String? = extractJsonString(json, key)
