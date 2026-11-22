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

/** 去除 HTML 标签并压缩空白 */
fun stripTags(html: String): String =
    html.replace(Regex("<[^>]*>"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
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
    // 定位 gridtable 表格（兼容单/双引号属性）
    val tableMatch = Regex(
        "<table[^>]*class=[\"'][^\"']*gridtable[^\"']*[\"'][^>]*>([\\s\\S]*?)</table>",
        RegexOption.IGNORE_CASE
    ).find(html) ?: throw AppError.fetch(
        "响应中未找到结果表格（可能是登录页或错误页），响应特征: ${htmlSnippet(html)}"
    )
    val tableHtml = tableMatch.groupValues[1]

    // 表头（thead th 或第一行 th）
    val headers = mutableListOf<String>()
    val thRe = Regex("<th[^>]*>([\\s\\S]*?)</th>", RegexOption.IGNORE_CASE)
    thRe.findAll(tableHtml).forEach { headers.add(cellText(it.groupValues[1])) }

    // 数据行（tbody tr / 所有含 td 的 tr）
    val results = mutableListOf<com.yuruan.neuqassist.data.RawClassroomData>()
    val trRe = Regex("<tr[^>]*>([\\s\\S]*?)</tr>", RegexOption.IGNORE_CASE)
    for (trMatch in trRe.findAll(tableHtml)) {
        val rowHtml = trMatch.groupValues[1]
        if (!rowHtml.contains("<td")) continue

        val cells = mutableListOf<String>()
        val tdRe = Regex("<td[^>]*>([\\s\\S]*?)</td>", RegexOption.IGNORE_CASE)
        tdRe.findAll(rowHtml).forEach { cells.add(cellText(it.groupValues[1])) }

        if (cells.isNotEmpty()) {
            val item = com.yuruan.neuqassist.data.RawClassroomData(
                building = pickHeader(headers, 0, cells[0], "教学楼") ?: "",
                name = pickHeader(headers, 1, cells.getOrNull(1) ?: "", "名称") ?: "",
                capacity = pickHeader(headers, 2, cells.getOrNull(2) ?: "", "容量")
                    ?.let { if (it.isEmpty()) "0" else it } ?: "",
                equipmentConfig = pickHeader(headers, 3, cells.getOrNull(3) ?: "", "教室设备配置")
                    ?: ""
            )
            results.add(item)
        }
    }

    return results
}

/** 按表头名取值：key 匹配返回该列文本，否则返回 null */
private fun pickHeader(headers: List<String>, col: Int, value: String, key: String): String? {
    val headerKey = if (col < headers.size) headers[col] else "column${col + 1}"
    return if (headerKey == key) value else null
}

/** 从 JSON 文本中提取顶层字符串字段（避免依赖 JSON.parse 整体解析） */
fun extractJsonStringTop(json: String, key: String): String? = extractJsonString(json, key)
