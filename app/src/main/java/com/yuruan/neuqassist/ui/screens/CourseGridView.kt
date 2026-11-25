package com.yuruan.neuqassist.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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

private val SIDE_W = 44.dp      // 左侧节次栏宽度
private val HEADER_H = 44.dp    // 顶部星期表头高度
private val SLOT_MIN = 40.dp    // 节次行高下限
private val SLOT_MAX = 84.dp    // 节次行高上限
private val SLOT_DEFAULT = 56.dp

private val DAY_CHARS = listOf("一", "二", "三", "四", "五", "六", "日")

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
    availableHeight: Dp = 480.dp
) {
    val slotCount = if (timeSlots.isEmpty()) 12 else timeSlots.size
    val usable = availableHeight - HEADER_H
    val slotH = if (usable <= 0.dp) {
        SLOT_DEFAULT
    } else {
        (usable / slotCount).coerceIn(SLOT_MIN, SLOT_MAX)
    }
    val gridLine = if (lineWeightSolid) GridLineSolid else GridLineLight
    val layouts = layoutCourseBlocks(courses)
    // 今日态强调色跟随主题预设（个性化设置 → 主题配色）
    val accent = MaterialTheme.colorScheme.primary
    val slotHPx = with(LocalDensity.current) { slotH.toPx() }
    val currentOnBlankDoubleTap by rememberUpdatedState(onBlankDoubleTap)

    Column(modifier = modifier.fillMaxWidth()) {
        // ===== 顶部星期表头 =====
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
                Text(monthLabel, fontSize = 11.sp, color = TextSecondary)
            }
            // 7 天列表头（今日列强调色跟随主题预设）
            for (d in 0 until 7) {
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

        // ===== 网格区（节次行 × 7 天列） =====
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
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                            Text(
                                text = timeSlots.getOrNull(i)?.startTime ?: "",
                                fontSize = 8.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            // 7 天列（今日列轻微着色，色调随主题预设）
            for (d in 0 until 7) {
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

                    // 课程块（绝对定位由 Box + offset 模拟）
                    for (layout in dayLayouts) {
                        val top = slotH * (layout.course.startSection - 1)
                        val height = slotH * layout.span
                        val leftFrac = layout.subColumnIndex.toFloat() / layout.subColumnCount
                        val widthFrac = 1f / layout.subColumnCount
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = 0.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(
                                        start = (SIDE_W.value * 0f).dp
                                    )
                            ) {}
                            // 使用 fraction 定位
                            androidx.compose.foundation.layout.BoxWithConstraints(
                                modifier = Modifier.fillMaxSize()
                            ) {
                                val colW = maxWidth * widthFrac
                                CourseBlock(
                                    layout = layout,
                                    fontScaleKey = fontScaleKey,
                                    cardRadiusDp = cardRadiusDp,
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
        }
    }
}

/** 单个课程块（字号随档位系数缩放、圆角随设置；标准档 = 历史渲染不变） */
@Composable
private fun CourseBlock(
    layout: CourseBlockLayout,
    fontScaleKey: String,
    cardRadiusDp: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val course = layout.course
    val color = CoursePalette[course.colorIndex % CoursePalette.size]
    val place = splitCoursePlace(course.position)

    Box(
        modifier = modifier
            .padding(1.dp)
            .background(color, RoundedCornerShape(cardRadiusDp.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.TopStart
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Text(
                text = course.name,
                fontSize = Personalize.scaleCourseFont(11f, fontScaleKey).sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = if (layout.span >= 2) 4 else 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = Personalize.scaleCourseFont(13f, fontScaleKey).sp
            )
            if (course.teacher.isNotEmpty() && layout.span >= 2) {
                Text(
                    text = course.teacher,
                    fontSize = Personalize.scaleCourseFont(9f, fontScaleKey).sp,
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (place.building.isNotEmpty() && layout.span >= 2) {
                Text(
                    text = place.building,
                    fontSize = Personalize.scaleCourseFont(9f, fontScaleKey).sp,
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (place.room.isNotEmpty()) {
                    Text(
                        text = place.room,
                        fontSize = Personalize.scaleCourseFont(9f, fontScaleKey).sp,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (place.room.isNotEmpty()) {
                Text(
                    text = place.room,
                    fontSize = Personalize.scaleCourseFont(9f, fontScaleKey).sp,
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
