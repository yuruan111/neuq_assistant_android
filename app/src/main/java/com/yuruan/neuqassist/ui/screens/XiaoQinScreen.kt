package com.yuruan.neuqassist.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuruan.neuqassist.data.Keys
import com.yuruan.neuqassist.data.Logger
import com.yuruan.neuqassist.ui.HelpText
import com.yuruan.neuqassist.ui.theme.Dimens
import com.yuruan.neuqassist.ui.theme.PrimaryBlue
import com.yuruan.neuqassist.ui.theme.SurfaceLight
import com.yuruan.neuqassist.ui.theme.TextPrimary
import com.yuruan.neuqassist.ui.theme.TextSecondary
import com.yuruan.neuqassist.ui.theme.TextTertiary

/**
 * 小秦帮页 —— 移植自 HarmonyOS 端 views/XiaoQinView.ets
 *
 * 会话状态卡（登录/刷新）+ 功能宫格（校园地图 / 校历与作息 / 生活服务 / 意见反馈）
 * + 设置入口（外观设置 / 隐私政策 / 开源许可 / 免责声明）
 */

private const val FEEDBACK_MAIL = "yuruan0011@yeah.net"

private data class FeatureItem(
    val icon: String,
    val title: String,
    val desc: String,
    val url: String = ""
)

private val FEATURES = listOf(
    FeatureItem("🗺", "校园地图", "校园平面图 · 建筑分布",
        "https://yx.neuq.edu.cn/__local/7/5F/5E/A1E576CD61EE642BCCD9481ABF8_4FA76E63_75EE5.png"),
    FeatureItem("📅", "校历与作息", "学期日历 · 作息时间",
        "https://jwc.neuq.edu.cn/info/1077/3582.htm"),
    FeatureItem("🍜", "生活服务", "食堂 · 图书馆 · 班车"),
    FeatureItem("📮", "意见反馈", "问题反馈 · 功能建议")
)

