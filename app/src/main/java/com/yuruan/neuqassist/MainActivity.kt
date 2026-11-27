package com.yuruan.neuqassist

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
        setContent {
            // 主题强调色由个性化设置驱动（对应 hap AppStorage 的 themePrimary）：
            // 切换预设时整个 setContent 重组，底栏选中态 / 按钮 / 滑块 / 课表今日态全部联动
            val vm: MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
            NEUQTheme(primary = vm.themePrimary) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFF2F3F5)
                ) {
                    MainScreen(onExitApp = { finish() }, vm = vm)
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
    // 底部栏目：冷启动首帧取「个性化设置 → 默认启动栏目」；进程内切栏不回写偏好。
    // rememberSaveable 保证旋转 / 进程重建时恢复用户当次所在栏目（存恢复态），
    // 与持久化的默认栏目互不覆盖（对应 hap 隐私闸门放行时一次性应用 launchTab 的语义）
    var currentTabIndex by rememberSaveable {
        mutableIntStateOf(vm.settings.defaultLaunchTab)
    }
    val currentTab = Tab.entries[currentTabIndex.coerceIn(0, Tab.entries.size - 1)]
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
    var pendingEmptyImport by remember { mutableStateOf<com.yuruan.neuqassist.data.CourseFileImport?>(null) }
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
    var formRemark by remember { mutableStateOf("") }
    var formSingleMode by remember { mutableStateOf(false) }
    var formSingleTaps by remember { mutableIntStateOf(0) }

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
        formRemark = course.remark
        formSingleMode = course.weeks.size == 1
        formSingleTaps = 0
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
        formRemark = ""
        formSingleMode = false
        formSingleTaps = 0
    }

    // ===== 隐私闸门 =====
    // 与 hap MainPage / MainPageImmersive 一致：闸门页也衬全局背景（含保护层）
    if (!vm.privacyAgreed) {
        Box(modifier = Modifier.fillMaxSize()) {
            AppBackdrop(
                bgEnabled = vm.bgEnabled,
                bgImagePath = vm.bgImagePath,
                bgDim = vm.bgDim,
                bgVersion = vm.bgVersion
            )
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
            onSessionExtracted = { extractedMode, cookies ->
                vm.onWebLoginSuccess(extractedMode, cookies)
                showWebLogin = false
                toastMsg = "登录会话已获取，可直接查询"
                if (showImport && !vm.importBusy) {
                    vm.fetchTerms { _, _ -> }
                }
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
        BackHandler { showCalendarPdf = false }
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
                val parsed = vm.parseCourseFile(text)
                if (parsed == null) {
                    toastMsg = "未能从文件中识别到课表，请确认选择的是教务「导出」的学生课表文件"
                } else if (parsed.courses.isEmpty()) {
                    pendingEmptyImport = parsed
                } else {
                    vm.applyParsedCourseFile(parsed) { ok, msg ->
                        if (ok) showImport = false
                        toastMsg = msg
                    }
                }
            } catch (e: Exception) {
                toastMsg = "读取文件失败: ${e.message}"
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 全局背景层（最底）：自定义图 / 内置图 + 浅色渐变保护层，
        // 各页根布局改为透明后由此透出（对应 hap Backdrop + 各页保护层）
        AppBackdrop(
            bgEnabled = vm.bgEnabled,
            bgImagePath = vm.bgImagePath,
            bgDim = vm.bgDim,
            bgVersion = vm.bgVersion
        )
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
                        lineWeightSolid = vm.lineWeightSolid,
                        fontScaleKey = vm.scheduleFontScale,
                        cardRadiusDp = vm.scheduleCardRadius,
                        rowHeightKey = vm.scheduleRowHeight,
                        autoHideWeekend = vm.autoHideWeekend,
                        hideTeacher = vm.hideCourseTeacher,
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
                            if (vm.currentWeek != vm.realWeek) {
                                vm.hapticTick()
                                vm.jumpToRealWeek()
                            }
                        }
                    )

                    Tab.CLASSROOM -> EmptyClassroomScreen(
                        cloudSource = vm.cloudSource,
                        onCloudSourceChange = { vm.updateCloudSource(it) },
                        sessionReady = vm.sessionCookies.isNotEmpty() &&
                            ((vm.sessionMode == "webvpn") == vm.useWebvpn),
                        onGoLogin = {
                            webLoginMode = if (vm.sessionMode == "eams") "eams" else "webvpn"
                            showWebLogin = true
                        },
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
                        onOpenSettings = { showClassroomSettings = true },
                        onRoomClick = { room ->
                            toastMsg = if (room.equipmentConfig.isEmpty()) {
                                room.name
                            } else {
                                "${room.name} · ${room.equipmentConfig}"
                            }
                        }
                    )

                    Tab.XIAOQIN -> XiaoQinScreen(
                        sessionModeLabel = vm.sessionModeLabel,
                        vm = vm,
                        onWebLogin = {
                            webLoginMode = if (vm.sessionMode == "eams") "eams" else "webvpn"
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
                onSelect = { currentTabIndex = it.ordinal }
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
                    if (vm.sessionCookies.isEmpty() ||
                        vm.importError.contains("过期") ||
                        vm.importError.contains("登录")
                    ) {
                        webLoginMode = if (vm.sessionMode == "eams") "eams" else "webvpn"
                        showWebLogin = true
                    } else {
                        vm.fetchTerms { _, _ -> }
                    }
                },
                onWebLogin = {
                    webLoginMode = if (vm.sessionMode == "eams") "eams" else "webvpn"
                    showWebLogin = true
                },
                onPickTerm = { id ->
                    vm.importScheduleViaWeb(id) { ok, msg ->
                        if (ok) {
                            showImport = false
                            toastMsg = msg
                        }
                    }
                },
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
            var draftSlots by remember { mutableStateOf(vm.timeSlots) }
            TimeSlotsDialog(
                slots = draftSlots,
                onSlotChange = { i, s ->
                    val list = draftSlots.toMutableList()
                    list[i] = s
                    draftSlots = list
                },
                onAddSlot = {
                    if (draftSlots.size < 15) {
                        val last = draftSlots.last()
                        val lastEnd = ScheduleViewUtil.toMinutes(last.endTime)
                        var start = if (lastEnd >= 0) lastEnd + 10 else 8 * 60
                        if (start + 45 > 1440) start = 8 * 60
                        draftSlots = draftSlots + com.yuruan.neuqassist.data.TimeSlot(
                            ScheduleViewUtil.fromMinutes(start),
                            ScheduleViewUtil.fromMinutes(start + 45)
                        )
                    }
                },
                onRemoveSlot = { idx ->
                    if (draftSlots.size > 1) {
                        draftSlots = draftSlots.filterIndexed { i, _ -> i != idx }
                    }
                },
                onSave = {
                    val invalid = draftSlots.any {
                        !ScheduleViewUtil.isValidTime(it.startTime) ||
                            !ScheduleViewUtil.isValidTime(it.endTime)
                    }
                    if (invalid) {
                        toastMsg = "时间格式不正确，请使用 24 小时制 HH:MM（如 08:00）"
                    } else {
                        vm.saveTimeSlots(draftSlots)
                        showTimeSlots = false
                        toastMsg = "节次时间已保存"
                    }
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
            BackHandler {
                if (courseEditMode.isNotEmpty()) courseEditMode = "" else showCourses = false
            }
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
                formSingleMode = formSingleMode,
                slotCount = vm.timeSlots.size,
                onFormName = { formName = it },
                onFormTeacher = { formTeacher = it },
                onFormPosition = { formPosition = it },
                onFormDay = { formDay = it },
                onFormStart = { formStart = it; if (formEnd < it) formEnd = it },
                onFormEnd = { formEnd = it },
                onFormColor = { formColor = it },
                onToggleWeek = { w ->
                    val selected = w in formWeeks
                    if (formSingleMode) {
                        if (selected) {
                            formSingleMode = false
                        } else if (formSingleTaps == 0) {
                            formWeeks = listOf(w)
                            formSingleTaps = 1
                            return@CoursesManagerScreen
                        } else {
                            formSingleMode = false
                        }
                    }
                    formWeeks = if (selected) formWeeks - w else formWeeks + w
                },
                onApplyWeekPreset = { key ->
                    if (key == "single") {
                        formWeeks = listOf(vm.currentWeek.coerceIn(1, 20))
                        formSingleMode = true
                        formSingleTaps = 0
                    } else {
                        formSingleMode = false
                        formSingleTaps = 0
                        formWeeks = when (key) {
                            "all" -> (1..20).toList()
                            "odd" -> (1..20 step 2).toList()
                            "even" -> (2..20 step 2).toList()
                            "first" -> (1..10).toList()
                            "last" -> (11..20).toList()
                            else -> formWeeks
                        }
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
                        val isEdit = courseEditMode == "edit" && courseEditId.isNotEmpty()
                        val id = if (isEdit) courseEditId else "c${System.currentTimeMillis()}"
                        val preservedRemark = if (isEdit) formRemark else ""
                        vm.addOrUpdateCourse(
                            com.yuruan.neuqassist.data.CourseItem(
                                id = id,
                                name = formName.trim(),
                                teacher = formTeacher.trim(),
                                position = formPosition.trim(),
                                day = formDay,
                                startSection = formStart,
                                endSection = maxOf(formStart, formEnd),
                                colorIndex = formColor,
                                weeks = formWeeks.sorted(),
                                remark = preservedRemark
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

        pendingEmptyImport?.let { emptyImport ->
            val sem = emptyImport.semesterName
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pendingEmptyImport = null },
                title = { Text("导入空课表？") },
                text = {
                    Text(
                        "已识别到${if (sem.isEmpty()) "" else "「$sem」"}课表网格，" +
                            "但其中没有课程记录（0 门课程）。\n\n" +
                            "确认导入会清空当前课表（含手动添加的课程），且不可恢复。"
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        pendingEmptyImport = null
                        vm.applyParsedCourseFile(emptyImport) { ok, msg ->
                            if (ok) showImport = false
                            toastMsg = msg
                        }
                    }) { Text("确认导入") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        pendingEmptyImport = null
                    }) { Text("取消") }
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
                    .navigationBarsPadding()
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
        com.yuruan.neuqassist.R.drawable.ic_adjust_import to "导入课表",
        com.yuruan.neuqassist.R.drawable.ic_adjust_times to "上课时间",
        com.yuruan.neuqassist.R.drawable.ic_adjust_term to "学期起点",
        com.yuruan.neuqassist.R.drawable.ic_adjust_courses to "课程管理",
        com.yuruan.neuqassist.R.drawable.ic_adjust_faq to "常见问题"
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
                                        Image(
                                            painter = painterResource(icon),
                                            contentDescription = title,
                                            modifier = Modifier.size(30.dp)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
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

/**
 * 全局背景层（对应 hap MainPageImmersive.Backdrop + 各页保护层，三态）：
 *   ① 启用 + 自定义背景存在 → 自定义图铺满（已复制进应用沙箱，无临时授权问题）
 *   ② 启用（无自定义 / 自定义文件已失效）→ 内置背景图（与 hap 同源素材 bg_scene）
 *   ③ 关闭 → 不画图，仅保护层（完全不透明，等同无背景）
 * 保护层为浅色渐变（#F2F3F5 → #E9EBF8），不透明度 = 背景强度（0.6-1）：
 * 值越大层越实，深色正文在任意背景（含照片纹理）上都有稳定对比度。
 */
@Composable
private fun AppBackdrop(
    bgEnabled: Boolean,
    bgImagePath: String,
    bgDim: Float,
    bgVersion: Long = 0L
) {
    // 自定义图按需取样解码（大图降采样防 OOM）；路径或版本号变更 / 文件失效时重新解码
    var customImage by remember(bgImagePath, bgVersion) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(bgImagePath, bgVersion) {
        if (bgImagePath.isEmpty()) {
            customImage = null
            return@LaunchedEffect
        }
        val decoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            decodeSampledBitmap(java.io.File(bgImagePath))
        }
        customImage = decoded?.asImageBitmap()
    }

    if (bgEnabled) {
        val img = customImage
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            // 无自定义图 / 自定义失效（解码为空）→ 内置默认背景兜底
            Image(
                painter = painterResource(com.yuruan.neuqassist.R.drawable.bg_scene),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }

    // 内容保护层：透明度由「个性化设置 → 背景强度」控制（关闭背景时完全不透明）
    val layerAlpha = if (bgEnabled) bgDim.coerceIn(0.6f, 1f) else 1f
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFFF2F3F5).copy(alpha = layerAlpha),
                        Color(0xFFE9EBF8).copy(alpha = layerAlpha)
                    )
                )
            )
    )
}

/**
 * 自定义背景取样解码：先读尺寸再按屏幕约束降采样，失败（文件损坏 / 已删除 /
 * 内存不足）返回 null，由调用方回退内置背景，绝不抛出阻断渲染。
 */
private fun decodeSampledBitmap(file: java.io.File): android.graphics.Bitmap? {
    if (!file.exists() || !file.isFile) return null
    return try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDim / (sample * 2) >= 1080) sample *= 2
        android.graphics.BitmapFactory.decodeFile(
            file.absolutePath,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        )
    } catch (e: Throwable) {
        // 损坏图片 / OOM 等：按文件失效处理（回退内置背景），不阻断渲染
        com.yuruan.neuqassist.data.Logger.warn("自定义背景解码失败，回退内置背景: ${e.message}")
        null
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
                        // 选中态强调色跟随主题预设（个性化设置实时联动）
                        color = if (selected) MaterialTheme.colorScheme.primary else Color(0x99182431)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // 选中态指示条：宽度随选中状态变化（0 → 20%）
                    val fraction = if (selected) 0.2f else 0f
                    Box(
                        modifier = Modifier
                            .height(3.dp)
                            .fillMaxWidth(fraction)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
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
