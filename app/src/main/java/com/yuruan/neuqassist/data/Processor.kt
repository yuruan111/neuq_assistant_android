package com.yuruan.neuqassist.data

/**
 * 数据处理器 —— 移植自 HarmonyOS 端 service/Processor.ets（Rust src/processor.rs）
 *
 * 应用筛选与名称清洗规则（Rust 端 Rules 1-13），语义保持一致：
 * 1/2. 教学楼与设备配置均为空，或设备配置为空而教学楼非空 → 舍弃
 * 3.   容量为 "0" → 舍弃
 * 5.   设备配置为禁止类型 → 舍弃
 * 6.   教学楼为禁止建筑 → 舍弃
 * 7-12. 各教学楼名称清洗（提取数字部分、科技楼自主学习室转换）
 * 13.  黑名单过滤
 */

/** 数字名称模式：^\d+[A-Z]?$ 或 ^\d+[A-Z]?-[A-Z\d-]+$ */
private val NUMBER_PATTERN = Regex("^\\d+[A-Z]?$|^\\d+[A-Z]?-[A-Z\\d-]+$")

/** 自主学习室模式：^自主学习室([A-Z])科技楼(.+)$ */
private val ZIZHU_PATTERN = Regex("^自主学习室([A-Z])科技楼(.+)$")

object Processor {

    /**
     * 应用所有筛选和转换规则 —— 对应 Rust apply_rules_parallel
     * （App 端数据量为单时段数百条，无需并行化）
     */
    fun applyRules(data: List<RawClassroomData>): List<ProcessedClassroomData> {
        val result = mutableListOf<ProcessedClassroomData>()
        for (item in data) {
            // Rule 1: 教学楼与设备配置均为空，舍弃
            if (item.building.isEmpty() && item.equipmentConfig.isEmpty()) continue
            // Rule 2: 设备配置为空但教学楼非空，舍弃
            if (item.equipmentConfig.isEmpty() && item.building.isNotEmpty()) continue
            // Rule 3: 容量为 "0"，舍弃
            if (item.capacity == "0") continue
            // Rule 5: 设备配置为禁止类型
            if (item.equipmentConfig in AppConfig.FORBIDDEN_CONFIGS) continue
            // Rule 6: 教学楼为禁止建筑
            if (item.building in AppConfig.FORBIDDEN_BUILDINGS) continue
            // Rules 7-12: 名称清洗
            val cleanedName = cleanName(item.building, item.name) ?: continue
            // Rule 13: 黑名单过滤
            val blacklisted = AppConfig.BLACKLIST[item.building]
            if (blacklisted != null && cleanedName in blacklisted) continue

            val processed = ProcessedClassroomData(
                building = item.building,
                name = cleanedName,
                capacity = item.capacity,
                equipmentConfig = item.equipmentConfig,
                timeSlot = item.timeSlot
            )

            if (!validateProcessedData(processed)) continue
            result.add(processed)
        }
        return result
    }

    /** 清洗教室名称（Rules 7-12），返回 null 表示舍弃 */
    fun cleanName(building: String, name: String): String? = when (building) {
        "工学馆" -> extractNumberPart(name, "工学馆")
        "管理楼" -> extractNumberPart(name, "管理楼")
        "基础楼" -> extractNumberPart(name, "基础楼")
        "人文楼" -> extractNumberPart(name, "人文楼")
        "综合实验楼" -> extractNumberPart(name, "综合楼")
        "科技楼" -> cleanKejiBuilding(name)
        else -> name
    }

    /** 提取数字部分：名称以教学楼前缀开头且剩余部分匹配数字模式时返回剩余部分 */
    private fun extractNumberPart(name: String, prefix: String): String {
        if (name.startsWith(prefix)) {
            val rest = name.substring(prefix.length)
            if (NUMBER_PATTERN.matches(rest)) return rest
        }
        // 不匹配前缀时返回原名称
        return name
    }

    /** 清洗科技楼名称，不符合规则返回 null（舍弃） */
    private fun cleanKejiBuilding(name: String): String? {
        if (name.startsWith("科技楼")) {
            val rest = name.substring("科技楼".length)
            if (NUMBER_PATTERN.matches(rest)) return rest
        }
        // 自主学习室格式："自主学习室Q科技楼6026-A" → "6026-A自习室Q"
        val m = ZIZHU_PATTERN.find(name)
        if (m != null) return "${m.groupValues[2]}自习室${m.groupValues[1]}"
        return null
    }
}
