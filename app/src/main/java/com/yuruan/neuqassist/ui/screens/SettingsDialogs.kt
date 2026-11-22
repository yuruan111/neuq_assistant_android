package com.yuruan.neuqassist.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuruan.neuqassist.ui.AppTextField
import com.yuruan.neuqassist.ui.HelpText
import com.yuruan.neuqassist.ui.theme.Dimens
import com.yuruan.neuqassist.ui.theme.PrimaryBlue
import com.yuruan.neuqassist.ui.theme.SurfaceLight
import com.yuruan.neuqassist.ui.theme.TextPrimary
import com.yuruan.neuqassist.ui.theme.TextTertiary
import com.yuruan.neuqassist.ui.theme.TextPrimary
import com.yuruan.neuqassist.ui.theme.TextSecondary
import com.yuruan.neuqassist.ui.theme.TextTertiary

/**
 * 空教室查询页的高级设置对话框 —— 移植自 HarmonyOS 端 Index.ets 的 settingsPanel
 * （方式一：直连 / 脚本登录配置）
 *
 * 方式二（网页通道）为默认唯一首选，无需在此配置账号密码；
 * 仅在它失败（且填有账号密码）时才会自动回退直连。
 */
@Composable
fun ClassroomSettingsDialog(
    username: String,
    password: String,
    useWebvpn: Boolean,
    authType: String,
    webvpnUsername: String,
    webvpnPassword: String,
    serverUrl: String,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onUseWebvpn: (Boolean) -> Unit,
    onAuthType: (String) -> Unit,
    onWebvpnUsername: (String) -> Unit,
    onWebvpnPassword: (String) -> Unit,
    onServerUrl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("高级设置", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.gapMedium)
            ) {
                HelpText(
                    text = "默认使用「网页通道」：在「小秦帮 → 教务登录」登录一次即可，无需账号密码。" +
                        "下方为可选的直连方式配置（网页通道失败时自动回退）。"
                )
                HelpText(
                    text = "自建服务器（可选）：配置后云端查询优先从你的服务器获取" +
                        "（部署见 server/server.py）。留空则直接访问公共镜像。"
                )
                AppTextField(serverUrl, onServerUrl, "自建服务器接口（可选）")

                Spacer(modifier = Modifier.height(4.dp))
                Text("教务系统账号（可选）", fontSize = 12.sp, color = TextTertiary)
                AppTextField(username, onUsername, "学号")
                AppTextField(password, onPassword, "密码", isPassword = true)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("校外访问（WebVPN）", fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                    Switch(checked = useWebvpn, onCheckedChange = onUseWebvpn)
                }

                if (useWebvpn) {
                    Text("WebVPN 账号（留空则使用上方学号）", fontSize = 12.sp, color = TextTertiary)
                    AppTextField(webvpnUsername, onWebvpnUsername, "WebVPN/统一身份认证账号")
                    AppTextField(webvpnPassword, onWebvpnPassword, "WebVPN/统一身份认证密码", isPassword = true)

                    Text("认证方式", fontSize = 14.sp, color = TextPrimary)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = authType == "cas", onClick = { onAuthType("cas") })
                        Text("统一身份认证", fontSize = 13.sp, color = TextPrimary)
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        RadioButton(selected = authType == "local", onClick = { onAuthType("local") })
                        Text("本地账号", fontSize = 13.sp, color = TextPrimary)
                    }
                    HelpText(
                        text = "提示：校外网络（含流量）直连教务系统会被 HTTP 483 拒绝，需开启 WebVPN。" +
                            "账号密码仅保存在本机加密存储中。"
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        }
    )
}

/**
 * 学期起点设置对话框 —— 移植自 HarmonyOS 端 ScheduleView.ets 的 TermStartEditor
 */
