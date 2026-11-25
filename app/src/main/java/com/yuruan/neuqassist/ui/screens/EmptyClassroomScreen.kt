package com.yuruan.neuqassist.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuruan.neuqassist.data.AppConfig
import com.yuruan.neuqassist.data.BuildingGroup
import com.yuruan.neuqassist.data.ProcessedClassroomData
import com.yuruan.neuqassist.data.QueryResult
import com.yuruan.neuqassist.ui.Chip
import com.yuruan.neuqassist.ui.PrimaryButton
import com.yuruan.neuqassist.ui.SecondaryButton
import com.yuruan.neuqassist.ui.LoadingRow
import com.yuruan.neuqassist.ui.ErrorBanner
import com.yuruan.neuqassist.ui.WarnBanner
import com.yuruan.neuqassist.ui.SourceNote
import com.yuruan.neuqassist.ui.EmptyHint
import com.yuruan.neuqassist.ui.HelpText
import com.yuruan.neuqassist.data.DateUtil
import com.yuruan.neuqassist.ui.theme.CampusMainBg
import com.yuruan.neuqassist.ui.theme.CampusMainText
import com.yuruan.neuqassist.ui.theme.CampusSouthBg
import com.yuruan.neuqassist.ui.theme.CampusSouthText
import com.yuruan.neuqassist.ui.theme.CardBg
import com.yuruan.neuqassist.ui.theme.WarnBg
import com.yuruan.neuqassist.ui.theme.WarnOrange
import com.yuruan.neuqassist.ui.theme.Dimens
import com.yuruan.neuqassist.ui.theme.SurfaceGrey
import com.yuruan.neuqassist.ui.theme.SurfaceLight
import com.yuruan.neuqassist.ui.theme.TextPrimary
import com.yuruan.neuqassist.ui.theme.TextSecondary
import com.yuruan.neuqassist.ui.theme.TextTertiary

/**
 * 空教室查询页 —— 移植自 HarmonyOS 端 pages/Index.ets
 *
 * 数据链路：ClassroomService（登录/查询/过滤/分组）→ 本页面展示。
 * 配置（学号/密码/WebVPN 开关）通过 SettingsStore 持久化。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EmptyClassroomScreen(
    cloudSource: Boolean,
    onCloudSourceChange: (Boolean) -> Unit,
    sessionReady: Boolean,
    onGoLogin: () -> Unit,
    dayOffset: Int,
    onDayChange: (Int) -> Unit,
    slotIndex: Int,
    onSlotChange: (Int) -> Unit,
    loading: Boolean,
    progressText: String,
    errorMsg: String,
    result: QueryResult?,
    cacheInfoText: String,
    sourceText: String,
    warningText: String,
    bulkBusy: Boolean,
    cooldownLeft: Int,
    onCancelCooldown: () -> Unit,
    onQuery: () -> Unit,
    onBulkUpdate: () -> Unit,
    onOpenSettings: () -> Unit,
    onRoomClick: (ProcessedClassroomData) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 根布局透明：全局背景（自定义/内置图 + 保护层）由 MainActivity 统一渲染后透出
    Column(
        modifier = modifier
            .fillMaxSize()
    ) {
        // 标题栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "空教室查询",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "⚙",
                fontSize = 20.sp,
                color = Color(0xFF5A6472),
                modifier = Modifier
                    .clickable { onOpenSettings() }
                    .padding(8.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.screenPadding)
        ) {
            // 数据来源切换
            Column(modifier = Modifier.padding(top = 4.dp)) {
                Text("数据来源", fontSize = 13.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(
                        item = "云端数据（免登录）",
                        selected = cloudSource,
                        onClick = { onCloudSourceChange(true) }
                    )
                    Chip(
                        item = "实时查询（需登录）",
                        selected = !cloudSource,
                        onClick = { onCloudSourceChange(false) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimens.gapLarge))

            // 实时查询需要一次网页登录会话：直接给出入口，避免用户卡在「请先登录」提示上
            if (!cloudSource && !sessionReady) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = WarnBg,
                    shape = RoundedCornerShape(Dimens.smallRadius)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(
                            text = "实时查询需要一次教务网页登录会话（默认通道无需账号密码）",
                            fontSize = 13.sp,
                            color = WarnOrange
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SecondaryButton(text = "去网页登录", onClick = onGoLogin)
                    }
                }
                Spacer(modifier = Modifier.height(Dimens.gapLarge))
            }

            // 日期选择（单行横向滚动）
            Column {
                Text("日期", fontSize = 13.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(AppConfig.TOTAL_DAYS) { i ->
                        Chip(
                            item = DateUtil.dayLabel(i),
                            selected = dayOffset == i,
                            onClick = { onDayChange(i) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Dimens.gapLarge))

            // 时段选择（FlowRow 自动换行）
            Column {
                Text("时段", fontSize = 13.sp, color = TextSecondary)
                Spacer(modifier = Modifier.height(6.dp))
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AppConfig.TIME_SLOTS.forEachIndexed { i, slot ->
                        Chip(
                            item = slot.label,
                            selected = slotIndex == i,
                            onClick = { onSlotChange(i) }
                        )
                    }
                    Chip(
                        item = "全天空闲",
                        selected = slotIndex == AppConfig.TIME_SLOTS.size,
                        onClick = { onSlotChange(AppConfig.TIME_SLOTS.size) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimens.gapLarge))

            // WebVPN 登录限流冷却倒计时（T-14b-2）：可立即取消等待
            if (cooldownLeft > 0) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = WarnBg,
                    shape = RoundedCornerShape(Dimens.smallRadius)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "WebVPN 登录限流冷却：${cooldownLeft}s",
                            fontSize = 13.sp,
                            color = WarnOrange,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "取消等待",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { onCancelCooldown() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(Dimens.gapLarge))
            }

            PrimaryButton(
                text = if (loading) "查询中..." else "查询",
                onClick = onQuery,
                enabled = !loading
            )

            Spacer(modifier = Modifier.height(8.dp))

            SecondaryButton(
                text = if (bulkBusy) "更新中..." else "一键更新近 5 天（保存本地）",
                onClick = onBulkUpdate,
                enabled = !loading
            )

            if (loading) {
                Spacer(modifier = Modifier.height(Dimens.gapLarge))
                LoadingRow(
                    text = if (progressText.isEmpty()) "正在获取数据" else progressText
                )
            }

            if (errorMsg.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Dimens.gapLarge))
                ErrorBanner(text = errorMsg)
            }

            if (cacheInfoText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                SourceNote(text = cacheInfoText)
            }

            if (sourceText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                SourceNote(text = sourceText)
            }

            if (warningText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                WarnBanner(text = warningText)
            }

            // 结果
            Spacer(modifier = Modifier.height(Dimens.gapLarge))
            result?.let { r ->
                ResultView(r, onRoomClick = onRoomClick)
            }

            HelpText(
                text = "数据来自教务系统实时查询，仅供参考；因节假日调休教务系统未更新时，" +
                    "查询结果可能不正常。请以教务系统实际查询结果为准。",
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
            )
        }
    }
}

/** 结果展示（对应原端 resultView + RoomGrid） */
@Composable
private fun ResultView(
    result: QueryResult,
    onRoomClick: (ProcessedClassroomData) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.gapLarge)) {
        Text(
            text = "${DateUtil.dateToCn(result.dateStr)} · ${result.slotLabel} · " +
                "共 ${result.totalRooms} 间",
            fontSize = 13.sp,
            color = TextSecondary
        )

        if (result.totalRooms == 0) {
            Text(
                text = "该时段没有符合条件的空闲教室",
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 40.dp)
            )
        }

        result.groups.forEach { group ->
            BuildingCard(group, onRoomClick = onRoomClick)
        }
    }
}

