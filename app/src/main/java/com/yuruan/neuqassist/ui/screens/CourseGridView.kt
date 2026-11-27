package com.yuruan.neuqassist.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuruan.neuqassist.data.CourseBlockLayout
import com.yuruan.neuqassist.data.CourseItem
import com.yuruan.neuqassist.data.Personalize
import com.yuruan.neuqassist.data.TimeSlot
import com.yuruan.neuqassist.data.layoutCourseBlocks
import com.yuruan.neuqassist.data.splitCoursePlace
import com.yuruan.neuqassist.ui.theme.CoursePalette
import com.yuruan.neuqassist.ui.theme.Dimens
import com.yuruan.neuqassist.ui.theme.FrameLine
import com.yuruan.neuqassist.ui.theme.GridLineLight
import com.yuruan.neuqassist.ui.theme.GridLineSolid
import com.yuruan.neuqassist.ui.theme.TextPrimary
import com.yuruan.neuqassist.ui.theme.TextSecondary
import com.yuruan.neuqassist.ui.theme.TextTertiary

/**
 * 周课表网格视图 —— 移植自 HarmonyOS 端 views/CourseGridView.ets
 *
 * 布局（对齐参考实现 neuq-classroom-app / shiguangschedule 的网格方向）：
 *   纵轴 = 节次（12 行，左侧固定节次栏显示序号与起止时间）
 *   横轴 = 星期（7 列，顶部表头显示星期 + 日期 + 休/班徽标）
 *   课程块：x 按天列定位、y 按起始节次定位，高度按跨度（连上的 1-2 节纵向跨行）
 *
 * 保留特性：今日高亮、休/班徽标、副列并排（同日时间重叠）
 * （Android 端不移植智感握姿换边与仿沉浸光感触摸光斑——前者依赖
 *  HarmonyOS DETECT_GESTURE 权限，后者为纯装饰性特效）
 *
 * 版权说明：网格结构参考开源项目 时光课程表 shiguangschedule（Apache-2.0），
 * 视觉风格参照 WakeUp课程表（饱和彩色课程块、白字、今日高亮）。
 */

private val SIDE_W = 34.dp      // 左侧节次栏宽度（收窄以增加每天课程列宽度）
private val HEADER_H = 42.dp    // 顶部星期表头高度（吸顶固定）
private val SLOT_MIN = 44.dp    // 节次行高下限
private val SLOT_MAX = 88.dp    // 节次行高上限

private val DAY_CHARS = listOf("一", "二", "三", "四", "五", "六", "日")

/** 本周周六、周日均无课且开启自动折叠时，仅显示周一~周五（5 列） */
internal fun resolveVisibleDayCount(weekCourses: List<CourseItem>, autoHideWeekend: Boolean): Int {
    if (!autoHideWeekend) return 7
    val hasWeekendCourse = weekCourses.any { it.day == 6 || it.day == 7 }
    return if (hasWeekendCourse) 7 else 5
}

/** 将上课地点压缩为紧凑单串（例如 "@工学馆302" / "@科技楼S402"） */
internal fun formatCompactPlace(position: String): String {
    val place = splitCoursePlace(position)
    val b = place.building.trim()
    val r = place.room.trim()
    return when {
        b.isEmpty() && r.isEmpty() -> ""
        r.isEmpty() -> "@$b"
        b.isEmpty() -> "@$r"
        r.startsWith(b) -> "@$r"
        else -> "@$b$r"
    }
}

