package com.yuruan.neuqassist.net

import com.yuruan.neuqassist.data.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 空教室结果表解析 —— 纯逻辑单测。
 *
 * 回归点：教务 gridtable 的列不一定严格是「教学楼/名称/容量/教室设备配置」四列
 * 且次序固定（可能带序号等附加列）。旧实现按列序号硬比对表头，任何偏移都会
 * 把四个字段全部解析成空串，进而被 Processor 的「教学楼与设备配置均为空」规则
 * 整表丢弃 —— 界面表现为实时查询永远 0 间。这里锁定「按表头名映射」的语义。
 */
class HtmlParserTest {

    private fun table(headerCells: String, dataRows: String): String =
        "<table class=\"gridtable\">" +
            "<thead><tr>$headerCells</tr></thead>" +
            "<tbody>$dataRows</tbody></table>"

    @Test
    fun standardFourColumnsAreParsed() {
        val html = table(
            "<th>教学楼</th><th>名称</th><th>容量</th><th>教室设备配置</th>",
            "<tr><td>工学馆</td><td>工学馆101</td><td>60</td><td>多媒体教室</td></tr>"
        )
        val rows = parseClassroomHtml(html)
        assertEquals(1, rows.size)
        assertEquals("工学馆", rows[0].building)
        assertEquals("工学馆101", rows[0].name)
        assertEquals("60", rows[0].capacity)
        assertEquals("多媒体教室", rows[0].equipmentConfig)
    }

    @Test
    fun extraLeadingColumnDoesNotShiftFields() {
        val html = table(
            "<th>序号</th><th>教学楼</th><th>名称</th><th>容量</th><th>教室设备配置</th>",
            "<tr><td>1</td><td>科技楼</td><td>科技楼S402</td><td>80</td><td>普通教室</td></tr>"
        )
        val rows = parseClassroomHtml(html)
        assertEquals(1, rows.size)
        assertEquals("科技楼", rows[0].building)
        assertEquals("科技楼S402", rows[0].name)
        assertEquals("80", rows[0].capacity)
        assertEquals("普通教室", rows[0].equipmentConfig)
    }

    @Test
    fun reorderedColumnsAreMappedByName() {
        val html = table(
            "<th>名称</th><th>教学楼</th><th>教室设备配置</th><th>容量</th>",
            "<tr><td>人文楼305</td><td>人文楼</td><td>普通教室</td><td>50</td></tr>"
        )
        val rows = parseClassroomHtml(html)
        assertEquals(1, rows.size)
        assertEquals("人文楼", rows[0].building)
        assertEquals("人文楼305", rows[0].name)
        assertEquals("50", rows[0].capacity)
        assertEquals("普通教室", rows[0].equipmentConfig)
    }

    @Test
    fun tdWrittenHeaderRowIsSkippedAsHeader() {
        val html = "<table class=\"gridtable\"><tbody>" +
            "<tr><td>教学楼</td><td>名称</td><td>容量</td><td>教室设备配置</td></tr>" +
            "<tr><td>管理楼</td><td>管理楼210</td><td>40</td><td>普通教室</td></tr>" +
            "</tbody></table>"
        val rows = parseClassroomHtml(html)
        assertEquals(1, rows.size)
        assertEquals("管理楼", rows[0].building)
        assertEquals("管理楼210", rows[0].name)
    }

    @Test
    fun headerWhitespaceIsTolerated() {
        val html = table(
            "<th> 教学楼 </th><th>名称</th><th>\n容量\t</th><th>教室设备配置</th>",
            "<tr><td>基础楼</td><td>基础楼203</td><td>45</td><td>普通教室</td></tr>"
        )
        val rows = parseClassroomHtml(html)
        assertEquals(1, rows.size)
        assertEquals("基础楼", rows[0].building)
        assertEquals("45", rows[0].capacity)
    }

    @Test
    fun noHeaderFallsBackToFixedColumnOrder() {
        val html = "<table class=\"gridtable\"><tbody>" +
            "<tr><td>地质楼</td><td>地质楼102</td><td>30</td><td>普通教室</td></tr>" +
            "</tbody></table>"
        val rows = parseClassroomHtml(html)
        assertEquals(1, rows.size)
        assertEquals("地质楼", rows[0].building)
        assertEquals("30", rows[0].capacity)
    }

    @Test
    fun emptyCapacityBecomesZeroButRowKeptWhenOtherFieldsPresent() {
        val html = table(
            "<th>教学楼</th><th>名称</th><th>容量</th><th>教室设备配置</th>",
            "<tr><td>综合实验楼</td><td>综合楼401</td><td></td><td>普通教室</td></tr>"
        )
        val rows = parseClassroomHtml(html)
        assertEquals(1, rows.size)
        assertEquals("0", rows[0].capacity)
        assertEquals("综合实验楼", rows[0].building)
    }

    @Test
    fun headerOnlyTableIsLegalEmptyResult() {
        val html = table(
            "<th>教学楼</th><th>名称</th><th>容量</th><th>教室设备配置</th>",
            ""
        )
        val rows = parseClassroomHtml(html)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun unquotedClassAttributeIsAccepted() {
        val html = "<table class=gridtable><tr>" +
            "<th>教学楼</th><th>名称</th><th>容量</th><th>教室设备配置</th></tr>" +
            "<tr><td>工学馆</td><td>工学馆202</td><td>70</td><td>多媒体教室</td></tr></table>"
        val rows = parseClassroomHtml(html)
        assertEquals(1, rows.size)
        assertEquals("工学馆202", rows[0].name)
    }

    @Test
    fun missingGridTableThrowsFetchErrorInsteadOfSilentEmpty() {
        try {
            parseClassroomHtml("<html><body>登录页</body></html>")
            fail("缺少 gridtable 时应抛 fetch 错误")
        } catch (e: AppError) {
            assertEquals(AppError.Kind.Fetch, e.kind)
        }
    }
}
