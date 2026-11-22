package com.yuruan.neuqassist.data

/**
 * 课表数据模型与布局算法 —— 移植自 HarmonyOS 端 views/CourseModels.ets
 *
 * 版权说明：数据结构与子列布局算法参考开源项目 时光课程表 shiguangschedule
 *   https://github.com/XingHeYuZhuan/shiguangschedule （Apache-2.0 License）
 * 本文件为其 Kotlin 移植改写版，遵循 Apache-2.0 协议保留原项目版权声明。
 *
 * 对应原文件：
 *   data/db/main/Course.kt       → CourseItem（课程实体）
 *   data/db/main/CourseWeek.kt   → CourseItem.weeks（课程-周多对多关联）
 *   ui/schedule/components/ScheduleGrid.kt 中的
 *   calculateSingleSchedulables  → layoutCourseBlocks（同天重叠课程分副列）
 */

/** 节次时间 */
data class TimeSlot(
    val startTime: String, // "08:00"
    val endTime: String    // "09:40"
)

/** 课程实体（对应原 Course.kt，节次模式；自定义时间模式暂不启用） */
data class CourseItem(
    val id: String,             // 唯一标识（UUID）
    val name: String,           // 课程名，如 "高等数学"
    val teacher: String,        // 授课教师
    val position: String,       // 上课地点，如 "主楼A101"
    val day: Int,               // 星期几：1=周一 ... 7=周日
    val startSection: Int,      // 开始节次（从 1 起）
    val endSection: Int,        // 结束节次（含）
    val colorIndex: Int,        // 课程卡颜色索引（对应 PALETTE 下标）
    val weeks: List<Int>,       // 上课周数列表（原 CourseWeek 表的行内化）
    val remark: String = ""     // 备注（可空语义，用空串表示）
)

/** 上课地点拆分结果：教学楼 + 教室（课表卡片分两行显示，避免长地点被截断） */
data class CoursePlace(
    val building: String, // 教学楼名（如 "科技楼"；仅教室号时为空串）
    val room: String      // 教室号（如 "S402"；无教室号时为空串）
)

/**
 * 东秦常见教学楼名（长词在前，避免"综合实验楼"被"实验楼"抢先匹配）。
 * 用于把教务地点串（如 "科技楼S402"）拆成「教学楼 + 教室」两行显示。
 */
private val NEUQ_BUILDINGS = listOf(
    "综合实验楼", "大学会馆", "旧实验楼", "工学馆", "地质楼", "管理楼", "人文楼", "科技楼",
    "基础楼", "理学楼", "实验楼", "图书馆", "体育馆", "主楼"
)

/**
 * 拆分上课地点：优先匹配已知教学楼名，其次按"中文前缀 + 后续编号"切分，
 * 纯中文（如"操场"）整体视为教学楼，纯编号（如"A201"）整体视为教室。
 */
fun splitCoursePlace(position: String): CoursePlace {
    val p = position.trim()
    if (p.isEmpty()) return CoursePlace("", "")
    for (b in NEUQ_BUILDINGS) {
        if (p.startsWith(b)) return CoursePlace(b, p.substring(b.length).trim())
    }
    var end = 0
    while (end < p.length && p[end].code in 0x4E00..0x9FA5) end++
    if (end == 0) return CoursePlace("", p)          // 纯编号：无楼名
    if (end == p.length) return CoursePlace(p, "")   // 纯中文：无教室号
    return CoursePlace(p.substring(0, end), p.substring(end).trim())
}

/** 网格定位结果：一门课程在周网格中的摆放信息 */
data class CourseBlockLayout(
    val course: CourseItem,
    val columnIndex: Int,     // 天索引（0=周一 ... 6=周日）
    val subColumnIndex: Int,  // 同天重叠时的副列序号
    val subColumnCount: Int,  // 该天副列总数（含单课的天为 1）
    val span: Int             // 跨越的节次列数（= endSection - startSection + 1）
)

/** 过滤出第 week 周有课的课程（对应原 mergedCourses 的周过滤环节） */
fun filterCoursesByWeek(courses: List<CourseItem>, week: Int): List<CourseItem> =
    courses.filter { week in it.weeks }

/**
 * 按天分组 + 重叠簇内贪心区间图着色，为冲突课程分配副列并排展示。
 * 簇 = 当天时间上传递相交的课程组：只有真正冲突的课程共享列宽
 * （副列数 = 簇内最大同时重叠数），无冲突的课程保持整列宽——
 * 此前的「天级副列数」会让全天的课被一处冲突误伤压窄，
 * 现对齐原版 shiguangschedule calculateSingleSchedulables 的成簇逻辑。
 */
fun layoutCourseBlocks(courses: List<CourseItem>): List<CourseBlockLayout> {
    val layouts = mutableListOf<CourseBlockLayout>()
    for (day in 1..7) {
        val dayCourses = courses.filter { it.day == day }
            .sortedBy { it.startSection }
            .toMutableList()

        // 1) 成簇：下一课的起始节次晚于当前簇最大结束节次 → 开新簇（共享同一节次才算重叠）
        val clusters = mutableListOf<List<CourseItem>>()
        var cur = mutableListOf<CourseItem>()
        var maxEnd = 0
        for (c in dayCourses) {
            if (cur.isNotEmpty() && c.startSection > maxEnd) {
                clusters.add(cur)
                cur = mutableListOf()
            }
            cur.add(c)
            maxEnd = maxOf(maxEnd, c.endSection)
        }
        if (cur.isNotEmpty()) clusters.add(cur)

        // 2) 簇内贪心列分配：每个副列记录其当前占据的结束节次；新课程放入第一个空闲列
        for (cluster in clusters) {
            val colEnds = mutableListOf<Int>()
            val assignedCols = mutableListOf<Int>()
            for (c in cluster) {
                var col = -1
                for (i in colEnds.indices) {
                    if (colEnds[i] < c.startSection) {
                        col = i
                        break
                    }
                }
                if (col == -1) {
                    colEnds.add(c.endSection)
                    col = colEnds.size - 1
                } else {
                    colEnds[col] = c.endSection
                }
                assignedCols.add(col)
            }

            // 3) 副列数 = 簇内列数，仅簇内课程共享该宽度
            for (k in cluster.indices) {
                val c = cluster[k]
                layouts.add(
                    CourseBlockLayout(
                        course = c,
                        columnIndex = day - 1,
                        subColumnIndex = assignedCols[k],
                        subColumnCount = colEnds.size,
                        // 连上课（如 1-2 节）跨越多个节次列，渲染时据此跨列
                        span = c.endSection - c.startSection + 1
                    )
                )
            }
        }
    }
    return layouts
}

/** 东秦 12 节作息（来自教务课表页配套定义） */
val NEUQ_TIME_SLOTS: List<TimeSlot> = listOf(
    TimeSlot("08:00", "08:45"),
    TimeSlot("08:50", "09:35"),
    TimeSlot("10:05", "10:50"),
    TimeSlot("10:55", "11:40"),
    TimeSlot("14:00", "14:45"),
    TimeSlot("14:50", "15:35"),
    TimeSlot("16:05", "16:50"),
    TimeSlot("16:55", "17:40"),
    TimeSlot("18:40", "19:25"),
    TimeSlot("19:30", "20:15"),
    TimeSlot("20:25", "21:10"),
    TimeSlot("21:15", "22:00")
)

/** WakeUp 风格饱和配色（白字） */
val COURSE_PALETTE = listOf(
    "#F09A3E", "#EE6E9F", "#3FB39B", "#5B9BE0",
    "#9B7FDF", "#D9A83F", "#68BE7D", "#E57373"
)