@Composable
fun CourseGridView(
    courses: List<CourseItem>,
    timeSlots: List<TimeSlot>,
    sideDateLabels: List<String>,
    monthLabel: String,
    todayIndex: Int,
    restFlags: List<Boolean>,
    workFlags: List<Boolean>,
    lineWeightSolid: Boolean,
    /** 课表文字大小档位键（个性化设置 → 课表显示；App 状态实时联动） */
    fontScaleKey: String,
    /** 课表卡片圆角（dp，2-16；个性化设置 → 课表显示） */
    cardRadiusDp: Int,
    onCourseClick: (CourseItem) -> Unit,
    onBlankDoubleTap: () -> Unit = {},
    modifier: Modifier = Modifier,
    rowHeightKey: String = Personalize.DEFAULT_ROW_HEIGHT,
    autoHideWeekend: Boolean = Personalize.DEFAULT_AUTO_HIDE_WEEKEND,
    hideTeacher: Boolean = Personalize.DEFAULT_HIDE_TEACHER,
    teacherOnlyMode: Boolean = false,
    bottomHintText: String = "",
    availableHeight: Dp = 480.dp
) {
    val slotCount = if (timeSlots.isEmpty()) 12 else timeSlots.size
    val gridLine = if (lineWeightSolid) GridLineSolid else GridLineLight
    val layouts = layoutCourseBlocks(courses)
    val visibleDays = resolveVisibleDayCount(courses, autoHideWeekend)
    // 今日态强调色跟随主题预设（个性化设置 → 主题配色）
    val accent = MaterialTheme.colorScheme.primary
    val currentOnBlankDoubleTap by rememberUpdatedState(onBlankDoubleTap)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val baseSlotH = Personalize.rowHeightDp(rowHeightKey).dp
        val containerH = if (maxHeight.value.isFinite()) maxHeight else availableHeight
        val usable = containerH - HEADER_H
        val fitted = if (usable > 0.dp) usable / slotCount else baseSlotH
        val slotH = maxOf(baseSlotH, fitted).coerceIn(SLOT_MIN, SLOT_MAX)
        val slotHPx = with(LocalDensity.current) { slotH.toPx() }

        Column(modifier = Modifier.fillMaxSize()) {
            // ===== 顶部星期表头（吸顶不随网格滚动） =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HEADER_H)
            ) {
                // 左上角：月份（网格透明衬底由宿主背景层提供，对应 hap 沉浸网格）
                Box(
                    modifier = Modifier
                        .width(SIDE_W)
                        .fillMaxSize()
                        .border(width = 1.dp, color = FrameLine),
                    contentAlignment = Alignment.Center
                ) {
                    Text(monthLabel, fontSize = 10.sp, color = TextSecondary, maxLines = 1)
                }
                // 5/7 天列表头（今日列强调色跟随主题预设）
                for (d in 0 until visibleDays) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .background(
                                if (d == todayIndex) accent.copy(alpha = 0.12f) else Color.Transparent
                            )
                            .border(width = 1.dp, color = FrameLine),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = DAY_CHARS[d],
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (d == todayIndex) accent else TextPrimary
                                )
                                if (d < restFlags.size && restFlags[d]) {
                                    Text(
                                        text = " 休",
                                        fontSize = 9.sp,
                                        color = Color(0xFF34A853)
                                    )
                                }
                                if (d < workFlags.size && workFlags[d]) {
                                    Text(
                                        text = " 班",
                                        fontSize = 9.sp,
                                        color = Color(0xFFEA4335)
                                    )
                                }
                            }
                            Text(
                                text = sideDateLabels.getOrElse(d) { "" },
                                fontSize = 9.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            // ===== 可滚动网格区（节次行 × 可见天列） =====
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    // 左侧节次栏
                    Column(modifier = Modifier.width(SIDE_W)) {
                        for (i in 0 until slotCount) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(slotH)
                                    .border(width = 1.dp, color = gridLine),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "${i + 1}",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextPrimary,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = timeSlots.getOrNull(i)?.startTime ?: "",
                                        fontSize = 7.5.sp,
                                        color = TextSecondary,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }

                    // 5/7 天列（今日列轻微着色，色调随主题预设）
                    for (d in 0 until visibleDays) {
                        val dayLayouts = layouts.filter { it.columnIndex == d }
                        val latestDayLayouts by rememberUpdatedState(dayLayouts)
                        var colWidthPx by remember { mutableIntStateOf(0) }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(slotH * slotCount)
                                .background(
                                    if (d == todayIndex) accent.copy(alpha = 0.05f) else Color.Transparent
                                )
                                .border(width = 1.dp, color = gridLine)
                                .onSizeChanged { colWidthPx = it.width }
                                .pointerInput(slotHPx) {
                                    detectTapGestures(
                                        onDoubleTap = { offset ->
                                            val w = colWidthPx.toFloat()
                                            val hitCourse = w > 0f && latestDayLayouts.any { layout ->
                                                val left = w * (layout.subColumnIndex.toFloat() / layout.subColumnCount)
                                                val right = left + w / layout.subColumnCount
                                                val top = slotHPx * (layout.course.startSection - 1)
                                                val bottom = top + slotHPx * layout.span
                                                offset.x in (left - 4f)..(right + 4f) &&
                                                    offset.y in (top - 4f)..(bottom + 4f)
                                            }
                                            if (!hitCourse) {
                                                currentOnBlankDoubleTap()
                                            }
                                        }
                                    )
                                }
                        ) {
                            // 横向网格线
                            Column(modifier = Modifier.fillMaxSize()) {
                                for (i in 0 until slotCount) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(slotH)
                                            .border(
                                                width = 1.dp,
                                                color = gridLine
                                            )
                                    )
                                }
                            }

                            // 课程块（绝对定位由 BoxWithConstraints + offset 模拟）
                            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                for (layout in dayLayouts) {
                                    val top = slotH * (layout.course.startSection - 1)
                                    val height = slotH * layout.span
                                    val leftFrac = layout.subColumnIndex.toFloat() / layout.subColumnCount
                                    val widthFrac = 1f / layout.subColumnCount
                                    val colW = maxWidth * widthFrac
                                    CourseBlock(
                                        layout = layout,
                                        fontScaleKey = fontScaleKey,
                                        cardRadiusDp = cardRadiusDp,
                                        hideTeacher = hideTeacher,
                                        teacherOnlyMode = teacherOnlyMode,
                                        modifier = Modifier
                                            .offset(
                                                x = maxWidth * leftFrac,
                                                y = top
                                            )
                                            .width(colW)
                                            .height(height),
                                        onClick = { onCourseClick(layout.course) }
                                    )
                                }
                            }
                        }
                    }
                }

                if (bottomHintText.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = bottomHintText,
                        fontSize = 11.sp,
                        color = TextTertiary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/** 单个课程块（支持标准 / 隐藏教师 / 仅显示教师三种显示模式，字号与圆角跟随设置） */
@Composable
private fun CourseBlock(
    layout: CourseBlockLayout,
    fontScaleKey: String,
    cardRadiusDp: Int,
    hideTeacher: Boolean,
    teacherOnlyMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val course = layout.course
    val color = CoursePalette[course.colorIndex % CoursePalette.size]
    val shape = RoundedCornerShape(cardRadiusDp.dp)

    Box(
        modifier = modifier
            .padding(1.dp)
            .clip(shape)
            .background(color, shape)
            .clickable { onClick() },
        contentAlignment = if (hideTeacher && teacherOnlyMode) Alignment.Center else Alignment.TopStart
    ) {
        if (hideTeacher && teacherOnlyMode) {
            val teacherText = course.teacher.trim()
            val hasTeacher = teacherText.isNotEmpty()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 2.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (hasTeacher) teacherText else "未填教师",
                    fontSize = Personalize.scaleCourseFont(
                        if (layout.span >= 2) 11.5f else 10f,
                        fontScaleKey
                    ).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = if (hasTeacher) 1f else 0.72f),
                    maxLines = if (layout.span >= 2) 3 else 2,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = Personalize.scaleCourseFont(13f, fontScaleKey).sp
                )
            }
        } else {
            val compactPlace = formatCompactPlace(course.position)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 2.5.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = course.name,
                    fontSize = Personalize.scaleCourseFont(10.5f, fontScaleKey).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = if (layout.span >= 2) (if (hideTeacher) 4 else 3) else 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = Personalize.scaleCourseFont(12.5f, fontScaleKey).sp
                )
                if (compactPlace.isNotEmpty() && (layout.subColumnCount < 2 || layout.span >= 2)) {
                    Text(
                        text = compactPlace,
                        fontSize = Personalize.scaleCourseFont(9f, fontScaleKey).sp,
                        color = Color.White.copy(alpha = 0.92f),
                        maxLines = if (layout.span >= 2) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = Personalize.scaleCourseFont(10.5f, fontScaleKey).sp
                    )
                }
                if (!hideTeacher && course.teacher.isNotEmpty() && layout.span >= 2 && layout.subColumnCount < 2) {
                    Text(
                        text = course.teacher,
                        fontSize = Personalize.scaleCourseFont(8.5f, fontScaleKey).sp,
                        color = Color.White.copy(alpha = 0.84f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = Personalize.scaleCourseFont(10f, fontScaleKey).sp
                    )
                }
            }
        }
    }
}
