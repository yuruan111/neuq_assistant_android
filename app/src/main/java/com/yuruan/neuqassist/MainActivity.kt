package com.yuruan.neuqassist

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yuruan.neuqassist.data.AppConfig
import com.yuruan.neuqassist.data.DateUtil
import com.yuruan.neuqassist.data.ScheduleViewUtil
import com.yuruan.neuqassist.ui.MainViewModel
import com.yuruan.neuqassist.ui.screens.ClassroomSettingsDialog
import com.yuruan.neuqassist.ui.screens.ConsentDialog
import com.yuruan.neuqassist.ui.screens.CoursePreviewSheet
import com.yuruan.neuqassist.ui.screens.CoursesManagerScreen
import com.yuruan.neuqassist.ui.screens.EmptyClassroomScreen
import com.yuruan.neuqassist.ui.screens.FaqDialog
import com.yuruan.neuqassist.ui.screens.ImportScheduleDialog
import com.yuruan.neuqassist.ui.screens.ScheduleScreen
import com.yuruan.neuqassist.ui.screens.TermStartDialog
import com.yuruan.neuqassist.ui.screens.TimeSlotsDialog
import com.yuruan.neuqassist.ui.screens.WebLoginPage
import com.yuruan.neuqassist.ui.screens.XiaoQinScreen
import com.yuruan.neuqassist.ui.screens.weeksText
import com.yuruan.neuqassist.ui.theme.NEUQTheme
import com.yuruan.neuqassist.ui.theme.PrimaryBlue
import com.yuruan.neuqassist.ui.theme.TextPrimary
import java.util.Calendar

/**
 * 主 Activity —— 移植自 HarmonyOS 端 pages/MainPage.ets（底部三栏壳）
 *
 * 底部三栏：课表查询 / 空教室查询 / 小秦帮
 *
 * 【隐私闸门】未同意隐私政策时不渲染三个 Tab（各页的联网分支不会执行），
 * 点「退出应用」结束 Activity，不留后台进程。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NEUQTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFF2F3F5)
                ) {
                    MainScreen(onExitApp = { finish() })
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        com.yuruan.neuqassist.ui.WebFetchHelper.release()
    }
}

/** 底部 Tab 定义 */
private enum class Tab(val title: String) {
    SCHEDULE("课表查询"),
    CLASSROOM("空教室"),
    XIAOQIN("小秦帮")
}