@Composable
fun TermStartDialog(
    currentKey: String,
    onPickDate: () -> Unit,
    onOpenCalendar: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("学期起点", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.gapMedium)) {
                Text("当前开学日期（第 1 周的周一）", fontSize = 12.sp, color = TextTertiary)
                Text(
                    text = currentKey,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                HelpText(text = "打开教务处校历页面，可在线查看，并可用「识别开学日期」自动识别。")

                Surface(
                    onClick = onOpenCalendar,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    color = com.yuruan.neuqassist.ui.theme.CampusMainBg,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("📅 查看校历", fontSize = 14.sp, color = TextPrimary)
                    }
                }

                Surface(
                    onClick = onPickDate,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    color = SurfaceLight,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("📅 手动选择日期", fontSize = 14.sp, color = TextPrimary)
                    }
                }
                HelpText(
                    text = "手动选择会取所选日期所在周的周一为第 1 周起点。" +
                        "周次对不准时在此修正，学期起点也是导入时自动选学期的依据。"
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

/**
 * 上课时间编辑对话框 —— 移植自 HarmonyOS 端 ScheduleView.ets 的 TimeSlotsEditor
 */
@Composable
fun TimeSlotsDialog(
    slots: List<com.yuruan.neuqassist.data.TimeSlot>,
    onSlotChange: (Int, com.yuruan.neuqassist.data.TimeSlot) -> Unit,
    onAddSlot: () -> Unit,
    onRemoveSlot: (Int) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上课时间", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = {
            // 滚轮选中的节次/起止（-1 = 未打开）
            var wheelIndex by remember { mutableStateOf(-1) }
            var wheelIsStart by remember { mutableStateOf(true) }

            Column(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HelpText(text = "点击时间弹出滚轮选择（24 小时制），修改后点击保存生效。")
                slots.forEachIndexed { i, slot ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = com.yuruan.neuqassist.ui.theme.SurfaceGrey,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("第${i + 1}节", fontSize = 13.sp, color = TextPrimary, modifier = Modifier.padding(start = 4.dp))
                            TimeChip(
                                text = slot.startTime,
                                onClick = { wheelIndex = i; wheelIsStart = true }
                            )
                            Text("~", fontSize = 13.sp, color = TextSecondary)
                            TimeChip(
                                text = slot.endTime,
                                onClick = { wheelIndex = i; wheelIsStart = false }
                            )
                            TextButton(
                                onClick = { onRemoveSlot(i) },
                                enabled = slots.size > 1
                            ) { Text("✕", color = TextTertiary) }
                        }
                    }
                }
                TextButton(onClick = onAddSlot, enabled = slots.size < 15) {
                    Text("+ 添加节次")
                }
                HelpText(text = "注意：重新导入课表会重置为教务下发的作息。至少保留一节。")

                // 时间滚轮（对应原端 WheelPicker：小时轮 + 分钟轮 5 分钟一档）
                if (wheelIndex >= 0 && wheelIndex < slots.size) {
                    val idx = wheelIndex
                    val isStart = wheelIsStart
                    val current = if (isStart) slots[idx].startTime else slots[idx].endTime
                    TimeWheelDialog(
                        current = current,
                        onCancel = { wheelIndex = -1 },
                        onConfirm = { value ->
                            val old = slots[idx]
                            val next = if (isStart) {
                                old.copy(startTime = value)
                            } else {
                                old.copy(endTime = value)
                            }
                            onSlotChange(idx, next)
                            wheelIndex = -1
                        }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 小时轮 0-23 */
private val WHEEL_HOURS = (0..23).map { String.format("%02d", it) }

/** 分钟轮 5 分钟一档（12 档） */
private val WHEEL_MINUTES = (0..11).map { String.format("%02d", it * 5) }

/** 可点击的时间胶囊（对应原端 TimeChip） */
@Composable
private fun TimeChip(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.height(36.dp),
        color = Color.White,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33182431))
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text, fontSize = 13.sp, color = TextPrimary)
        }
    }
}

/**
 * 时间滚轮 —— 移植自 HarmonyOS 端 ScheduleView.ets 的 WheelPicker。
 *
 * 小时轮 0-23 + 分钟轮 5 分钟一档，拖动实时预览，确认后写回。
 */
@Composable
private fun TimeWheelDialog(
    current: String,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val parts = current.split(":")
    var hourIdx by remember {
        mutableStateOf(
            parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
        )
    }
    var minIdx by remember {
        mutableStateOf(
            ((parts.getOrNull(1)?.toIntOrNull() ?: 0) / 5).coerceIn(0, 11)
        )
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("选择时间", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${WHEEL_HOURS[hourIdx]}:${WHEEL_MINUTES[minIdx]}",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = PrimaryBlue,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // 小时轮
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("时", fontSize = 12.sp, color = TextSecondary)
                        androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier.height(150.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            itemsIndexed(WHEEL_HOURS) { i, h ->
                                val selected = i == hourIdx
                                Text(
                                    text = h,
                                    fontSize = if (selected) 18.sp else 14.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) PrimaryBlue else TextSecondary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { hourIdx = i }
                                        .padding(vertical = 8.dp, horizontal = 20.dp)
                                )
                            }
                        }
                    }
                    // 分钟轮
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("分（5 分钟一档）", fontSize = 12.sp, color = TextSecondary)
                        androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier.height(150.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            itemsIndexed(WHEEL_MINUTES) { i, m ->
                                val selected = i == minIdx
                                Text(
                                    text = m,
                                    fontSize = if (selected) 18.sp else 14.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) PrimaryBlue else TextSecondary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { minIdx = i }
                                        .padding(vertical = 8.dp, horizontal = 20.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm("${WHEEL_HOURS[hourIdx]}:${WHEEL_MINUTES[minIdx]}") }) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } }
    )
}

/**
 * 导入课表对话框 —— 移植自 HarmonyOS 端 ScheduleView.ets 的 ImportView
 */
