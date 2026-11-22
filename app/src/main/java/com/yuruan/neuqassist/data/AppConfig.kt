package com.yuruan.neuqassist.data

/**
 * 应用配置与静态常量 —— 移植自 HarmonyOS 端 config/AppConfig.ets（Rust src/config.rs）
 *
 * 教学楼、时段、禁止类型、黑名单等常量与 Rust 端完全一致；
 * 黑名单镜像自 assets/blacklist/blacklist.json（应用内嵌，避免外部文件依赖）。
 */
object AppConfig {

    data class TimeSlot(
        val begin: Int,
        val end: Int,
        val fileSuffix: String,
        val label: String
    )

    /** 预定义的查询时段（与 Rust TIME_SLOTS 一致，10 个） */
    val TIME_SLOTS: List<TimeSlot> = listOf(
        TimeSlot(1, 2, "1-2", "第1-2节"),
        TimeSlot(3, 4, "3-4", "第3-4节"),
        TimeSlot(5, 6, "5-6", "第5-6节"),
        TimeSlot(7, 8, "7-8", "第7-8节"),
        TimeSlot(9, 10, "9-10", "第9-10节"),
        TimeSlot(11, 12, "11-12", "第11-12节"),
        TimeSlot(1, 8, "1-8", "昼间第1-8节"),
        TimeSlot(1, 4, "am", "上午空闲"),
        TimeSlot(5, 8, "pm", "下午空闲"),
        TimeSlot(9, 12, "night", "夜间空闲")
    )

    /** 顺序时段（用于计算全天空闲，与 Rust SEQUENTIAL_SLOTS 一致） */
    val SEQUENTIAL_SLOTS: List<String> = listOf("1-2", "3-4", "5-6", "7-8", "9-10", "11-12")

    data class BuildingConfig(
        val name: String,
        val code: String,
        val hasFloors: Boolean,
        /** 校区：'本部' / '南校区'（科技楼、人文楼在南校区，其余在本部） */
        val campus: String
    )

    /** 预定义的教学楼（与 Rust BUILDINGS 一致，7 个；campus 为本 App 补充的参考数据） */
    val BUILDINGS: List<BuildingConfig> = listOf(
        BuildingConfig("工学馆", "GXG", true, "本部"),
        BuildingConfig("基础楼", "JCL", false, "本部"),
        BuildingConfig("综合实验楼", "ZHSYL", false, "本部"),
        BuildingConfig("地质楼", "DZL", false, "本部"),
        BuildingConfig("管理楼", "GLL", false, "本部"),
        BuildingConfig("科技楼", "KJL", false, "南校区"),
        BuildingConfig("人文楼", "RWL", false, "南校区")
    )

    /** 需要做「楼层分组」的教学楼（这三栋空教室数量多；其余楼房间少，平铺即可） */
    val FLOOR_GROUPED_BUILDINGS: List<String> = listOf("工学馆", "科技楼", "人文楼")

    /** 按教学楼中文名查校区；未知（不在 BUILDINGS 内）返回空串 */
    fun campusOfBuilding(name: String): String =
        BUILDINGS.firstOrNull { it.name == name }?.campus ?: ""

    /** 禁止的教室设备配置类型 */
    val FORBIDDEN_CONFIGS: List<String> = listOf(
        "体育教学场地", "机房", "实验室", "活动教室", "研讨室",
        "多功能", "智慧教室", "不排课教室", "语音室"
    )

    /** 禁止的教学楼 */
    val FORBIDDEN_BUILDINGS: List<String> = listOf("大学会馆", "旧实验楼")

    /**
     * 黑名单（教学楼 → 教室名列表），镜像 assets/blacklist/blacklist.json。
     * 后续如需更新，直接修改此常量即可。
     */
    val BLACKLIST: Map<String, List<String>> = mapOf(
        "工学馆" to listOf("410", "417"),
        "基础楼" to emptyList(),
        "综合实验楼" to emptyList(),
        "地质楼" to emptyList(),
        "管理楼" to emptyList(),
        "科技楼" to emptyList(),
        "人文楼" to emptyList()
    )