@Composable
fun MainScreen(
    onExitApp: () -> Unit,
    vm: MainViewModel = viewModel()
) {
    var currentTab by remember { mutableStateOf(Tab.SCHEDULE) }
    var showWebLogin by remember { mutableStateOf(false) }
    var webLoginMode by remember { mutableStateOf("webvpn") }
    // 应用内网页查看（校历等）：非空时全屏显示 WebViewPage
    var webViewUrl by remember { mutableStateOf<String?>(null) }
    var webViewTitle by remember { mutableStateOf("网页") }
    // 校历 PDF 查看页（Android WebView 不能渲染 PDF，走专用渲染器）
    var showCalendarPdf by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 对话框状态
    var showClassroomSettings by remember { mutableStateOf(false) }
    var showAdjustPanel by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var showTimeSlots by remember { mutableStateOf(false) }
    var showTermStart by remember { mutableStateOf(false) }
    var showCourses by remember { mutableStateOf(false) }
    var showFaq by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    var previewCourse by remember { mutableStateOf<com.yuruan.neuqassist.data.CourseItem?>(null) }
    var toastMsg by remember { mutableStateOf("") }

    // 课程编辑表单状态（课程管理面板与「课程预览 → 编辑」共用，故提升到本层作用域）
    var courseEditMode by remember { mutableStateOf("") }   // ''=列表 'add'=添加 'edit'=编辑
    var courseEditId by remember { mutableStateOf("") }
    var formName by remember { mutableStateOf("") }
    var formTeacher by remember { mutableStateOf("") }
    var formPosition by remember { mutableStateOf("") }
    var formDay by remember { mutableStateOf(1) }
    var formStart by remember { mutableStateOf(1) }
    var formEnd by remember { mutableStateOf(2) }
    var formColor by remember { mutableStateOf(0) }
    var formWeeks by remember { mutableStateOf((1..20).toList()) }

    /** 载入某门课程到编辑表单（预览页「编辑」与列表「✏️」共用） */
    fun startEditCourse(course: com.yuruan.neuqassist.data.CourseItem) {
        courseEditMode = "edit"
        courseEditId = course.id
        formName = course.name
        formTeacher = course.teacher
        formPosition = course.position
        formDay = course.day
        formStart = course.startSection
        formEnd = course.endSection
        formColor = course.colorIndex
        formWeeks = course.weeks
    }

    /** 清空表单并进入新增模式 */
    fun startAddCourse() {
        courseEditMode = "add"
        courseEditId = ""
        formName = ""
        formTeacher = ""
        formPosition = ""
        formDay = 1
        formStart = 1
        formEnd = 2
        formColor = (0..7).random()
        formWeeks = (1..20).toList()
    }

    // ===== 隐私闸门 =====
    if (!vm.privacyAgreed) {
        Box(modifier = Modifier.fillMaxSize()) {
            ConsentDialog(
                onAgree = { vm.agreePrivacy() },
                onDecline = { onExitApp() }
            )
        }
        return
    }

    // ===== 网页登录全屏页 =====
    if (showWebLogin) {
        WebLoginPage(
            mode = webLoginMode,
            onSessionExtracted = { cookies ->
                vm.onWebLoginSuccess(webLoginMode)
                showWebLogin = false
                toastMsg = "登录会话已获取，可直接查询"
            },
            onBack = { showWebLogin = false }
        )
        return
    }

    // ===== 应用内网页查看（校历等） =====
    // 对应 HarmonyOS 端 router.pushUrl('pages/WebViewPage')；不跳外部浏览器，
    // 避免设备未装浏览器时「打不开」
    val webUrl = webViewUrl
    if (webUrl != null) {
        com.yuruan.neuqassist.ui.screens.WebViewPage(
            url = webUrl,
            title = webViewTitle,
            onBack = { webViewUrl = null }
        )
        return
    }

    // ===== 校历 PDF 查看页 =====
    if (showCalendarPdf) {
        com.yuruan.neuqassist.ui.screens.CalendarPdfScreen(
            onBack = { showCalendarPdf = false }
        )
        return
    }

    // ===== 主界面 =====
    val filePickLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@rememberLauncherForActivityResult
            try {
                val text = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: ""
                vm.importFromFile(text) { ok, msg ->
                    if (ok) {
                        showImport = false
                        toastMsg = msg
                    } else {
                        toastMsg = msg
                    }
                }
            } catch (e: Exception) {
                toastMsg = "读取文件失败: ${e.message}"
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 内容区
            // ⚠️ 顶部安全区避让：enableEdgeToEdge() 后内容会画到状态栏/挖孔下面，
            // 必须由这里补顶部 inset，否则页面标题会被状态栏压住。
            // 用 safeDrawing（= 状态栏 ∪ 挖孔 的较大者），对应 HarmonyOS 端
            // Index/ScheduleView/XiaoQinView 里 safeTop 的语义。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            ) {
                when (currentTab) {
                    Tab.SCHEDULE -> ScheduleScreen(
                        currentWeek = vm.currentWeek,
                        realWeek = vm.realWeek,
                        headDateText = headDateText(),
                        termStartDateKey = vm.settings.termStartDate,
                        timeSlots = vm.timeSlots,
                        courses = vm.scheduleCourses,
                        sideDateLabels = weekDateLabels(vm.termStartDate, vm.currentWeek),
                        monthLabel = monthLabel(vm.termStartDate, vm.currentWeek),
                        todayIndex = todayIndex(vm.termStartDate, vm.currentWeek, vm.realWeek),
                        restFlags = vm.restFlags,
                        workFlags = vm.workFlags,
                        lineWeightSolid = vm.settings.lineWeight == "solid",
                        onSwitchWeek = {
                            vm.hapticTick()
                            vm.switchWeek(it)
                        },
                        onOpenAdjust = { showAdjustPanel = true },
                        onCourseClick = {
                            previewCourse = it
                            showPreview = true
                        },
                        onQuickJumpEmpty = {
                            if (vm.currentWeek != vm.realWeek) vm.switchWeek(0)
                        }
                    )

                    Tab.CLASSROOM -> EmptyClassroomScreen(
                        cloudSource = vm.cloudSource,
                        onCloudSourceChange = { vm.updateCloudSource(it) },
                        dayOffset = vm.dayOffset,
                        onDayChange = { vm.setDay(it) },
                        slotIndex = vm.slotIndex,
                        onSlotChange = { vm.setSlot(it) },
                        loading = vm.loading,
                        progressText = vm.progressText,
                        errorMsg = vm.errorMsg,
                        result = vm.result,
                        cacheInfoText = vm.cacheInfoText,
                        sourceText = vm.sourceText,
                        warningText = vm.warningText,
                        bulkBusy = vm.bulkBusy,
                        cooldownLeft = vm.cooldownLeft,
                        onCancelCooldown = { vm.cancelCooldown() },
                        onQuery = { vm.doQuery() },
                        onBulkUpdate = { vm.updateRecentAll() },
                        onOpenSettings = { showClassroomSettings = true }
                    )

                    Tab.XIAOQIN -> XiaoQinScreen(
                        sessionModeLabel = vm.sessionModeLabel,
                        onWebLogin = {
                            webLoginMode = "webvpn"
                            showWebLogin = true
                        },
                        onLogout = { vm.logout() },
                        onOpenCalendar = { showCalendarPdf = true }
                    )
                }
            }

            // 底部导航
            BottomNavBar(
                current = currentTab,
                onSelect = { currentTab = it }
            )
        }

        // ===== 各对话框 =====
        if (showClassroomSettings) {
            ClassroomSettingsDialog(
                username = vm.username,
                password = vm.password,
                useWebvpn = vm.useWebvpn,
                authType = vm.authType,
                webvpnUsername = vm.webvpnUsername,
                webvpnPassword = vm.webvpnPassword,
                serverUrl = vm.serverUrl,
                onUsername = { vm.updateUsername(it) },
                onPassword = { vm.updatePassword(it) },
                onUseWebvpn = { vm.updateUseWebvpn(it) },
                onAuthType = { vm.updateAuthType(it) },
                onWebvpnUsername = { vm.updateWebvpnUsername(it) },
                onWebvpnPassword = { vm.updateWebvpnPassword(it) },
                onServerUrl = { vm.updateServerUrl(it) },
                onDismiss = { showClassroomSettings = false }
            )
        }

        if (showAdjustPanel) {
            AdjustPanelDialog(
                onImport = { showAdjustPanel = false; showImport = true },
                onTimes = { showAdjustPanel = false; showTimeSlots = true },
                onTerm = { showAdjustPanel = false; showTermStart = true },
                onCourses = { showAdjustPanel = false; showCourses = true },
                onFaq = { showAdjustPanel = false; showFaq = true },
                onDismiss = { showAdjustPanel = false }
            )
        }

        if (showFaq) {
            FaqDialog(onDismiss = { showFaq = false })
        }

        if (showImport) {
            ImportScheduleDialog(
                sessionReady = vm.sessionCookies.isNotEmpty(),
                busy = vm.importBusy,
                statusText = vm.importStatus,
                errorText = vm.importError,
                terms = vm.terms,
                onStartWebImport = {
                    if (vm.sessionCookies.isEmpty()) {
                        webLoginMode = "webvpn"
                        showWebLogin = true
                    } else {
                        vm.fetchTerms { _, _ -> }
                    }
                },
                onPickTerm = { id -> vm.importScheduleViaWeb(id) { _, _ -> } },
                onPickFile = {
                    showImport = false
                    // 触发文件选择（SAF 系统选择器）
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    try {
                        filePickLauncher.launch(intent)
                    } catch (e: Exception) {
                        toastMsg = "无法打开文件选择器"
                    }
                },
                onReload = { vm.fetchTerms { _, _ -> } },
                onDismiss = { showImport = false }
            )
        }

        if (showTimeSlots) {
            TimeSlotsDialog(
                slots = vm.timeSlots,
                onSlotChange = { i, s ->
                    val list = vm.timeSlots.toMutableList()
                    list[i] = s
                    vm.saveTimeSlots(list)
                },
                onAddSlot = { vm.addTimeSlot() },
                onRemoveSlot = { vm.removeTimeSlot(it) },
                onSave = {
                    vm.saveTimeSlots(vm.timeSlots)
                    showTimeSlots = false
                },
                onDismiss = { showTimeSlots = false }
            )
        }

        if (showTermStart) {
            TermStartDialog(
                currentKey = vm.settings.termStartDate,
                onPickDate = {
                    showTermStart = false
                    val cal = Calendar.getInstance().apply { time = vm.termStartDate }
                    val intent = android.app.DatePickerDialog(
                        context,
                        { _, y, m, d ->
                            val picked = Calendar.getInstance().apply {
                                clear()
                                set(y, m, d)
                            }.time
                            vm.setTermStart(picked)
                        },
                        cal.get(Calendar.YEAR),
                        cal.get(Calendar.MONTH),
                        cal.get(Calendar.DAY_OF_MONTH)
                    )
                    intent.datePicker.minDate = Calendar.getInstance().apply {
                        set(2020, 0, 1)
                    }.timeInMillis
                    intent.datePicker.maxDate = Calendar.getInstance().apply {
                        set(2032, 11, 31)
                    }.timeInMillis
                    intent.show()
                },
                onOpenCalendar = {
                    // Android WebView 不能渲染 PDF（校历文章页里挂的是
                    // <iframe src="...pdf">），改走专用 PDF 渲染页
                    showTermStart = false
                    showCalendarPdf = true
                },
                onDismiss = { showTermStart = false }
            )
        }

        if (showCourses) {
            CoursesManagerScreen(
                courses = vm.scheduleCourses,
                editingCourse = vm.scheduleCourses.firstOrNull { it.id == courseEditId },
                editMode = courseEditMode,
                formName = formName,
                formTeacher = formTeacher,
                formPosition = formPosition,
                formDay = formDay,
                formStart = formStart,
                formEnd = formEnd,
                formColor = formColor,
                formWeeks = formWeeks,
                slotCount = vm.timeSlots.size,
                onFormName = { formName = it },
                onFormTeacher = { formTeacher = it },
                onFormPosition = { formPosition = it },
                onFormDay = { formDay = it },
                onFormStart = { formStart = it; if (formEnd < it) formEnd = it },
                onFormEnd = { formEnd = it },
                onFormColor = { formColor = it },
                onToggleWeek = { w ->
                    formWeeks = if (w in formWeeks) formWeeks - w else formWeeks + w
                },
                onApplyWeekPreset = { key ->
                    formWeeks = when (key) {
                        "all" -> (1..20).toList()
                        "odd" -> (1..20 step 2).toList()
                        "even" -> (2..20 step 2).toList()
                        "first" -> (1..10).toList()
                        "last" -> (11..20).toList()
                        "single" -> listOf(vm.currentWeek.coerceIn(1, 20))
                        else -> formWeeks
                    }
                },
                onStartAdd = { startAddCourse() },
                onStartEdit = { idx -> vm.scheduleCourses.getOrNull(idx)?.let { startEditCourse(it) } },
                onDelete = { vm.deleteCourse(it) },
                onSave = {
                    if (formName.isBlank()) {
                        toastMsg = "请输入课程名称"
                    } else if (formWeeks.isEmpty()) {
                        toastMsg = "请选择上课周次"
                    } else {
                        val id = if (courseEditMode == "edit" && courseEditId.isNotEmpty()) {
                            courseEditId
                        } else {
                            "c${System.currentTimeMillis()}"
                        }
                        vm.addOrUpdateCourse(
                            com.yuruan.neuqassist.data.CourseItem(
                                id = id,
                                name = formName.trim(),
                                teacher = formTeacher.trim(),
                                position = formPosition.trim(),
                                day = formDay,
                                startSection = formStart,
                                endSection = formEnd,
                                colorIndex = formColor,
                                weeks = formWeeks.sorted(),
                                remark = ""
                            )
                        )
                        courseEditMode = ""
                        toastMsg = "已保存"
                    }
                },
                onBack = {
                    if (courseEditMode.isNotEmpty()) courseEditMode = "" else showCourses = false
                }
            )
        }

        // 课程预览
        previewCourse?.let { course ->
            if (showPreview) {
                androidx.compose.ui.window.Dialog(onDismissRequest = { showPreview = false }) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                        color = Color.White
                    ) {
                        CoursePreviewSheet(
                            course = course,
                            currentWeek = vm.currentWeek,
                            slotTimeText = { s, e ->
                                if (s in 1..vm.timeSlots.size && e in 1..vm.timeSlots.size && s <= e) {
                                    "第$s-$e 节 ${vm.timeSlots[s - 1].startTime}~${vm.timeSlots[e - 1].endTime}"
                                } else "第$s-$e 节"
                            },
                            onEdit = {
                                showPreview = false
                                showCourses = true
                                startEditCourse(course)
                            },
                            onDismiss = { showPreview = false }
                        )
                    }
                }
            }
        }

        // Toast
        if (toastMsg.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 80.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Surface(color = Color(0xCC000000), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        text = toastMsg,
                        fontSize = 13.sp,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
            // 3 秒后自动消失
            androidx.compose.runtime.LaunchedEffect(toastMsg) {
                kotlinx.coroutines.delay(3000)
                toastMsg = ""
            }
        }
    }
}

