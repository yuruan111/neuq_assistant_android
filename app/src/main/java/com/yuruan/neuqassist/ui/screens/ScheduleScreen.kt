package com.yuruan.neuqassist.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuruan.neuqassist.data.CourseItem
import com.yuruan.neuqassist.data.TimeSlot
import com.yuruan.neuqassist.data.filterCoursesByWeek
import com.yuruan.neuqassist.data.ScheduleViewUtil
import com.yuruan.neuqassist.ui.theme.CoursePalette
import com.yuruan.neuqassist.ui.theme.Dimens
import com.yuruan.neuqassist.ui.theme.SurfaceGrey
import com.yuruan.neuqassist.ui.theme.SurfaceLight
import com.yuruan.neuqassist.ui.theme.TextPrimary
import com.yuruan.neuqassist.ui.theme.TextSecondary
import com.yuruan.neuqassist.ui.theme.TextTertiary

/**
 * 课表查询页 —— 移植自 HarmonyOS 端 views/ScheduleView.ets
 *
 * 右上角「⋯」入口 → 调整面板（对应原端 AdjustPanel 半模态）：
 *   五宫格：导入课表 / 上课时间 / 学期起点 / 课程管理 / 常见问题
 *
 * 版权说明：周网格布局参考开源项目 时光课程表 shiguangschedule（Apache-2.0）。
 */