/** 单栋教学楼卡片 */
@Composable
private fun BuildingCard(
    group: BuildingGroup,
    onRoomClick: (ProcessedClassroomData) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CardBg,
        shape = RoundedCornerShape(Dimens.cardRadius)
    ) {
        Column(modifier = Modifier.padding(Dimens.cardPadding)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = group.building,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                if (group.campus.isNotEmpty()) {
                    val isSouth = group.campus == "南校区"
                    Surface(
                        color = if (isSouth) CampusSouthBg else CampusMainBg,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = group.campus,
                            fontSize = 11.sp,
                            color = if (isSouth) CampusSouthText else CampusMainText,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = "${group.rooms.size} 间",
                    fontSize = 12.sp,
                    color = TextTertiary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 楼内多楼层 → 按楼层分节；单楼层（或全为不可判定）→ 平铺
            if (group.floors.size > 1) {
                group.floors.forEach { fg ->
                    Text(
                        text = fg.floor,
                        fontSize = 13.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    RoomGrid(fg.rooms, onRoomClick = onRoomClick)
                    Spacer(modifier = Modifier.height(6.dp))
                }
            } else {
                RoomGrid(group.rooms, onRoomClick = onRoomClick)
            }
        }
    }
}

/** 房间卡片网格（楼内某楼层或整楼平铺共用） */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RoomGrid(
    rooms: List<ProcessedClassroomData>,
    onRoomClick: (ProcessedClassroomData) -> Unit
) {
    // Compose FlowRow 实现自适应换行网格
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        rooms.forEach { room ->
            Surface(
                onClick = { onRoomClick(room) },
                color = SurfaceLight,
                shape = RoundedCornerShape(Dimens.smallRadius)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = room.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    if (room.capacity.isNotEmpty()) {
                        Text(text = "容量 ${room.capacity}", fontSize = 10.sp, color = TextTertiary)
                    }
                }
            }
        }
    }
}