@Composable
fun XiaoQinScreen(
    sessionModeLabel: String,
    onWebLogin: () -> Unit,
    onLogout: () -> Unit,
    /** 打开校历 PDF 查看页（由宿主切换全屏路由） */
    onOpenCalendar: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf<String?>(null) }
    // 网页查看（隐私政策 / 校园地图等）用独立状态承载 URL 与标题。
    // ⚠️ 不要把 URL 编码进 showDialog 字符串再用 ":" 切分——URL 自带 "https://"，
    // 首个冒号会把取值截成 "https"，导致白名单校验失败、页面打不开。
    var webUrl by remember { mutableStateOf<String?>(null) }
    var webTitle by remember { mutableStateOf("网页") }
    var showAppearance by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF2F3F5))
    ) {
        // 标题栏（右侧设置入口）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "小秦帮",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "⚙",
                fontSize = 20.sp,
                color = Color(0xFF5A6472),
                modifier = Modifier.clickable { showSettings = true }.padding(8.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.screenPadding)
        ) {
            // ===== 会话状态卡 =====
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                shape = RoundedCornerShape(Dimens.cardRadius)
            ) {
                Row(
                    modifier = Modifier.padding(Dimens.cardPadding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = sessionModeLabel,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary
                        )
                        Text(
                            text = "登录后课表导入与实时查询可用；会话过期时在此刷新",
                            fontSize = 10.sp,
                            color = TextTertiary
                        )
                    }
                    Text(
                        text = if (sessionModeLabel == "未登录") "WebVPN登录" else "刷新会话",
                        fontSize = 13.sp,
                        color = Color.White,
                        modifier = Modifier
                            .background(PrimaryBlue, RoundedCornerShape(18.dp))
                            .clickable { onWebLogin() }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimens.gapLarge))

            // ===== 功能宫格（2 列） =====
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.gapMedium)) {
                FEATURES.chunked(2).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.gapMedium)) {
                        rowItems.forEach { item ->
                            FeatureCard(
                                item = item,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    when (item.title) {
                                        "意见反馈" -> showDialog = "feedback"
                                        "校历与作息" -> onOpenCalendar()
                                        else -> if (item.url.isNotEmpty()) {
                                            webTitle = item.title
                                            webUrl = item.url
                                        }
                                    }
                                }
                            )
                        }
                        // 补齐奇数行
                        if (rowItems.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            Spacer(modifier = Modifier.height(Dimens.gapLarge))

            // ===== 设置面板入口（原端在 bindSheet 中，此处以分组列表呈现） =====
            SettingsGroup(
                items = listOf("外观设置", "隐私政策", "开源许可", "免责声明"),
                onItemClick = { key ->
                    when (key) {
                        "外观设置" -> showAppearance = true
                        "隐私政策" -> {
                            webTitle = "隐私政策"
                            webUrl = Keys.PRIVACY_POLICY_URL
                        }
                        else -> showDialog = key
                    }
                }
            )

            if (sessionModeLabel != "未登录") {
                Spacer(modifier = Modifier.height(Dimens.gapLarge))
                Surface(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    color = SurfaceLight,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("退出登录", fontSize = 14.sp, color = Color(0xFFD83931))
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 构建标识：排查问题时用于确认设备上跑的是哪一次构建
            Text(
                text = com.yuruan.neuqassist.data.BuildInfo.display(),
                fontSize = 10.sp,
                color = TextTertiary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    // ===== 对话框 =====
    when (val d = showDialog) {
        "feedback" -> {
            AlertDialog(
                onDismissRequest = { showDialog = null },
                title = { Text("意见反馈") },
                text = {
                    Text(
                        "如遇到问题或有功能建议，欢迎发送邮件至：\n\n$FEEDBACK_MAIL\n\n" +
                            "为避免重复处理，建议在邮件中注明：\n" +
                            "· 设备型号与系统版本\n" +
                            "· 问题现象与复现步骤\n" +
                            "· 如为课表/教室数据异常，请附上相关日期与时段"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(
                            android.content.ClipData.newPlainText("mail", FEEDBACK_MAIL)
                        )
                        showDialog = null
                    }) { Text("复制邮箱") }
                },
                dismissButton = {
                    TextButton(onClick = { showDialog = null }) { Text("我知道了") }
                }
            )
        }
        "开源许可" -> {
            AlertDialog(
                onDismissRequest = { showDialog = null },
                title = { Text("开源许可") },
                text = {
                    Text(
                        "本应用参考/移植了以下开源项目，在此感谢所有作者：\n\n" +
                            "1. 时光课程表 shiguangschedule（Apache-2.0）——课表数据结构与网格布局\n" +
                            "github.com/XingHeYuZhuan/shiguangschedule\n\n" +
                            "2. neuq-classroom-app（MIT，本人受邀参与共建）——WebView 注入与本地缓存免登录方案，作者 wanYuea\n" +
                            "github.com/wy723161060/neuq-classroom-app\n\n" +
                            "3. neuq-classroom-query——空教室查询逻辑与数据方案\n" +
                            "github.com/TsiaohanWang/neuq-classroom-query\n\n" +
                            "各项目许可证全文见本应用仓库 THIRD-PARTY-NOTICES.md；" +
                            "其余部分为本项目原创代码。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showDialog = null }) { Text("我知道了") }
                }
            )
        }
        "免责声明" -> {
            AlertDialog(
                onDismissRequest = { showDialog = null },
                title = { Text("免责声明") },
                text = {
                    Text(
                        "1. 本应用为非官方学生工具，与东北大学秦皇岛分校及其教务系统官方无任何隶属或授权关系。\n" +
                            "2. 应用内所有数据来自教务系统公开查询接口，仅供个人学习与参考，不保证完整性、准确性与时效性；" +
                            "课表、教室与节假日信息请以教务系统及学校官方发布为准。\n" +
                            "3. 使用本应用产生的一切后果由使用者自行承担，开发者不对因数据偏差、功能中断或使用不当造成的损失负责。\n" +
                            "4. 账号与密码仅保存在本机（密码经系统加密安全存储），不会上传至任何第三方服务器。\n" +
                            "5. 空教室云端数据来自学校公开接口的公开镜像（neuq.tsiao.io、*.pages.dev），" +
                            "节假日数据来自 timor.tech 公开服务，感谢上述服务的维护者。\n" +
                            "6. 继续使用即表示你已阅读并同意以上内容。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = { showDialog = null }) { Text("我知道了") }
                }
            )
        }
        else -> {
            // 其余情况不渲染任何弹窗；网页查看走下方 webUrl 分支
        }
    }

    // ===== 网页查看（全屏） =====
    val url = webUrl
    if (url != null) {
        WebViewPage(
            url = url,
            title = webTitle,
            onBack = { webUrl = null }
        )
    }

    // ===== 外观设置 =====
    if (showAppearance) {
        AppearanceDialog(onDismiss = { showAppearance = false })
    }
}

/** 功能宫格卡片 */
@Composable
private fun FeatureCard(item: FeatureItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = Color.White,
        shape = RoundedCornerShape(Dimens.cardRadius)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 14.dp, start = 8.dp, end = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(item.icon, fontSize = 32.sp)
            Text(
                item.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )
            Text(
                item.desc,
                fontSize = 11.sp,
                color = TextTertiary
            )
        }
    }
}

/** 设置分组列表 */
@Composable
private fun SettingsGroup(items: List<String>, onItemClick: (String) -> Unit) {    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shape = RoundedCornerShape(Dimens.cardRadius)
    ) {
        Column {
            items.forEachIndexed { i, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onItemClick(item) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = item,
                        fontSize = 14.sp,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Text("›", fontSize = 16.sp, color = TextTertiary)
                }
                if (i < items.size - 1) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp)
                            .height(1.dp)
                            .background(Color(0x1F182431))
                    )
                }
            }
        }
    }
}