@Composable
fun ScheduleScreen(
    currentWeek: Int,
    realWeek: Int,
    headDateText: String,
    termStartDateKey: String,
    timeSlots: List<TimeSlot>,
    courses: List<CourseItem>,
    sideDateLabels: List<String>,
    monthLabel: String,
    todayIndex: Int,
    restFlags: List<Boolean>,
    workFlags: List<Boolean>,
    /** 课表网格线是否加重（由「小秦帮 → 个性化设置」控制，持久化） */
    lineWeightSolid: Boolean,
    /** 课表文字大小档位键（个性化设置实时联动） */
    fontScaleKey: String,
    /** 课表卡片圆角（dp，2-16，个性化设置实时联动） */
    cardRadiusDp: Int,
    /** 课表节次行高档位键（个性化设置实时联动） */
    rowHeightKey: String,
    /** 周末无课时自动隐藏周六/周日列 */
    autoHideWeekend: Boolean,
    /** 隐藏课程卡片教师姓名 */
    hideTeacher: Boolean,
    /** 切周触感反馈开关由调用方注入；此处仅回调 */
    onSwitchWeek: (Int) -> Unit,
    onOpenAdjust: () -> Unit,
    onCourseClick: (CourseItem) -> Unit,
    onQuickJumpEmpty: () -> Unit,
    modifier: Modifier = Modifier
) {
    var teacherOnlyMode by remember { mutableStateOf(false) }
    LaunchedEffect(hideTeacher) {
        if (!hideTeacher) teacherOnlyMode = false
    }

    // 根布局透明：全局背景（自定义/内置图 + 保护层）由 MainActivity 统一渲染后透出，
    // 对应 hap ScheduleView 的半透明保护层沉浸形态
    Column(
        modifier = modifier
            .fillMaxSize()
    ) {
        // ===== WakeUp 风格头部：大日期 + 周次切换 + 右上角调整入口 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = headDateText,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "第${currentWeek}周",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    if (hideTeacher) {
                        val primary = MaterialTheme.colorScheme.primary
                        Surface(
                            onClick = { teacherOnlyMode = !teacherOnlyMode },
                            modifier = Modifier.height(30.dp),
                            color = if (teacherOnlyMode) primary else Color.White.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(15.dp),
                            border = if (teacherOnlyMode) {
                                null
                            } else {
                                androidx.compose.foundation.BorderStroke(1.dp, primary)
                            }
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (teacherOnlyMode) "看课程" else "看教师",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (teacherOnlyMode) Color.White else primary,
                                    maxLines = 1
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    CircleIconBtn(
                        iconRes = com.yuruan.neuqassist.R.drawable.ic_week_prev,
                        desc = "上一周"
                    ) { onSwitchWeek(-1) }
                    Spacer(modifier = Modifier.width(6.dp))
                    CircleIconBtn(
                        iconRes = com.yuruan.neuqassist.R.drawable.ic_week_next,
                        desc = "下一周"
                    ) { onSwitchWeek(1) }
                    Spacer(modifier = Modifier.width(6.dp))
                    CircleBtn("⋯") { onOpenAdjust() }
                }
            }
        }

        // ===== 周网格（吸顶表头 + 可滚动网格区） =====
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val hintText = if (currentWeek == realWeek) {
                "提示：查看其他周时，双击课表空白处回到本周"
            } else {
                "第${currentWeek}周（非本周）· 点击「回到本周」或双击空白处返回"
            }
            CourseGridView(
                courses = filterCoursesByWeek(courses, currentWeek),
                timeSlots = timeSlots,
                sideDateLabels = sideDateLabels,
                monthLabel = monthLabel,
                todayIndex = todayIndex,
                restFlags = restFlags,
                workFlags = workFlags,
                lineWeightSolid = lineWeightSolid,
                fontScaleKey = fontScaleKey,
                cardRadiusDp = cardRadiusDp,
                rowHeightKey = rowHeightKey,
                autoHideWeekend = autoHideWeekend,
                hideTeacher = hideTeacher,
                teacherOnlyMode = teacherOnlyMode,
                bottomHintText = hintText,
                onCourseClick = onCourseClick,
                onBlankDoubleTap = {
                    if (currentWeek != realWeek) onQuickJumpEmpty()
                },
                modifier = Modifier.fillMaxSize()
            )

            if (courses.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        color = Color(0xE6FFFFFF),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "当前暂无课程",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0x80182431)
                            )
                            Text(
                                text = "点击右上角「⋯」导入或手动添加课程",
                                fontSize = 11.sp,
                                color = Color(0x66182431)
                            )
                        }
                    }
                }
            }

            // 非本周时悬浮「回到本周」按钮（对应原端双击空白处回到本周）
            if (currentWeek != realWeek) {
                Surface(
                    onClick = onQuickJumpEmpty,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        text = "回到本周",
                        fontSize = 13.sp,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/** 圆形小按钮（对应原端 ButtonType.Circle） */
/** 圆形图标按钮（周切换等，图标与鸿蒙端 ic_week_* 线性箭头一致） */
@Composable
private fun CircleIconBtn(iconRes: Int, desc: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        color = Color(0x33FFFFFF),
        shape = CircleShape
    ) {
        Box(contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(iconRes),
                contentDescription = desc,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun CircleBtn(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        color = Color(0x33FFFFFF),
        shape = CircleShape
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 课程预览半模态 —— 移植自 HarmonyOS 端 ScheduleView.ets 的 CoursePreview
 */
@Composable
fun CoursePreviewSheet(
    course: CourseItem,
    currentWeek: Int,
    slotTimeText: (Int, Int) -> String,
    onEdit: () -> Unit,
    onDismiss: () -> Unit
) {
    val place = com.yuruan.neuqassist.data.splitCoursePlace(course.position)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        CoursePalette[course.colorIndex % CoursePalette.size],
                        CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = course.name,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
        }
        PreviewRow("时间", "周${dayChar(course.day)} · ${slotTimeText(course.startSection, course.endSection)}")
        PreviewRow("教师", course.teacher)
        PreviewRow(
            "地点",
            if (place.building.isNotEmpty() || place.room.isNotEmpty()) {
                "${place.building}${if (place.room.isNotEmpty()) " ${place.room}" else ""}".trim()
            } else ""
        )
        PreviewRow("周次", weeksText(course.weeks, currentWeek))
        PreviewRow("备注", course.remark)

        Spacer(modifier = Modifier.height(4.dp))
        Surface(
            onClick = onEdit,
            modifier = Modifier.fillMaxWidth().height(42.dp),
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(21.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("✏️ 编辑该课程", fontSize = 14.sp, color = Color.White)
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun PreviewRow(label: String, value: String) {
    if (value.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceGrey, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Text(label, fontSize = 12.sp, color = TextTertiary, modifier = Modifier.width(52.dp))
        Text(
            text = value,
            fontSize = 13.sp,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun dayChar(day: Int): String =
    if (day in 1..7) listOf("一", "二", "三", "四", "五", "六", "日")[day - 1] else "?"

/** 生成周次文本摘要（识别全学期/单周/双周/单次/连续区间） */
fun weeksText(weeks: List<Int>, currentWeek: Int = -1): String {
    if (weeks.isEmpty()) return "未选周次"
    if (weeks.size == 20) return "全学期"
    if (weeks.size == 1) return "第${weeks[0]}周 · 单次"
    val isOdd = weeks.all { it % 2 == 1 }
    val isEven = weeks.all { it % 2 == 0 }
    val contig = weeks.indices.all { it == 0 || weeks[it] == weeks[it - 1] + 1 }
    if (isOdd) return "单周"
    if (isEven) return "双周"
    if (contig) return "第${weeks.first()}-${weeks.last()}周"
    val s = weeks.take(6).joinToString(",")
    return "第${s}${if (weeks.size > 6) "…" else ""}周"
}

/** 课程管理页（对应原端 CoursesManager） */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CoursesManagerScreen(
    courses: List<CourseItem>,
    editingCourse: CourseItem?,
    editMode: String,
    formName: String,
    formTeacher: String,
    formPosition: String,
    formDay: Int,
    formStart: Int,
    formEnd: Int,
    formColor: Int,
    formWeeks: List<Int>,
    formSingleMode: Boolean = false,
    slotCount: Int,
    onFormName: (String) -> Unit,
    onFormTeacher: (String) -> Unit,
    onFormPosition: (String) -> Unit,
    onFormDay: (Int) -> Unit,
    onFormStart: (Int) -> Unit,
    onFormEnd: (Int) -> Unit,
    onFormColor: (Int) -> Unit,
    onToggleWeek: (Int) -> Unit,
    onApplyWeekPreset: (String) -> Unit,
    onStartAdd: () -> Unit,
    onStartEdit: (Int) -> Unit,
    onDelete: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF2F3F5))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {}
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "←",
                    fontSize = 18.sp,
                    color = TextPrimary,
                    modifier = Modifier.clickable { onBack() }.padding(4.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (editMode) {
                        "" -> "课程管理"
                        "add" -> "添加课程"
                        else -> "编辑课程"
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
            }
        }

        if (editMode == "") {
            // ===== 课程列表 =====
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (courses.isEmpty()) {
                    Text(
                        "暂无课程，点击下方按钮添加",
                        fontSize = 13.sp,
                        color = TextTertiary,
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        textAlign = TextAlign.Center
                    )
                }
                courses.forEachIndexed { idx, c ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.White,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(c.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                                Text(
                                    text = "${if (c.teacher.isNotEmpty()) c.teacher + " · " else ""}${if (c.position.isNotEmpty()) c.position else "未设置"}",
                                    fontSize = 11.sp,
                                    color = TextTertiary
                                )
                                Text(
                                    text = "周${dayChar(c.day)} · 第${c.startSection}-${c.endSection}节",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                                Text(
                                    text = weeksText(c.weeks),
                                    fontSize = 10.sp,
                                    color = if (c.weeks.size == 1) Color(0xFFB25E00) else TextTertiary
                                )
                            }
                            Text("✏️", fontSize = 16.sp, modifier = Modifier.clickable { onStartEdit(idx) }.padding(8.dp))
                            Text("🗑", fontSize = 16.sp, modifier = Modifier.clickable { onDelete(c.id) }.padding(8.dp))
                        }
                    }
                }
            }
            Surface(
                onClick = onStartAdd,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .height(44.dp),
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(22.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("+ 添加课程", fontSize = 14.sp, color = Color.White)
                }
            }
        } else {
            // ===== 添加/编辑表单 =====
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FormLabel("课程名称")
                com.yuruan.neuqassist.ui.AppTextField(formName, onFormName, "如：高等数学")
                FormLabel("教师")
                com.yuruan.neuqassist.ui.AppTextField(formTeacher, onFormTeacher, "选填")
                FormLabel("上课地点")
                com.yuruan.neuqassist.ui.AppTextField(formPosition, onFormPosition, "如：科技楼S402")

                FormLabel("星期")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { d, ch ->
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    if (formDay == d + 1) MaterialTheme.colorScheme.primary else SurfaceLight,
                                    CircleShape
                                )
                                .clickable { onFormDay(d + 1) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                ch,
                                fontSize = 13.sp,
                                color = if (formDay == d + 1) Color.White else TextPrimary
                            )
                        }
                    }
                }

                FormLabel("节次：第 $formStart - $formEnd 节")
                val baseMax = if (slotCount > 0) slotCount else 12
                val startUpper = maxOf(baseMax, formStart, 1)
                val endUpper = maxOf(baseMax, formEnd, formStart)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        "开始",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (s in 1..startUpper) {
                            SectionChip("$s", formStart == s) { onFormStart(s) }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        "结束",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (e in formStart..endUpper) {
                            SectionChip("$e", formEnd == e) { onFormEnd(e) }
                        }
                    }
                }

                FormLabel("周次")
                val presetKey = weekPresetKey(formWeeks, formSingleMode)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "all" to "全学期", "odd" to "单周", "even" to "双周",
                        "first" to "前半", "last" to "后半", "single" to "单次"
                    ).forEach { (k, label) ->
                        SectionChip(label, presetKey == k) { onApplyWeekPreset(k) }
                    }
                }
                // 周次数字（FlowRow）
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (w in 1..20) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(
                                    if (w in formWeeks) MaterialTheme.colorScheme.primary else SurfaceLight,
                                    CircleShape
                                )
                                .clickable { onToggleWeek(w) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "$w",
                                fontSize = 11.sp,
                                color = if (w in formWeeks) Color.White else TextPrimary
                            )
                        }
                    }
                }
                if (presetKey == "single") {
                    Text(
                        "单次课仅在该周显示，可用于考试、补课、活动提醒",
                        fontSize = 10.sp,
                        color = Color(0xFFB25E00)
                    )
                }
                Text("已选：${weeksText(formWeeks)}", fontSize = 11.sp, color = TextSecondary)

                FormLabel("颜色")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoursePalette.forEachIndexed { ci, c ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(c, CircleShape)
                                .then(
                                    if (formColor == ci) {
                                        Modifier.border(2.dp, TextPrimary, CircleShape)
                                    } else Modifier
                                )
                                .clickable { onFormColor(ci) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            Surface(
                onClick = onSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .height(44.dp),
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(22.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("保存", fontSize = 15.sp, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun FormLabel(text: String) {
    Text(text, fontSize = 12.sp, color = TextTertiary, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun SectionChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.primary else SurfaceLight,
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = if (selected) Color.White else TextPrimary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

/** 当前表单命中的预设键 */
fun weekPresetKey(formWeeks: List<Int>, singleMode: Boolean = false): String = when {
    formWeeks == ScheduleViewUtil.allWeeks() -> "all"
    formWeeks == ScheduleViewUtil.oddWeeks() -> "odd"
    formWeeks == ScheduleViewUtil.evenWeeks() -> "even"
    formWeeks == ScheduleViewUtil.firstHalfWeeks() -> "first"
    formWeeks == ScheduleViewUtil.lastHalfWeeks() -> "last"
    singleMode -> "single"
    else -> "custom"
}
