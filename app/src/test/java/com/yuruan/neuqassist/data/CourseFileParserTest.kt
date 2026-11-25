package com.yuruan.neuqassist.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 教务导出课表（.xls 实为 HTML 网格）解析 —— 纯逻辑单测。
 *
 * 覆盖三种真实形态：正常 `<th>` 表头的星期列映射、空课表（只有行头/空单元格）、
 * 以及同格堆叠的冲突课；解析器若把 `<th>` 当数据行或把空表当失败，导入链路会直接断。
 */
class CourseFileParserTest {

    private fun exportHtml(rows: String): String = """
2026-2027学年秋季 学号 2023123456 姓名 张三
<table id='manualArrangeCourseTable' class='gridtable'>
<tr><td>节次</td><th>星期一</th><th>星期二</th><th>星期三</th><th>星期四</th><th>星期五</th><th>星期六</th><th>星期日</th></tr>
$rows
</table>
<table id='courseList'><tr><td>课程列表</td></tr></table>
"""

    @Test
    fun parsesWeekdayHeaderAndSingleCourse() {
        val html = exportHtml("""
<tr><th>第一节</th><td>高等数学(01)
(张三)
(1-14 主楼A101)</td><td></td><td></td><td></td><td></td><td></td><td></td></tr>
""")
        val result = CourseFileParser.parse(html)
        assertNotNull(result)
        assertEquals("2026-2027学年秋季", result!!.semesterName)
        assertEquals(1, result.courses.size)

        val c = result.courses[0]
        assertEquals("高等数学", c.name)
        assertEquals("张三", c.teacher)
        assertEquals("主楼A101", c.position)
        assertEquals(1, c.day)
        assertEquals(1, c.startSection)
        assertEquals(1, c.endSection)
        assertEquals((1..14).toList(), c.weeks)
    }

    @Test
    fun emptyGridReturnsEmptyCourseListInsteadOfFailure() {
        val html = exportHtml("""
<tr><td>第一节</td><td></td><td></td><td></td><td></td><td></td><td></td><td></td></tr>
""")
        val result = CourseFileParser.parse(html)
        assertNotNull(result)
        assertEquals(0, result!!.courses.size)
        assertEquals("2026-2027学年秋季", result.semesterName)
    }

    @Test
    fun missesAnchorWhenExportFileIsNotEamsGrid() {
        assertNull(CourseFileParser.parse("<html><body>not an export</body></html>"))
    }

    @Test
    fun expandsOddAndEvenWeekSegments() {
        val html = exportHtml("""
<tr><th>第一节</th><td>大学物理(02)
(李四)
(2-14双 主楼B201)</td><td></td><td></td><td></td><td></td><td></td><td></td></tr>
""")
        val c = CourseFileParser.parse(html)!!.courses[0]
        assertEquals(listOf(2, 4, 6, 8, 10, 12, 14), c.weeks)
    }

    @Test
    fun keepsSingleWeekCourseOnLaterWeek() {
        val html = exportHtml("""
<tr><th>第一节</th><td>形势与政策(05)
(赵六)
(15 主楼D401)</td><td></td><td></td><td></td><td></td><td></td><td></td></tr>
""")
        val c = CourseFileParser.parse(html)!!.courses[0]
        assertEquals(listOf(15), c.weeks)
    }

    @Test
    fun splitsStackedConflictCoursesInsideOneCell() {
        val html = exportHtml("""
<tr><th>第一节</th><td>大学物理(02)
(李四)
(1-14 主楼B201)
体育(03)
(王五)
(1-8 操场)</td><td></td><td></td><td></td><td></td><td></td><td></td></tr>
""")
        val courses = CourseFileParser.parse(html)!!.courses
        assertEquals(2, courses.size)
        assertTrue(courses.any { it.name == "大学物理" && it.teacher == "李四" })
        assertTrue(courses.any { it.name == "体育" && it.teacher == "王五" && it.position == "操场" })
    }

    @Test
    fun mapsCourseToColumnDayFromHeader() {
        // 第 3 个单元格（星期三）上的课程必须落到 day=3，不能被列偏移错算成星期二
        val html = exportHtml("""
<tr><th>第一节</th><td></td><td></td><td>电路分析(04)
(钱七)
(1-14 科技楼S402)</td><td></td><td></td><td></td><td></td></tr>
""")
        val c = CourseFileParser.parse(html)!!.courses[0]
        assertEquals(3, c.day)
        assertEquals("科技楼S402", c.position)
    }
}
