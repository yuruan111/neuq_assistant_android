package com.yuruan.neuqassist.data

/**
 * 数据模型 —— 移植自 HarmonyOS 端 model/ClassroomModels.ets（Rust src/models.rs）
 *
 * RawClassroomData / ProcessedClassroomData 与 Rust 端 serde 结构一一对应；
 * 校验逻辑保留原语义（容量必须为数字、时段格式白名单等）。
 */

/** 原始教室数据（从教务系统 HTML 解析获得） */
data class RawClassroomData(
    val building: String = "",
    val name: String = "",
    val capacity: String = "",
    val equipmentConfig: String = "",
    /** 空闲时段（如 "1-2"），处理时添加 */
    val timeSlot: String = ""
) {
    fun copyWithSlot(timeSlot: String) = copy(timeSlot = timeSlot)
}

/** 处理后的教室数据（已过滤、已清洗名称） */
data class ProcessedClassroomData(
    val building: String = "",
    val name: String = "",
    val capacity: String = "",
    val equipmentConfig: String = "",
    val timeSlot: String = ""
)

/** 校验时段格式是否有效（与 Rust is_valid_time_slot 一致） */
fun isValidTimeSlot(slot: String): Boolean =
    slot == "1-2" || slot == "3-4" || slot == "5-6" || slot == "7-8" ||
        slot == "1-8" || slot == "9-10" || slot == "11-12" || slot == "1-12"

/** 校验原始数据（对应 Rust Validate for RawClassroomData 的容量检查） */
fun validateRawData(item: RawClassroomData) {
    if (item.capacity.isEmpty()) throw AppError.validation("容量字段不能为空")
    if (!item.capacity.matches(Regex("^\\d+$"))) {
        throw AppError.validation("容量 '${item.capacity}' 不是有效的数字")
    }
}

/** 校验处理后的数据（对应 Rust Validate for ProcessedClassroomData） */
fun validateProcessedData(item: ProcessedClassroomData): Boolean {
    if (item.building.isEmpty() || item.name.isEmpty() || item.timeSlot.isEmpty()) return false
    return isValidTimeSlot(item.timeSlot)
}

/**
 * 按时段和教学楼聚合的数据结构 —— 对应 Rust TimeSlotBuildingData
 *
 * data: 时段 -> 教学楼 -> 教室名称集合
 */
class TimeSlotBuildingData {
    private val data = HashMap<String, HashMap<String, MutableSet<String>>>()

    fun add(timeSlot: String, building: String, roomName: String) {
        val byBuilding = data.getOrPut(timeSlot) { HashMap() }
        byBuilding.getOrPut(building) { LinkedHashSet() }.add(roomName)
    }

    fun getRooms(timeSlot: String, building: String): Set<String> =
        data[timeSlot]?.get(building) ?: emptySet()

    /**
     * 计算全天空闲教室：所有顺序时段都空闲（取交集），对应 Rust
     * get_all_day_free_rooms
     */
    fun getAllDayFreeRooms(building: String): Set<String> {
        var commonRooms: Set<String>? = null
        for (slot in AppConfig.SEQUENTIAL_SLOTS) {
            val current = getRooms(slot, building)
            if (commonRooms == null) {
                commonRooms = current
            } else {
                val intersection = commonRooms.intersect(current)
                if (intersection.isEmpty()) return emptySet()
                commonRooms = intersection
            }
        }
        return commonRooms ?: emptySet()
    }
}
