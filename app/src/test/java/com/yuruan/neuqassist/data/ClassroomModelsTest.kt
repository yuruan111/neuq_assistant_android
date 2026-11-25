package com.yuruan.neuqassist.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 节次时段白名单与数据校验 —— 纯逻辑单测。
 *
 * `am`/`pm`/`night` 是空教室查询的聚合时段，云端缓存与设置恢复都按这三个
 * 值回放；白名单漏项会把整栋楼的空闲时段过滤成空结果（P0 回归点）。
 */
class ClassroomModelsTest {

    @Test
    fun aggregateSlotsAreAccepted() {
        assertTrue(isValidTimeSlot("am"))
        assertTrue(isValidTimeSlot("pm"))
        assertTrue(isValidTimeSlot("night"))
    }

    @Test
    fun sequentialAndMergedSlotsKeepWorking() {
        for (slot in listOf("1-2", "3-4", "5-6", "7-8", "1-8", "9-10", "11-12", "1-12")) {
            assertTrue("时段 $slot 应合法", isValidTimeSlot(slot))
        }
    }

    @Test
    fun unknownOrMiscasedSlotsAreRejected() {
        assertFalse(isValidTimeSlot(""))
        assertFalse(isValidTimeSlot("AM"))
        assertFalse(isValidTimeSlot("1-3"))
        assertFalse(isValidTimeSlot("morning"))
    }

    @Test
    fun validateProcessedDataAcceptsAggregateSlots() {
        for (slot in listOf("am", "pm", "night")) {
            val item = ProcessedClassroomData(
                building = "科技楼",
                name = "S402",
                capacity = "60",
                equipmentConfig = "",
                timeSlot = slot
            )
            assertTrue("聚合时段 $slot 应通过校验", validateProcessedData(item))
        }
    }

    @Test
    fun validateProcessedDataRejectsMissingFieldsOrBadSlot() {
        val base = ProcessedClassroomData(
            building = "科技楼",
            name = "S402",
            capacity = "60",
            equipmentConfig = "",
            timeSlot = "am"
        )
        assertFalse(validateProcessedData(base.copy(building = "")))
        assertFalse(validateProcessedData(base.copy(name = "")))
        assertFalse(validateProcessedData(base.copy(timeSlot = "")))
        assertFalse(validateProcessedData(base.copy(timeSlot = "evening")))
    }

    @Test
    fun validateRawDataRequiresNumericCapacity() {
        validateRawData(RawClassroomData(capacity = "60"))

        try {
            validateRawData(RawClassroomData(capacity = ""))
            fail("空容量应抛校验错误")
        } catch (e: AppError) {
            assertEquals(AppError.Kind.Validation, e.kind)
        }

        try {
            validateRawData(RawClassroomData(capacity = "六十"))
            fail("非数字容量应抛校验错误")
        } catch (e: AppError) {
            assertEquals(AppError.Kind.Validation, e.kind)
        }
    }
}