@Composable
fun ImportScheduleDialog(
    sessionReady: Boolean,
    busy: Boolean,
    statusText: String,
    errorText: String,
    terms: List<com.yuruan.neuqassist.data.TermInfo>,
    onStartWebImport: () -> Unit,
    onPickTerm: (String) -> Unit,
    onPickFile: () -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入课表", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.gapMedium)
            ) {
                HelpText(
                    text = "在教务页面同源上下文中直接读取数据：无需输入账号密码，" +
                        "复用「小秦帮 → 教务登录」的会话。导入会覆盖当前课表与作息时间。"
                )
                if (errorText.isNotEmpty()) {
                    Text(errorText, fontSize = 11.sp, color = com.yuruan.neuqassist.ui.theme.ErrorRedDeep)
                }
                if (busy) {
                    com.yuruan.neuqassist.ui.LoadingRow(
                        text = statusText.ifEmpty { "处理中…" }
                    )
                } else if (terms.isEmpty()) {
                    Surface(
                        onClick = onStartWebImport,
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        color = PrimaryBlue,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) { Text("启动网页通道", fontSize = 14.sp, color = androidx.compose.ui.graphics.Color.White) }
                    }
                    if (!sessionReady) {
                        HelpText(text = "尚未登录：请先到「小秦帮 → 教务登录」完成一次网页登录。")
                    }
                } else {
                    Text(
                        statusText.ifEmpty { "请选择要导入的学期：" },
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    terms.forEach { t ->
                        Surface(
                            onClick = { onPickTerm(t.id) },
                            modifier = Modifier.fillMaxWidth(),
                            color = com.yuruan.neuqassist.ui.theme.SurfaceGrey,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(t.name, fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                                Text("导入", fontSize = 13.sp, color = PrimaryBlue)
                            }
                        }
                    }
                    TextButton(onClick = onReload) { Text("重新检测") }
                }

                Spacer(modifier = Modifier.height(4.dp))
                HelpText(
                    text = "免登录备选：在任意浏览器登录教务 → 课表页右上角「导出」，" +
                        "把文件发送到手机后在此选择。"
                )
                Surface(
                    onClick = onPickFile,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    color = androidx.compose.ui.graphics.Color(0xFF34A853),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) { Text("📄 从导出文件导入", fontSize = 14.sp, color = androidx.compose.ui.graphics.Color.White) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/** FAQ 单条问答卡片 */
@Composable
private fun FaqSection(head: String, body: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(head, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
        Spacer(modifier = Modifier.height(4.dp))
        Text(body, fontSize = 12.sp, color = TextTertiary, lineHeight = 18.sp)
    }
}

/**
 * 常见问题（FAQ）—— 移植自 HarmonyOS 端 ScheduleView.ets 的 FaqView
 */
@Composable
fun FaqDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("课表使用说明", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                FaqSection(
                    "📥 导入课表",
                    "① 先到「小秦帮」页完成教务登录（导入复用该会话，无需二次输密码）。\n" +
                        "② 课表右上角调整面板 →「导入课表」。若提示未登录，点「去网页登录」，完成后回来点「登录完成，重新检测」。\n" +
                        "③ 学期列表出现后会按学期起点自动选中当前学期，也可手动点某学期的「导入」。\n" +
                        "④ 免登录备选：在任意浏览器登录教务 → 课表页右上角「导出」，把文件发送到手机，在导入面板点「从导出文件导入」。\n" +
                        "注意：导入会覆盖现有课表与自定义作息时间。"
                )
                FaqSection(
                    "📚 手动添加课程",
                    "调整面板 →「课程管理」→「+ 添加课程」：填课程名称（必填），教师、地点选填，选星期、起止节次和颜色，保存后立即显示在课表上。\n" +
                        "列表中「✏️」编辑、「🗑」删除，删除立即生效。"
                )
                FaqSection(
                    "🗓 周次怎么选 / 单次课",
                    "新课程默认全学期（第1-20周）都有。可用快捷预设一键套用：全学期 / 单周 / 双周 / 前半 / 后半；也可点周数字自由勾选任意组合（如第3、5、8周）。\n" +
                        "单次课：点「单次」预设会自动选中本周（考试、补课、讲座提醒），仅该周显示。"
                )
                FaqSection(
                    "⏰ 上课时间不对",
                    "每节课的起止时间在 调整面板 →「上课时间」中修改，保存后课表按新作息显示。\n" +
                        "注意：重新导入课表会重置为教务下发的作息。"
                )
                FaqSection(
                    "🔢 周次对不准怎么办",
                    "若顶部「第X周」与实际不符，是学期起点不对：调整面板 →「学期起点」→ 选开学第一周的周一日期。\n" +
                        "学期起点也是导入时自动选学期的依据。"
                )
                FaqSection(
                    "🔄 切换周次",
                    "课表区域左右滑动切换上一周 / 下一周，也可点顶部的左右箭头按钮。\n" +
                        "查看其他周时，点击「回到本周」按钮返回当前周。"
                )
                FaqSection(
                    "💾 数据保存在哪",
                    "课表、作息、学期起点、手动添加的课程都保存在手机本地，打开应用自动恢复；卸载应用会清空。\n" +
                        "换机或卸载前建议截图备份，重新登录教务即可再次导入。"
                )
                FaqSection(
                    "📮 遇到问题",
                    "在小秦帮页点「意见反馈」获取反馈邮箱。反馈时请附截图并说明操作步骤，更容易定位问题。"
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("我知道了") } }
    )
}
