package com.yuruan.neuqassist.ui

import android.app.Application
import android.content.Context
import android.webkit.CookieManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yuruan.neuqassist.data.AppConfig
import com.yuruan.neuqassist.data.AppError
import com.yuruan.neuqassist.data.ClassroomService
import com.yuruan.neuqassist.data.CloudCache
import com.yuruan.neuqassist.data.CloudCacheData
import com.yuruan.neuqassist.data.CloudService
import com.yuruan.neuqassist.data.CourseItem
import com.yuruan.neuqassist.data.CourseFileImport
import com.yuruan.neuqassist.data.CourseFileParser
import com.yuruan.neuqassist.data.DateUtil
import com.yuruan.neuqassist.data.Keys
import com.yuruan.neuqassist.data.Logger
import com.yuruan.neuqassist.data.Personalize
import com.yuruan.neuqassist.data.ProcessedClassroomData
import com.yuruan.neuqassist.data.QueryResult
import com.yuruan.neuqassist.data.ScheduleService
import com.yuruan.neuqassist.data.SettingsStore
import com.yuruan.neuqassist.data.SecretStore
import com.yuruan.neuqassist.data.TermInfo
import com.yuruan.neuqassist.data.TimeSlot
import com.yuruan.neuqassist.data.HolidayService
import com.yuruan.neuqassist.data.HapticService
import com.yuruan.neuqassist.data.ScheduleViewUtil
import com.yuruan.neuqassist.net.ClassroomQueryConfig
import com.yuruan.neuqassist.net.WebvpnCrypto
import com.yuruan.neuqassist.net.WebvpnSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 主 ViewModel —— 编排各页业务逻辑（对应 HarmonyOS 端 Index.ets / ScheduleView.ets /
 * XiaoQinView.ets / MainPage.ets 的 aboutToAppear / doQuery / updateRecentAll 等）
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val context: Context = app.applicationContext
    val settings = SettingsStore(context)
    private val secrets = SecretStore.get(context)

    // ===== 隐私闸门 =====
    var privacyAgreed by mutableStateOf(false)
        private set

    // ===== 空教室页状态（对应 Index.ets） =====
    var cloudSource by mutableStateOf(settings.cloudSource)
    var dayOffset by mutableStateOf(0)
    var slotIndex by mutableStateOf(AppConfig.nearestTimeSlotIndex())
    var loading by mutableStateOf(false)
    var progressText by mutableStateOf("")
    var errorMsg by mutableStateOf("")
    var result by mutableStateOf<QueryResult?>(null)
    var cacheInfoText by mutableStateOf("")
    var sourceText by mutableStateOf("")
    var warningText by mutableStateOf("")
    var bulkBusy by mutableStateOf(false)
    var cooldownLeft by mutableStateOf(0)

    // 账号设置（对应 Index.ets settingsPanel）
    var username by mutableStateOf(settings.username)
    var password by mutableStateOf(secrets.read(Keys.ALIAS_JWXT_PASSWORD))
    var useWebvpn by mutableStateOf(settings.useWebvpn)
    var authType by mutableStateOf(settings.authType)
    var webvpnUsername by mutableStateOf(settings.webvpnUsername)
    var webvpnPassword by mutableStateOf(secrets.read(Keys.ALIAS_WEBVPN_PASSWORD))
    var serverUrl by mutableStateOf(settings.serverUrl)

    // 会话（网页登录）
    var sessionCookies by mutableStateOf(settings.sessionCookies)
    var sessionMode by mutableStateOf(settings.sessionMode)

    private var service: ClassroomService? = null
    private var webChannelFailed = false
    private var querySeq = 0

    /** 实时查询隐形 WebView 的预热状态（与鸿蒙端 eamsWarmBase / eamsWarmAt 一致） */
    private var eamsWarmBase = ""
    private var eamsWarmAt = 0L

    // ===== 课表页状态（对应 ScheduleView.ets） =====
    var termStartDate by mutableStateOf(parseTermStart(settings.termStartDate))
    var realWeek by mutableStateOf(ScheduleViewUtil.computeRealWeek(termStartDate))
        private set
    var currentWeek by mutableStateOf(realWeek)
    var timeSlots by mutableStateOf(settings.loadTimeSlots())
    var scheduleCourses by mutableStateOf(settings.loadCourses())
    var restFlags by mutableStateOf(List(7) { false })
    var workFlags by mutableStateOf(List(7) { false })
    var importBusy by mutableStateOf(false)
    var importError by mutableStateOf("")
    var importStatus by mutableStateOf("")
    var terms by mutableStateOf<List<TermInfo>>(emptyList())

    private val holidayService = HolidayService()
    private var holidaySeq = 0

    /** 触感反馈（切周 / 时间滚轮），设备不支持时静默降级 */
    private val haptics: HapticService? = try {
        HapticService(context)
    } catch (e: Exception) {
        null
    }

    // ===== 小秦帮页状态（对应 XiaoQinView.ets） =====
    var sessionModeLabel by mutableStateOf("")

    // ===== 个性化中心状态（对应 hap common/Personalize.ets 的 AppStorage 联动） =====
    // 读取侧已在 SettingsStore 内做合法值校验（损坏值回退默认），这里直接持有 Compose 状态
    /** 主题预设键（'blue' | 'orange' | 'green' | 'purple'） */
    var themePreset by mutableStateOf(settings.themePreset)
        private set
    /** 课表文字大小档位（'small' | 'standard' | 'large'） */
    var scheduleFontScale by mutableStateOf(settings.scheduleFontScale)
        private set
    /** 课表卡片圆角（dp，2-16） */
    var scheduleCardRadius by mutableStateOf(settings.scheduleCardRadius)
        private set
    /** 冷启动默认栏目（仅影响下次冷启动，切换栏目不回写） */
    var defaultLaunchTab by mutableStateOf(settings.defaultLaunchTab)
        private set
    /** 是否启用背景（默认启用内置背景图） */
    var bgEnabled by mutableStateOf(settings.bgEnabled)
        private set
    /** 自定义背景沙箱路径（空 = 内置默认背景） */
    var bgImagePath by mutableStateOf(settings.bgImagePath)
        private set
    /** 自定义背景版本号（覆写同一文件 bg_custom.jpg 时递增以触发解码刷新） */
    var bgVersion by mutableStateOf(0L)
        private set
    /** 背景强度（内容保护层不透明度，0.6-1）：越大文字越清晰、背景越淡 */
    var bgDim by mutableStateOf(settings.bgDim)
        private set
    /** 课表网格线是否加重（'solid'=加重，背景较花时更清晰） */
    var lineWeightSolid by mutableStateOf(settings.lineWeight == "solid")
        private set

    /** 当前主题强调色（由预设派生，不落盘；对应 hap AppStorage 的 themePrimary） */
    val themePrimary: Color
        get() = Color(Personalize.themeColor(themePreset))

    // ===== 底部导航 =====
    var currentTab by mutableStateOf(0)

    // ===== WebView 网页登录结果 =====
    var pendingWebSession by mutableStateOf<Pair<String, String>?>(null)

    init {
        // 自定义背景文件失效自愈（对应 hap 的文件失效场景）：路径还在但沙箱文件
        // 已不存在（如被系统清理）时清掉引用，避免残留死路径；UI 层另有兜底渲染
        if (bgImagePath.isNotEmpty() && !java.io.File(bgImagePath).exists()) {
            bgImagePath = ""
            settings.bgImagePath = ""
        }
        viewModelScope.launch {
            privacyAgreed = settings.isPrivacyAgreed()
            if (privacyAgreed) {
                loadInitialData()
            }
        }
    }

    // ===== 隐私闸门 =====

    fun agreePrivacy() {
        privacyAgreed = true
        settings.setPrivacyAgreed(true)
        viewModelScope.launch { loadInitialData() }
    }

    fun declinePrivacy() {
        // 退出应用由 Activity 处理
        privacyAgreed = false
    }

    private suspend fun loadInitialData() {
        val seq = querySeq
        val useCloud = cloudSource
        // 打开即显：云端模式读 cloud_cache.txt，实时模式读 local_cache.txt，互不混用
        try {
            val cached = if (useCloud) CloudCache.load(context) else CloudCache.loadLocal(context)
            if (cached != null && querySeq == seq && !loading && cloudSource == useCloud) {
                val slotKey = currentSlotKey()
                val stale = !CloudCache.isFresh(cached)
                val hit = CloudService.resultFromCache(
                    cached, dayOffset, slotKey, cacheDayShift(cached), stale
                )
                if (hit != null && querySeq == seq && !loading && cloudSource == useCloud) {
                    result = hit
                    cacheInfoText = "${if (useCloud) "云端缓存" else "本地缓存"} · 更新于 ${cached.updateTime}"
                }
            }
        } catch (e: Exception) {
            com.yuruan.neuqassist.data.Logger.warn("缓存展示失败: ${e.message}")
        }
        realWeek = ScheduleViewUtil.computeRealWeek(termStartDate)
        currentWeek = realWeek
        refreshWeekMeta()
        sessionModeLabel = when (sessionMode) {
            "" -> "未登录"
            "webvpn" -> "已登录（WebVPN 会话）"
            else -> "已登录（教务直连会话）"
        }
    }

    // ===== 空教室查询 =====

    private fun currentSlotKey(): String =
        if (slotIndex >= AppConfig.TIME_SLOTS.size) "allday"
        else AppConfig.TIME_SLOTS[slotIndex].fileSuffix

    fun selectTab(index: Int) {
        currentTab = index
    }

    fun setDay(offset: Int) {
        dayOffset = offset
        if (cloudSource && !loading) doQuery()
    }

    fun setSlot(index: Int) {
        slotIndex = index
        if (cloudSource && !loading) doQuery()
    }

    // 说明：以下 updateXxx 命名刻意避开 `var xxx by mutableStateOf(...)` 生成的合成
    // setter（setXxx），否则会触发 JVM 签名冲突（Platform declaration clash）。
    fun updateCloudSource(value: Boolean) {
        cloudSource = value
        settings.cloudSource = value
    }

    fun updateUsername(v: String) { username = v; settings.username = v; resetService() }
    fun updatePassword(v: String) { password = v; secrets.save(Keys.ALIAS_JWXT_PASSWORD, v); resetService() }
    fun updateUseWebvpn(v: Boolean) { useWebvpn = v; settings.useWebvpn = v; resetService() }
    fun updateAuthType(v: String) { authType = v; settings.authType = v; resetService() }
    fun updateWebvpnUsername(v: String) { webvpnUsername = v; settings.webvpnUsername = v; resetService() }
    fun updateWebvpnPassword(v: String) { webvpnPassword = v; secrets.save(Keys.ALIAS_WEBVPN_PASSWORD, v); resetService() }
    fun updateServerUrl(v: String) { serverUrl = v.trim(); settings.serverUrl = v.trim() }

    /** 设置变更后使旧客户端失效，下次查询用新配置重新登录 */
    private fun resetService() {
        service = null
        webChannelFailed = false
    }

    private fun buildConfig(): ClassroomQueryConfig {
        val cfg = ClassroomQueryConfig()
        cfg.username = this.username
        cfg.password = this.password
        cfg.webvpnEnabled = this.useWebvpn
        // WebVPN 凭证允许与教务系统凭证不同：留空时回退到教务账号密码
        cfg.webvpnUsername = if (this.webvpnUsername.isEmpty()) this.username
        else this.webvpnUsername
        cfg.webvpnPassword = if (this.webvpnPassword.isEmpty()) this.password
        else this.webvpnPassword
        cfg.webvpnAuthType = this.authType
        return cfg
    }

    /**
     * 经隐形 WebView 拉取 URL 的完整 HTML。
     * 原生 http 栈在部分运营商网络下会被中途重置，WebView 走系统
     * Chromium 网络栈不受影响（与 HarmonyOS 端 fetchViaWeb 同思路）。
     */
    private suspend fun fetchViaWeb(url: String): String =
        WebFetchHelper.fetch(context, url)

    /** 执行单时段 / 全天查询（对应 invokeQuery） */
    private suspend fun invokeQuery(svc: ClassroomService) {
        result = if (slotIndex >= AppConfig.TIME_SLOTS.size) {
            svc.queryAllDay(dayOffset) { msg -> progressText = msg }
        } else {
            svc.querySlot(dayOffset, AppConfig.TIME_SLOTS[slotIndex]) { msg ->
                progressText = msg
            }
        }
    }

    /** 云端缓存命中则直接展示（1 小时内有效，零联网；仅限云端数据模式）：返回是否命中 */
    private suspend fun tryServeFromCache(): Boolean {
        if (!cloudSource) return false
        return try {
            val cached = CloudCache.load(context) ?: return false
            if (!CloudCache.isFresh(cached)) return false
            val hit = CloudService.resultFromCache(
                cached,
                dayOffset,
                currentSlotKey(),
                dayShift = cacheDayShift(cached),
                stale = false
            ) ?: return false
            result = hit
            cacheInfoText = "本地缓存 · 更新于 ${cached.updateTime}"
            sourceText = "数据来源：云端缓存（每小时更新，本次未联网）"
            true
        } catch (e: Exception) {
            com.yuruan.neuqassist.data.Logger.warn("缓存命中判定失败: ${e.message}")
            false
        }
    }

    fun doQuery() {
        if (loading) return
        val seq = ++querySeq
        loading = true
        errorMsg = ""
        cacheInfoText = ""
        warningText = ""
        sourceText = ""
        result = null

        viewModelScope.launch {
            try {
                // 数据来源一：云端（neuq.tsiao.io，免登录，每小时更新，带本地缓存）
                if (cloudSource) {
                    // 仅云端数据模式下优先读取 1 小时内新鲜缓存（零联网）；实时查询绝不走缓存
                    if (tryServeFromCache()) {
                        loading = false
                        progressText = ""
                        return@launch
                    }
                    try {
                        val vpnCookies = if (sessionMode == "webvpn") sessionCookies else ""
                        val fetched = CloudService.fetchDay(
                            context, dayOffset, currentSlotKey(),
                            serverUrl, vpnCookies,
                            { url -> fetchViaWeb(url) },
                            { msg -> if (querySeq == seq) progressText = msg }
                        )
                        if (querySeq == seq) {
                            result = fetched
                            sourceText = if (serverUrl.isNotEmpty()) {
                                "数据来源：云端数据（自建服务器）"
                            } else {
                                "数据来源：云端数据（公共镜像，免登录·每小时更新）"
                            }
                        }
                    } catch (e: Exception) {
                        if (querySeq == seq) {
                            errorMsg = "${e.message}（云端来源，可切\"实时查询\"获取更精确数据）"
                        }
                    } finally {
                        loading = false
                        progressText = ""
                    }
                    return@launch
                }

                // 数据来源二：教务系统实时查询（需登录会话或账号密码）
                val hasUsableSession = sessionCookies.isNotEmpty() &&
                    (sessionMode == "webvpn") == useWebvpn
                if (!hasUsableSession && (username.isEmpty() || password.isEmpty())) {
                    loading = false
                    errorMsg = if (sessionCookies.isEmpty()) {
                        "请先到「小秦帮 → 教务登录」完成一次网页登录（默认通道无需账号密码）"
                    } else {
                        "当前会话是${if (sessionMode == "webvpn") "WebVPN" else "教务直连"}模式，" +
                            "与 WebVPN 开关不一致。请重新网页登录后再查询"
                    }
                    return@launch
                }
                val svc = service ?: ClassroomService(buildConfig()).also { service = it }
                if (hasUsableSession) svc.applyWebSession(sessionCookies)

                val useWebChannel = !webChannelFailed
                if (useWebChannel) {
                    svc.setWebQuery { date, b, e -> fetchViaWebEams(date, b, e) }
                } else {
                    svc.setWebQuery(null)
                }
                var effectiveWebChannel = useWebChannel
                try {
                    try {
                        invokeQuery(svc)
                    } catch (e: Exception) {
                        if (useWebChannel && username.isNotEmpty() && password.isNotEmpty()) {
                            webChannelFailed = true
                            effectiveWebChannel = false
                            svc.setWebQuery(null)
                            com.yuruan.neuqassist.data.Logger.warn("网页通道失败，回退直连: ${e.message}")
                            invokeQuery(svc)
                        } else throw e
                    }
                    sourceText = if (effectiveWebChannel) {
                        "数据来源：教务实时查询（网页通道）"
                    } else {
                        "数据来源：教务实时查询（直连方式）"
                    }
                } catch (e: Exception) {
                    val sessionMatch = sessionCookies.isNotEmpty() &&
                        (sessionMode == "webvpn") == useWebvpn
                    val diag = " 诊断[传输:${if (useWebvpn) "WebVPN代理" else "教务直连"}" +
                        "; 会话:${if (sessionCookies.isEmpty()) "无"
                        else if (sessionMode == "webvpn") "WebVPN" else "教务直连"}" +
                        "${if (sessionCookies.isEmpty()) "" else if (sessionMatch) "/已注入" else "/模式不匹配未注入"}" +
                        "; 账号:${if (username.isEmpty()) "未填" else "已填"}]"
                    errorMsg = "${e.message}$diag"
                    service = null
                } finally {
                    loading = false
                    progressText = ""
                }
            } catch (e: Exception) {
                errorMsg = e.message ?: "查询失败"
                loading = false
                progressText = ""
            }
        }
    }

    /** WebVPN 限流冷却剩余秒数轮询 */
    /**
     * WebVPN 限流冷却倒计时轮询（T-14b-2）。
     *
     * 只在 cooldownLeft > 0 期间轮询，避免无限循环空转；冷却结束后协程自行退出。
     */
    init {
        viewModelScope.launch {
            while (true) {
                val left = WebvpnSession.Cooldown.cooldownLeft
                cooldownLeft = left
                if (left <= 0) {
                    // 未进入冷却：降频为 1 秒一次（仅用于发现冷却开始）
                    delay(1000)
                } else {
                    delay(250)
                }
            }
        }
    }

    fun cancelCooldown() {
        WebvpnSession.Cooldown.cooldownCanceled = true
    }

    /**
     * 实时查询的 WebView 同源通道：隐形 WebView 加载教务页面（复用网页登录
     * 留下的全局 Cookie），注入同步 XHR 调用 free!search.action。
     * 与 HarmonyOS 端 eamsQueryViaWeb 同源。
     */
    private suspend fun fetchViaWebEams(date: String, timeBegin: Int, timeEnd: Int): String {
        val base = if (useWebvpn) {
            WebvpnCrypto.buildResourceUrl(
                AppConfig.DEFAULT_WEBVPN_BASE_URL,
                AppConfig.DEFAULT_BASE_URL
            )
        } else AppConfig.DEFAULT_BASE_URL

        // ① 先把已保存的网页会话 Cookie 回灌 WebView 的 CookieManager：
        //    进程回收 / Cookie 未及时落盘 / 只注入过原生客户端时，WebView 会以未登录
        //    身份访问教务页，被 302 到 CAS → 表现为「实时查询永远登录失效」。
        if (sessionCookies.isNotEmpty()) {
            val urls = if (useWebvpn) {
                listOf("${AppConfig.DEFAULT_WEBVPN_BASE_URL}/", "http://jwxt.neuq.edu.cn/")
            } else {
                listOf("http://jwxt.neuq.edu.cn/")
            }
            WebFetchHelper.seedCookies(sessionCookies, urls)
        }

        var where: String = WebFetchHelper.lastLoadedUrl ?: "未知"
        try {
            // ② 加载教务页面；10 分钟内且当前页面仍是同一 base 时复用，减少 WebVPN 请求
            val warmValid = eamsWarmBase == base &&
                (System.currentTimeMillis() - eamsWarmAt) < 10 * 60 * 1000L &&
                WebFetchHelper.lastLoadedUrl?.startsWith(base) == true
            if (!warmValid) {
                val page = fetchViaWeb("${base}homeExt.action")
                where = try {
                    WebFetchHelper.runJs("location.href").ifEmpty { where }
                } catch (e: Exception) {
                    where
                }
                if (page.isEmpty()) {
                    throw AppError.fetch(
                        "教务页面加载失败（30 秒内未完成；当前页面: $where）。请检查网络后重试，或重新网页登录"
                    )
                }
                if (isEamsLoginPage(page, where)) {
                    throw AppError.fetch("登录会话已失效（当前页面: $where），请重新网页登录")
                }
                eamsWarmBase = base
                eamsWarmAt = System.currentTimeMillis()
            } else {
                where = WebFetchHelper.lastLoadedUrl ?: where
            }

            // ③ 注入同步 XHR 查询（同源无跨域限制），返回 状态码|响应长度
            val body = "classroom.building.id=&" +
                "cycleTime.dateBegin=${java.net.URLEncoder.encode(date, "UTF-8")}&" +
                "cycleTime.dateEnd=${java.net.URLEncoder.encode(date, "UTF-8")}&" +
                "timeBegin=$timeBegin&timeEnd=$timeEnd&pageSize=1000&" +
                "classroom.type.id=&classroom.campus.id=&seats=&classroom.name=&" +
                "cycleTime.cycleCount=1&cycleTime.cycleType=1&roomApplyTimeType=0"
            val js = "(function(){" +
                "var x=new XMLHttpRequest();" +
                "x.open('POST','classroom/apply/free!search.action',false);" +
                "x.setRequestHeader('Content-Type','application/x-www-form-urlencoded');" +
                "x.send('$body');" +
                "window.__ncr = x.responseText || '';" +
                "return x.status + '|' + String((window.__ncr || '').length);" +
                "})()"
            val meta = WebFetchHelper.runJs(js).trim().trim('"')
            val parts = meta.split("|")
            val status = parts.getOrNull(0)?.toIntOrNull()
            val total = parts.getOrNull(1)?.toIntOrNull() ?: 0
            if (status == null || total <= 0) {
                throw AppError.fetch(
                    "教务页面内请求无响应（当前页面: $where；脚本返回: $meta），" +
                        "多半是会话已过期，请重新网页登录"
                )
            }
            val html = WebFetchHelper.readJsString("window.__ncr", total)
            if (html.contains("会话已经被过期") || !html.contains("gridtable")) {
                throw AppError.fetch(
                    "教务返回异常响应（状态码 $status，$total 字节；当前页面: $where），" +
                        "请重新网页登录后重试"
                )
            }
            return html
        } catch (e: Exception) {
            // 任何失败都作废预热，下一次强制重新加载页面
            eamsWarmBase = ""
            throw e
        }
    }

    /** 判定教务/网关页面是否落在登录页（比正文标记更早、更可靠） */
    private fun isEamsLoginPage(page: String, url: String): Boolean {
        if (page.contains("form['password']") || page.contains("casLoginForm")) return true
        val u = url.lowercase()
        return u.contains("/authserver/login") || u.contains("/do-login") ||
            u.contains("cas_login") || u.contains("/cas/login") ||
            u.contains("/eams/login")
    }

    /**
     * 一键更新近 5 天 × 6 个顺序时段（对应 updateRecentAll）：
     * 串行 + 1.2s 限速防教务风控 → 汇总写入本地缓存（覆盖上一次）→ 展示当前所选。
     */
    fun updateRecentAll() {
        if (loading) return
        val seq = ++querySeq
        loading = true
        bulkBusy = true
        errorMsg = ""
        cacheInfoText = ""
        warningText = ""

        viewModelScope.launch {
            val DAYS = 5
            try {
                val hasUsableSession = sessionCookies.isNotEmpty() &&
                    (sessionMode == "webvpn") == useWebvpn
                if (!hasUsableSession && (username.isEmpty() || password.isEmpty())) {
                    throw AppError.config("请先到「小秦帮 → 教务登录」完成一次网页登录")
                }
                val svc = service ?: ClassroomService(buildConfig()).also { service = it }
                if (hasUsableSession) svc.applyWebSession(sessionCookies)
                val useWebChannel = !webChannelFailed
                svc.setWebQuery(
                    if (useWebChannel) {
                        { date, b, e -> fetchViaWebEams(date, b, e) }
                    } else null
                )

                val rooms = mutableListOf<ProcessedClassroomData>()
                val roomDays = mutableListOf<Int>()
                val dayCounts = MutableList(DAYS) { 0 }
                var failed = 0
                for (d in 0 until DAYS) {
                    for (slotKey in AppConfig.SEQUENTIAL_SLOTS) {
                        val slot = AppConfig.TIME_SLOTS.firstOrNull { it.fileSuffix == slotKey }
                            ?: continue
                        progressText = "更新第 ${d + 1}/$DAYS 天 · ${slot.label}"
                        try {
                            val r = svc.querySlot(d, slot)
                            for (g in r.groups) {
                                for (room in g.rooms) {
                                    rooms.add(room)
                                    roomDays.add(d)
                                }
                                dayCounts[d] += g.rooms.size
                            }
                        } catch (e: Exception) {
                            failed++
                            val msg = e.message ?: ""
                            if (msg.contains("会话已失效") || msg.contains("会话已过期")) throw e
                            com.yuruan.neuqassist.data.Logger.warn("更新失败（第 ${d + 1} 天 ${slot.label}）: $msg")
                        }
                        delay(1200) // 限速：教务有「不要过快点击」风控
                    }
                }
                if (rooms.isEmpty()) {
                    throw AppError.fetch("未获取到任何数据（$failed 个时段失败）")
                }
                // 异常判定：空结果通常不是真实情况（节假日本应更空）
                val emptyDays = (0 until DAYS).filter { dayCounts[it] == 0 }.map { it + 1 }
                var warn = ""
                if (emptyDays.size >= 3) {
                    val list = emptyDays.joinToString("、")
                    val tailEmpty = dayCounts[DAYS - 3] == 0 &&
                        dayCounts[DAYS - 2] == 0 && dayCounts[DAYS - 1] == 0
                    warn = "第 $list 天未获取到任何教室（$DAYS 天中有 ${emptyDays.size} 天为空）。" +
                        (if (tailEmpty) "末尾连续多天为空属典型的抓取异常特征（教务风控限流或会话超时）；" else "") +
                        "节假日本应出现更多空闲教室，大面积为空通常不是真实情况，建议间隔几分钟后重新更新。"
                } else {
                    val nonZero = dayCounts.filter { it > 0 }
                    if (nonZero.size >= 3 && nonZero.all { it == nonZero[0] }) {
                        warn = "各天记录数完全一致（均为 ${nonZero[0]} 条），疑为教务返回了缓存旧数据" +
                            "（「不要过快点击」风控特征），建议间隔几分钟后重新更新。"
                    } else if (emptyDays.isNotEmpty()) {
                        warn = "第 ${emptyDays.joinToString("、")} 天未获取到教室，请留意是否为抓取异常。"
                    }
                }
                warningText = warn

                val stamp = DateUtil.stampNow()
                // 一键更新写入独立的本地离线缓存（local_cache.txt），绝不污染云端 cloud_cache.txt
                val data = CloudCacheData().apply {
                    updateTime = stamp
                    fetchedAt = System.currentTimeMillis()
                    this.rooms = rooms.toMutableList()
                    this.roomDays = roomDays.toMutableList()
                    buildIndex()
                }
                CloudCache.saveLocal(context, data)
                CloudService.clearResultCache()
                if (querySeq == seq) {
                    cacheInfoText = "本地缓存 · 更新于 $stamp"
                    sourceText = "数据来源：教务实时查询（网页通道）· 已保存到本地"

                    // 立即展示当前所选（用内存中的新数据，免再读盘）
                    CloudService.resultFromCache(
                        data, dayOffset, currentSlotKey(), dayShift = 0, stale = false
                    )?.let {
                        result = it
                    }
                }
            } catch (e: Exception) {
                errorMsg = "${e.message}（一键更新）"
                service = null
            } finally {
                loading = false
                bulkBusy = false
                progressText = ""
            }
        }
    }

    // ===== 网页登录会话 =====

    /**
     * 网页登录成功后调用：优先使用页面提取并传递来的 Cookie，缺失时兜底从 CookieManager 读取。
     * mode: "eams" 或 "webvpn"
     */
    fun onWebLoginSuccess(mode: String, extractedCookies: String = "") {
        val cookies = if (extractedCookies.isNotEmpty()) {
            extractedCookies
        } else {
            val cm = CookieManager.getInstance()
            cm.flush()
            val primaryUrl = if (mode == "webvpn") {
                "https://vpn.neuq.edu.cn/"
            } else {
                "http://jwxt.neuq.edu.cn/eams/homeExt.action"
            }
            var c = cm.getCookie(primaryUrl) ?: ""
            if (c.isEmpty() && mode != "webvpn") {
                c = cm.getCookie("http://jwxt.neuq.edu.cn/") ?: ""
            }
            c
        }
        if (cookies.isEmpty()) return
        sessionCookies = cookies
        sessionMode = mode
        useWebvpn = mode == "webvpn"
        settings.sessionCookies = cookies
        settings.sessionMode = mode
        settings.useWebvpn = mode == "webvpn"
        service = null
        webChannelFailed = false
        eamsWarmBase = ""
        errorMsg = ""
        importError = ""
        terms = emptyList()
        sessionModeLabel = if (mode == "webvpn") "已登录（WebVPN 会话）" else "已登录（教务直连会话）"
    }

    /** 退出登录 */
    fun logout() {
        sessionCookies = ""
        sessionMode = ""
        settings.sessionCookies = ""
        settings.sessionMode = ""
        service = null
        eamsWarmBase = ""
        sessionModeLabel = "未登录"
    }

    // ===== 课表 =====

    private fun parseTermStart(raw: String): java.util.Date {
        val parts = raw.split("-")
        if (parts.size != 3) return defaultTermStart()
        return java.util.Calendar.getInstance().apply {
            clear()
            set(parts[0].toIntOrNull() ?: 2026, (parts[1].toIntOrNull() ?: 8) - 1, parts[2].toIntOrNull() ?: 31)
        }.time
    }

    private fun defaultTermStart(): java.util.Date =
        java.util.Calendar.getInstance().apply {
            clear()
            set(2026, 7, 31) // 2026-08-31 周一
        }.time

    fun refreshWeekMeta() {
        realWeek = ScheduleViewUtil.computeRealWeek(termStartDate)
        if (currentWeek !in 1..20) currentWeek = realWeek
        val seq = ++holidaySeq
        val dates = ScheduleViewUtil.weekDates(termStartDate, currentWeek)
        // 先用已有缓存同步刷新当前周，避免网络等待期间残留上一周的休/班徽标
        val cachedFlags = holidayService.getFlags(dates)
        restFlags = cachedFlags.restFlags
        workFlags = cachedFlags.workFlags

        viewModelScope.launch {
            val years = dates.map {
                java.util.Calendar.getInstance().apply { time = it }.get(java.util.Calendar.YEAR)
            }.distinct()
            for (y in years) holidayService.getYearMap(y)
            if (seq != holidaySeq) return@launch
            val flags = holidayService.getFlags(dates)
            restFlags = flags.restFlags
            workFlags = flags.workFlags
        }
    }

    fun switchWeek(delta: Int) {
        val next = currentWeek + delta
        if (next < 1 || next > 20) return
        currentWeek = next
        refreshWeekMeta()
    }

    fun jumpToRealWeek() {
        realWeek = ScheduleViewUtil.computeRealWeek(termStartDate)
        if (currentWeek == realWeek) return
        currentWeek = realWeek
        refreshWeekMeta()
    }

    fun setTermStart(date: java.util.Date) {
        val monday = ScheduleViewUtil.mondayOf(date)
        termStartDate = monday
        settings.termStartDate = ScheduleViewUtil.toKey(monday)
        realWeek = ScheduleViewUtil.computeRealWeek(monday)
        currentWeek = realWeek
        refreshWeekMeta()
    }

    /** 切周触感反馈（对应原端 switchWeek 的 hapticService.tick()） */
    fun hapticTick() {
        haptics?.tick()
    }

    fun saveTimeSlots(slots: List<TimeSlot>) {
        timeSlots = slots
        settings.saveTimeSlots(slots)
    }

    fun addTimeSlot() {
        if (timeSlots.size >= 15) return
        val last = timeSlots.last()
        val lastEnd = ScheduleViewUtil.toMinutes(last.endTime)
        var start = if (lastEnd >= 0) lastEnd + 10 else 8 * 60
        if (start + 45 > 1440) start = 8 * 60
        timeSlots = timeSlots + TimeSlot(
            ScheduleViewUtil.fromMinutes(start),
            ScheduleViewUtil.fromMinutes(start + 45)
        )
    }

    fun removeTimeSlot(index: Int) {
        if (timeSlots.size <= 1) return
        timeSlots = timeSlots.filterIndexed { i, _ -> i != index }
    }

    fun saveCourses(courses: List<CourseItem>) {
        scheduleCourses = courses
        settings.saveCourses(courses)
    }

    fun addOrUpdateCourse(course: CourseItem) {
        val list = scheduleCourses.toMutableList()
        val idx = list.indexOfFirst { it.id == course.id }
        if (idx >= 0) list[idx] = course else list.add(course)
        saveCourses(list)
    }

    fun deleteCourse(id: String) {
        saveCourses(scheduleCourses.filter { it.id != id })
    }

    /** 解析教务「导出」文件，识别不到主网格返回 null */
    fun parseCourseFile(content: String): CourseFileImport? =
        try {
            CourseFileParser.parse(content)
        } catch (e: Exception) {
            Logger.warn("课表文件解析失败: ${e.message}")
            null
        }

    /** 应用已解析的导出课表（支持空课表确认后清空） */
    fun applyParsedCourseFile(parsed: CourseFileImport, onDone: (Boolean, String) -> Unit) {
        val courses = parsed.courses.toMutableList()
        ScheduleViewUtil.dedupeCourseIds(courses)
        saveCourses(courses)
        val sem = if (parsed.semesterName.isEmpty()) "" else "（${parsed.semesterName}）"
        val msg = if (courses.isEmpty()) {
            "已导入空课表$sem"
        } else {
            "导入成功：${courses.size} 门课程$sem"
        }
        onDone(true, msg)
    }

    /**
     * 从教务「导出」文件导入课表（免登录通道）
     * content: 文件文本内容
     */
    fun importFromFile(content: String, allowEmpty: Boolean = false, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val parsed = CourseFileParser.parse(content)
                if (parsed == null || (!allowEmpty && parsed.courses.isEmpty())) {
                    onDone(false, "未能从文件中识别到课表，请确认选择的是教务「导出」的学生课表文件")
                    return@launch
                }
                applyParsedCourseFile(parsed, onDone)
            } catch (e: Exception) {
                onDone(false, "文件导入失败: ${e.message}")
            }
        }
    }

    private fun buildScheduleService(): ScheduleService {
        val cfg = buildConfig()
        if (sessionMode.isNotEmpty()) {
            cfg.webvpnEnabled = sessionMode == "webvpn"
        }
        val svc = ScheduleService(cfg)
        if (sessionCookies.isNotEmpty()) {
            svc.applyWebSession(sessionCookies)
            svc.setWebTransport(
                getPage = { u -> WebFetchHelper.fetch(context, u) },
                postRelative = { rel, body -> WebFetchHelper.postXhr(rel, body) }
            )
        }
        return svc
    }

    /** 网页通道导入课表：走 WebView 同源注入（对应 openEngine + runTerms + pickTerm） */
    fun importScheduleViaWeb(semesterId: String, onDone: (Boolean, String) -> Unit) {
        importBusy = true
        importError = ""
        importStatus = "拉取课表并解析…"
        viewModelScope.launch {
            try {
                val svc = buildScheduleService()
                val termName = terms.firstOrNull { it.id == semesterId }?.name ?: ""
                val r = svc.fetchSchedule(semesterId, termName)
                val items = r.courses.mapIndexed { idx, c ->
                    CourseItem(
                        id = "${c.name}-${c.day}-${c.startSection}-${c.weeks.joinToString(".")}",
                        name = c.name,
                        teacher = c.teacher,
                        position = c.position,
                        day = c.day,
                        startSection = c.startSection,
                        endSection = c.endSection,
                        colorIndex = idx % 8,
                        weeks = c.weeks,
                        remark = ""
                    )
                }.toMutableList()
                ScheduleViewUtil.dedupeCourseIds(items)
                saveCourses(items)
                if (r.timeSlots.isNotEmpty()) saveTimeSlots(r.timeSlots)
                onDone(true, "导入成功：${items.size} 门课程${if (termName.isEmpty()) "" else "（$termName）"}")
            } catch (e: Exception) {
                importError = e.message ?: "导入失败"
                onDone(false, importError)
            } finally {
                importBusy = false
                importStatus = ""
            }
        }
    }

    /** 拉取学期列表（对应 runTerms） */
    fun fetchTerms(onDone: (Boolean, String) -> Unit) {
        importBusy = true
        importError = ""
        importStatus = "获取学期列表…"
        viewModelScope.launch {
            try {
                val svc = buildScheduleService()
                terms = svc.fetchTerms()
                importStatus = "共 ${terms.size} 个学期，请选择要导入的学期"
                onDone(true, "")
            } catch (e: Exception) {
                importError = e.message ?: "获取学期列表失败"
                onDone(false, importError)
            } finally {
                importBusy = false
            }
        }
    }

    // ===== 个性化设置更新（校验 + 状态实时生效 + 落盘） =====

    /** 切换主题预设（Personalize 内部完成校验；强调色由 themePrimary 派生实时联动） */
    fun updateThemePreset(key: String) {
        val k = Personalize.normalizeThemeKey(key)
        themePreset = k
        settings.themePreset = k
    }

    /** 切换课表字号档位 */
    fun updateScheduleFontScale(key: String) {
        val k = Personalize.normalizeFontScale(key)
        scheduleFontScale = k
        settings.scheduleFontScale = k
    }

    /** 设置课表卡片圆角：拖动过程仅更新状态（persist=false），松手才写盘 */
    fun updateScheduleCardRadius(v: Int, persist: Boolean = true) {
        scheduleCardRadius = Personalize.normalizeCardRadius(v)
        if (persist) settings.scheduleCardRadius = scheduleCardRadius
    }

    fun persistScheduleCardRadius() {
        settings.scheduleCardRadius = scheduleCardRadius
    }

    /** 切换冷启动默认栏目（仅影响下次冷启动，不做页面跳转） */
    fun updateDefaultLaunchTab(v: Int) {
        defaultLaunchTab = Personalize.normalizeLaunchTab(v)
        settings.defaultLaunchTab = defaultLaunchTab
    }

    /** 背景开关 */
    fun updateBgEnabled(v: Boolean) {
        bgEnabled = v
        settings.bgEnabled = v
    }

    /** 背景强度：拖动过程仅更新状态（persist=false），松手才写盘 */
    fun updateBgDim(v: Float, persist: Boolean = true) {
        bgDim = Personalize.normalizeBgDim(v)
        if (persist) settings.bgDim = bgDim
    }

    fun persistBgDim() {
        settings.bgDim = bgDim
    }

    /** 课表网格线加重开关 */
    fun updateLineWeightSolid(solid: Boolean) {
        lineWeightSolid = solid
        settings.lineWeight = if (solid) "solid" else "normal"
    }

    /** 应用自定义背景：先复制到应用沙箱再持久化引用（系统选择器授权是临时的） */
    fun applyCustomBackground(path: String) {
        bgImagePath = path
        bgEnabled = true
        bgVersion = System.currentTimeMillis()
        settings.bgImagePath = path
        settings.bgEnabled = true
    }

    /** 恢复内置默认背景（清理已复制的自定义文件） */
    fun resetBackground() {
        try {
            java.io.File(context.filesDir, Personalize.BG_CUSTOM_FILE).delete()
        } catch (e: Exception) {
            Logger.warn("清理自定义背景失败: ${e.message}")
        }
        bgImagePath = ""
        bgEnabled = true
        bgVersion = System.currentTimeMillis()
        settings.bgImagePath = ""
        settings.bgEnabled = true
    }

    /**
     * 恢复默认个性化设置：仅重置外观 / 偏好项并清理自定义背景文件。
     * ⚠️ 不触碰账号密码、登录会话、课程数据、节次时间、学期起点、缓存与隐私同意状态。
     */
    fun resetPersonalization() {
        Personalize.resetPersonalization(context, settings)
        themePreset = Personalize.DEFAULT_THEME_KEY
        scheduleFontScale = Personalize.DEFAULT_FONT_SCALE
        scheduleCardRadius = Personalize.DEFAULT_CARD_RADIUS
        defaultLaunchTab = Personalize.DEFAULT_LAUNCH_TAB
        bgEnabled = true
        bgImagePath = ""
        bgVersion = System.currentTimeMillis()
        bgDim = Personalize.DEFAULT_BG_DIM
        lineWeightSolid = Personalize.DEFAULT_LINE_WEIGHT == "solid"
    }

    // ===== 通用 =====

    /** 缓存基准日偏移：跨天后直接用今天的 dayOffset 匹配会展示错日期的数据 */
    private fun cacheDayShift(cached: com.yuruan.neuqassist.data.CloudCacheData): Int {
        if (cached.fetchedAt <= 0L) return 0
        val base = java.util.Calendar.getInstance().apply {
            timeInMillis = cached.fetchedAt
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val today = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val shift = Math.round((today - base) / 86400000.0).toInt()
        return if (shift < 0) 0 else shift
    }
}
