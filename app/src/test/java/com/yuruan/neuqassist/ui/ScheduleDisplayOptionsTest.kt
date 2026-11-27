package com.yuruan.neuqassist.ui

import com.yuruan.neuqassist.data.CourseItem
import com.yuruan.neuqassist.data.Personalize
import com.yuruan.neuqassist.data.ScheduleViewUtil
import com.yuruan.neuqassist.ui.screens.formatCompactPlace
import com.yuruan.neuqassist.ui.screens.resolveVisibleDayCount
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleDisplayOptionsTest {

    private fun courseOnDay(day: Int) = CourseItem(
        id = "c-$day",
        name = "课程$day",
        teacher = "教师",
        position = "工学馆 302",
        day = day,
        startSection = 1,
        endSection = 2,
        colorIndex = 0,
        weeks = listOf(1)
    )

    @Test
    fun normalizesAndMapsRowHeightOptions() {
        assertEquals("compact", Personalize.normalizeRowHeight("compact"))
        assertEquals("standard", Personalize.normalizeRowHeight("standard"))
        assertEquals("spacious", Personalize.normalizeRowHeight("spacious"))
        assertEquals("standard", Personalize.normalizeRowHeight("unknown"))

        assertEquals(48, Personalize.rowHeightDp("compact"))
        assertEquals(58, Personalize.rowHeightDp("standard"))
        assertEquals(70, Personalize.rowHeightDp("spacious"))
        assertEquals(58, Personalize.rowHeightDp("unknown"))
    }

    @Test
    fun resolvesVisibleDayCountForWeekendFolding() {
        val weekdayOnly = listOf(courseOnDay(1), courseOnDay(5))
        assertEquals(5, resolveVisibleDayCount(weekdayOnly, autoHideWeekend = true))
        assertEquals(7, resolveVisibleDayCount(weekdayOnly, autoHideWeekend = false))
        assertEquals(5, resolveVisibleDayCount(emptyList(), autoHideWeekend = true))

        assertEquals(7, resolveVisibleDayCount(weekdayOnly + courseOnDay(6), autoHideWeekend = true))
        assertEquals(7, resolveVisibleDayCount(weekdayOnly + courseOnDay(7), autoHideWeekend = true))
    }

    @Test
    fun formatsCompactPlaceWithoutDuplicateBuildingPrefix() {
        assertEquals("@工学馆302", formatCompactPlace("工学馆 302"))
        assertEquals("@科技楼S402", formatCompactPlace("科技楼S402"))
        assertEquals("@工学馆", formatCompactPlace("工学馆"))
        assertEquals("@302", formatCompactPlace("302"))
        assertEquals("", formatCompactPlace(""))
    }

    @Test
    fun clampsWeekAndMapsBetweenWeekAndPageIndices() {
        assertEquals(1, ScheduleViewUtil.clampWeek(-1))
        assertEquals(1, ScheduleViewUtil.clampWeek(0))
        assertEquals(1, ScheduleViewUtil.clampWeek(1))
        assertEquals(10, ScheduleViewUtil.clampWeek(10))
        assertEquals(20, ScheduleViewUtil.clampWeek(20))
        assertEquals(20, ScheduleViewUtil.clampWeek(21))
        assertEquals(20, ScheduleViewUtil.clampWeek(99))

        assertEquals(0, ScheduleViewUtil.weekToPage(-1))
        assertEquals(0, ScheduleViewUtil.weekToPage(1))
        assertEquals(9, ScheduleViewUtil.weekToPage(10))
        assertEquals(19, ScheduleViewUtil.weekToPage(20))
        assertEquals(19, ScheduleViewUtil.weekToPage(99))

        assertEquals(1, ScheduleViewUtil.pageToWeek(-1))
        assertEquals(1, ScheduleViewUtil.pageToWeek(0))
        assertEquals(10, ScheduleViewUtil.pageToWeek(9))
        assertEquals(20, ScheduleViewUtil.pageToWeek(19))
        assertEquals(20, ScheduleViewUtil.pageToWeek(20))
        assertEquals(20, ScheduleViewUtil.pageToWeek(99))
    }

    @Test
    fun formatsWeekDateLabelsAndMonthLabelForGivenWeek() {
        val termStart = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.AUGUST, 31) // 2026-08-31 周一
        }.time

        assertEquals(
            listOf("8/31", "9/1", "9/2", "9/3", "9/4", "9/5", "9/6"),
            ScheduleViewUtil.weekDateLabels(termStart, 1)
        )
        assertEquals("8月", ScheduleViewUtil.monthLabel(termStart, 1))

        assertEquals(
            listOf("9/7", "9/8", "9/9", "9/10", "9/11", "9/12", "9/13"),
            ScheduleViewUtil.weekDateLabels(termStart, 2)
        )
        assertEquals("9月", ScheduleViewUtil.monthLabel(termStart, 2))
    }
}
