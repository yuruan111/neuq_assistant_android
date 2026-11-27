package com.yuruan.neuqassist.ui

import com.yuruan.neuqassist.data.CourseItem
import com.yuruan.neuqassist.data.Personalize
import com.yuruan.neuqassist.ui.screens.formatCompactPlace
import com.yuruan.neuqassist.ui.screens.resolveVisibleDayCount
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
}