    /** 默认教务系统地址 */
    const val DEFAULT_BASE_URL: String = "http://jwxt.neuq.edu.cn/eams/"

    /** 默认 WebVPN 地址 */
    const val DEFAULT_WEBVPN_BASE_URL: String = "https://vpn.neuq.edu.cn"

    /** 查询总天数（日期选择范围 + 一键更新的抓取窗口，均为 5 天） */
    const val TOTAL_DAYS: Int = 5

    // ===== 「最接近当前时刻的时段」推算 =====

    /**
     * 各「查询时段」对应的上课分钟区间。
     * 键与 TIME_SLOTS 的 fileSuffix 一致；'1-8' 覆盖上午到下午（11:40 结束），
     * '11-12' 为晚间末段（22:00 结束）。
     */
    private val SLOT_MINUTE_RANGE: Map<String, IntArray> = mapOf(
        "1-2" to intArrayOf(8 * 60, 9 * 60 + 35),
        "3-4" to intArrayOf(10 * 60 + 5, 11 * 60 + 40),
        "5-6" to intArrayOf(14 * 60, 15 * 60 + 35),
        "7-8" to intArrayOf(16 * 60 + 5, 17 * 60 + 40),
        "1-8" to intArrayOf(8 * 60, 17 * 60 + 40),
        "9-10" to intArrayOf(18 * 60 + 40, 20 * 60 + 15),
        "11-12" to intArrayOf(20 * 60 + 25, 22 * 60)
    )

    /**
     * 按当前时刻推算「最接近的查询时段」在 TIME_SLOTS 中的下标。
     * 规则：
     *  - 落在某时段区间内 → 返回该时段；
     *  - 早于第一个时段 → 返回第一个（上午第1-2节）；
     *  - 晚于最后一个时段 → 返回最后一个（晚上第11-12节）；
     *  - 处于两时段之间的课间 → 返回即将开始的那一个（即"下一节"）。
     *
     * 注意：`1-8`（昼间第1-8节）跨越上午到下午，是"整天连看"的粗粒度选项，
     * 它的区间会覆盖多个细分时段的课间。若按普通时段参与比较，课间时它会以
     * 距离 0 抢走结果（如 09:40 返回 1-8 而非即将开始的 3-4），故把它按
     * 「起止点距离」处理：只在它自身区间内且落在两节课之间且没有更合适的细分
     * 时段时才可能胜出——实现上是给它加一个惩罚量，让细分时段优先。
     */
    private const val UMBRELLA_SLOT_SUFFIX: String = "1-8"

    /** 粗粒度时段在"未命中自身边界"时的距离惩罚（分钟），确保细分时段优先 */
    private const val UMBRELLA_PENALTY: Int = 100000

    fun nearestTimeSlotIndex(nowMinutes: Int = -1): Int {
        val now = if (nowMinutes < 0) {
            val c = java.util.Calendar.getInstance()
            c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
        } else nowMinutes

        var bestIdx = 0
        var bestDist = -1
        for (i in TIME_SLOTS.indices) {
            val suffix = TIME_SLOTS[i].fileSuffix
            val range = SLOT_MINUTE_RANGE[suffix] ?: continue
            val begin = range[0]
            val end = range[1]
            val isUmbrella = suffix == UMBRELLA_SLOT_SUFFIX
            val dist: Int = when {
                now < begin -> begin - now
                now > end -> now - end
                isUmbrella -> UMBRELLA_PENALTY
                else -> 0
            }
            if (bestDist < 0 || dist < bestDist) {
                bestDist = dist
                bestIdx = i
            }
        }
        return bestIdx
    }

    /** 当前是「第几天」（0=今天 ... TOTAL_DAYS-1）；恒为 0，抽出以便日后调整窗口语义 */
    fun nearestDayOffset(): Int = 0
}