/** 调整面板宫格对话框 */
@Composable
private fun AdjustPanelDialog(
    onImport: () -> Unit,
    onTimes: () -> Unit,
    onTerm: () -> Unit,
    onCourses: () -> Unit,
    onFaq: () -> Unit,
    onDismiss: () -> Unit
) {
    val items = listOf(
        "📥" to "导入课表",
        "⏰" to "上课时间",
        "📅" to "学期起点",
        "📚" to "课程管理",
        "❓" to "常见问题"
    )
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("调整", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(modifier = Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { (icon, title) ->
                                Surface(
                                    onClick = {
                                        when (title) {
                                            "导入课表" -> onImport()
                                            "上课时间" -> onTimes()
                                            "学期起点" -> onTerm()
                                            "课程管理" -> onCourses()
                                            else -> onFaq()
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    color = Color(0xFFF5F6F8),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(icon, fontSize = 26.sp)
                                        Text(title, fontSize = 14.sp, color = TextPrimary)
                                    }
                                }
                            }
                            if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** 底部导航栏（对应原端 Tabs barPosition.End） */
@Composable
private fun BottomNavBar(current: Tab, onSelect: (Tab) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xE6FFFFFF),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Tab.entries.forEach { tab ->
                val selected = current == tab
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable { onSelect(tab) },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = tab.title,
                        fontSize = if (selected) 15.sp else 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) PrimaryBlue else Color(0x99182431)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // 选中态指示条：宽度随选中状态变化（0 → 20%）
                    val fraction = if (selected) 0.2f else 0f
                    Box(
                        modifier = Modifier
                            .height(3.dp)
                            .fillMaxWidth(fraction)
                            .background(PrimaryBlue, RoundedCornerShape(2.dp))
                    )
                }
            }
        }
    }
}

// ===== 头部元数据计算（对应原端 refreshWeekMeta） =====

private fun headDateText(): String {
    val now = Calendar.getInstance()
    return "${now.get(Calendar.YEAR)}/${now.get(Calendar.MONTH) + 1}/${now.get(Calendar.DAY_OF_MONTH)}"
}

private fun weekDateLabels(termStart: java.util.Date, week: Int): List<String> {
    val dates = ScheduleViewUtil.weekDates(termStart, week)
    return dates.map {
        val cal = Calendar.getInstance().apply { time = it }
        "${cal.get(Calendar.MONTH) + 1}/${cal.get(Calendar.DAY_OF_MONTH)}"
    }
}

private fun monthLabel(termStart: java.util.Date, week: Int): String {
    val dates = ScheduleViewUtil.weekDates(termStart, week)
    if (dates.isEmpty()) return ""
    val cal = Calendar.getInstance().apply { time = dates[0] }
    return "${cal.get(Calendar.MONTH) + 1}月"
}

private fun todayIndex(termStart: java.util.Date, week: Int, realWeek: Int): Int {
    if (week != realWeek) return -1
    val w = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
    return if (w == Calendar.SUNDAY) 6 else w - Calendar.MONDAY
}