/**
 * 外观设置 —— 移植自 HarmonyOS 端 XiaoQinView.ets 的 SettingsPanel 外观部分
 *
 * 含：背景图开关 / 从相册选择自定义背景 / 恢复默认 / 背景强度滑块 / 课表网格线加重。
 * 「悬浮底栏透明度」为 HarmonyOS HdsTabs 专有能力，Android 无对应物，未移植。
 */
@Composable
private fun AppearanceDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { com.yuruan.neuqassist.data.SettingsStore(context) }

    var bgEnabled by remember { mutableStateOf(settings.bgEnabled) }
    var bgDim by remember { mutableStateOf(settings.bgDim) }
    var lineWeight by remember { mutableStateOf(settings.lineWeight) }

    // 图片选择器（系统相册，无需存储权限）
    val pickImageLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val dest = java.io.File(context.filesDir, "bg_custom.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                settings.bgImagePath = dest.absolutePath
                settings.bgEnabled = true
                bgEnabled = true
            } catch (e: Exception) {
                Logger.warn("保存自定义背景失败: ${e.message}")
            }
        }
    }

    fun persist() {
        settings.bgEnabled = bgEnabled
        settings.bgDim = bgDim
        settings.lineWeight = lineWeight
    }

    AlertDialog(
        onDismissRequest = { persist(); onDismiss() },
        title = { Text("外观设置", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // —— 背景开关 ——
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "使用背景图片",
                        fontSize = 14.sp,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = bgEnabled, onCheckedChange = { bgEnabled = it; persist() })
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        onClick = { pickImageLauncher.launch("image/*") },
                        modifier = Modifier.weight(1f).height(36.dp),
                        color = SurfaceLight,
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("从相册选择背景", fontSize = 13.sp, color = TextPrimary)
                        }
                    }
                    if (settings.bgImagePath.isNotEmpty()) {
                        Surface(
                            onClick = {
                                try {
                                    java.io.File(settings.bgImagePath).delete()
                                } catch (e: Exception) {
                                    // 文件不存在时忽略
                                }
                                settings.bgImagePath = ""
                                bgEnabled = true
                                persist()
                            },
                            modifier = Modifier.weight(1f).height(36.dp),
                            color = SurfaceLight,
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("恢复默认", fontSize = 13.sp, color = Color(0xFFD83931))
                            }
                        }
                    }
                }

                // —— 背景强度 ——
                Row {
                    Text(
                        text = "背景强度（越大越清晰）",
                        fontSize = 14.sp,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${Math.round(bgDim * 100)}%",
                        fontSize = 14.sp,
                        color = PrimaryBlue
                    )
                }
                Slider(
                    value = bgDim,
                    onValueChange = {
                        bgDim = it
                        persist()
                    },
                    valueRange = 0.6f..1.0f,
                    steps = 19
                )

                // —— 课表网格线加重 ——
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("课表网格线加重", fontSize = 14.sp, color = TextPrimary)
                        Text(
                            text = "背景较花时打开，网格边界更清晰",
                            fontSize = 11.sp,
                            color = TextTertiary
                        )
                    }
                    Switch(
                        checked = lineWeight == "solid",
                        onCheckedChange = {
                            lineWeight = if (it) "solid" else "normal"
                            persist()
                        }
                    )
                }

                HelpText(
                    text = "提示：自定义背景与背景强度保存在本机；" +
                        "「悬浮底栏透明度」为 HarmonyOS 专有能力，Android 版未提供。"
                )
            }
        },
        confirmButton = { TextButton(onClick = { persist(); onDismiss() }) { Text("完成") } }
    )
}
