package com.yuruan.neuqassist.ui.screens

import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.yuruan.neuqassist.data.Keys
import com.yuruan.neuqassist.net.WebvpnFingerprint
import com.yuruan.neuqassist.ui.theme.Dimens
import com.yuruan.neuqassist.ui.theme.SurfaceLight
import com.yuruan.neuqassist.ui.theme.TextPrimary
import com.yuruan.neuqassist.ui.theme.TextTertiary

/**
 * 通用网页查看器 —— 移植自 HarmonyOS 端 pages/WebViewPage.ets
 *
 * 路由参数：
 * - url  ：要加载的网页地址（必填）
 * - title：页面标题（可选，默认"网页"）
 *
 * 用途：校历查看、隐私政策等外部网页内容的轻量展示入口。
 */

/**
 * URL 白名单校验（安全收敛）：只允许加载
 *  - http/https 且域名属于 neuq.edu.cn / tsiao.io / pages.dev / yuruan111.github.io
 *    及其子域（http 放行是因教务系统本身不支持 HTTPS）
 * 其余一律拦截。域名匹配做后缀边界校验，避免误放行 evil-neuq.edu.cn.attacker.com。
 */
internal const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

private fun isInternalWebScheme(url: String): Boolean {
    val lower = url.trim().lowercase()
    return lower.startsWith("about:") ||
        lower.startsWith("javascript:") ||
        lower.startsWith("blob:") ||
        lower.startsWith("data:")
}

private fun isAllowedUrl(url: String): Boolean {
    val m = Regex("^https?://([^/:?#]+)", RegexOption.IGNORE_CASE).find(url) ?: return false
    val host = m.groupValues[1].lowercase()
    val allowedDomains = listOf("neuq.edu.cn", "tsiao.io", "pages.dev", "yuruan111.github.io")
    return allowedDomains.any { host == it || host.endsWith(".$it") }
}

/**
 * 判定 URL 是否已真正落地到教务主页 homeExt.action。
 * 必须剥离 ? 查询串与 # 锚点并校验域名/路径，防止 CAS ?service=...homeExt.action 误触发。
 */
private fun isEamsHomeUrl(url: String): Boolean {
    val pathOnly = url.substringBefore('?').substringBefore('#')
    return Regex("^https?://jwxt\\.neuq\\.edu\\.cn/eams/homeExt\\.action(?:;|\$)", RegexOption.IGNORE_CASE)
        .containsMatchIn(pathOnly)
}

/**
 * 判定 URL 是否仍停留在 WebVPN / CAS / 教务登录或设备指纹中间页（尚未登录成功）。
 */
internal fun isWebLoginPendingUrl(url: String): Boolean {
    val u = url.trim().lowercase()
    if (u.isEmpty()) return false
    if (Regex("/login(?:[/?#;]|$)").containsMatchIn(u)) return true
    return u.contains("/authserver/login") ||
        u.contains("/do-login") ||
        u.contains("cas_login") ||
        u.contains("/cas/login") ||
        u.contains("/eams/login") ||
        u.contains("/fingerprint") ||
        u.contains("/set-fingerprint")
}

/**
 * 判定 URL 是否已落到 WebVPN 登录后的门户页或代理业务页。
 */
internal fun isWebvpnLoggedInUrl(url: String): Boolean {
    val u = url.trim().lowercase()
    return Regex("^https://vpn\\.neuq\\.edu\\.cn(?:/|$)").containsMatchIn(u) &&
        !isWebLoginPendingUrl(u) &&
        !u.contains("/authserver/")
}

/**
 * 深澜 WebVPN 网关的指纹跳转页路径。
 * 该页**没有任何可见内容**，全部逻辑是「FingerprintJS 算指纹 → location.href 跳转」，
 * 且不含 try/catch —— 脚本一旦执行失败，页面就永久白屏。
 */
private const val FP_PATH = "/fingerprint"

/**
 * 统一 WebView 配置 —— 登录页与校历/地图页共用。
 *
 * 缺任一项都可能表现为「页面渲染错乱」或登录走不通：
 *  - useWideViewPort + loadWithOverviewMode：按网页自身 viewport 缩放。
 *    不开这两项时，部分未做移动适配的登录页会按桌面宽度渲染，
 *    出现排版错乱、内容被横向裁切；
 *  - mixedContentMode：学校 WebVPN/CAS 登录页是 https，但常引用 http 静态资源，
 *    必须使用 MIXED_CONTENT_ALWAYS_ALLOW 放行主动混合内容（CSS/JS/iframe），
 *    否则样式表与脚本被拦截，页面「几乎未渲染」；
 *  - desktopUa：教务系统按桌面浏览器 UA 放行并下发完整布局；
 *  - 第三方 Cookie：CAS 登录要在 vpn.neuq.edu.cn 与统一身份认证域之间跳转，
 *    不允许第三方 Cookie 时会在跳转环节丢失会话；
 *  - setSupportMultipleWindows(false)：登录页里的 target=_blank / window.open
 *    改为在当前 WebView 打开，否则点击后「没反应」。
 */
private fun configureWebView(
    web: WebView,
    databaseEnabled: Boolean = true,
    desktopUa: Boolean = false
) {
    web.layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
    web.isFocusable = true
    web.isFocusableInTouchMode = true
    web.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        this.databaseEnabled = databaseEnabled
        useWideViewPort = true
        loadWithOverviewMode = true
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(false)
        loadsImagesAutomatically = true
        blockNetworkImage = false
        cacheMode = WebSettings.LOAD_DEFAULT
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        if (desktopUa) {
            userAgentString = DESKTOP_USER_AGENT
        }
    }
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
}

@Composable
fun WebViewPage(
    url: String,
    title: String = "网页",
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var currentUrl by remember { mutableStateOf(url) }
    var loading by remember { mutableStateOf(true) }
    var loadProgress by remember { mutableStateOf(0) }
    var loadError by remember { mutableStateOf("") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onBack()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let { wv ->
                try {
                    wv.stopLoading()
                    wv.destroy()
                } catch (_: Exception) {
                }
            }
            webViewRef = null
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF2F3F5))) {
        // 顶栏：返回 / 标题 / 浏览器打开
        // 顶部补 safeDrawing inset：本页为全屏页，避让状态栏与挖孔
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextPrimary)
            }
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)))
                } catch (e: Exception) {
                }
            }) {
                Icon(Icons.Default.Share, contentDescription = "浏览器打开", tint = TextPrimary)
            }
        }

        // 底部补 navigationBars inset：网页区不下沉到导航栏之下，避免页尾内容被遮挡。
        // ⚠️ 用 weight(1f) 而非 fillMaxSize()：后者会请求整个父容器高度，
        // 使 WebView 底边超出屏幕且滚不到（页尾内容不可见）。
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
        ) {
            if (isAllowedUrl(url)) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            configureWebView(this)
                            webViewRef = this
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    loadProgress = newProgress
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val u = request?.url?.toString() ?: return false
                                    if (isInternalWebScheme(u) || isAllowedUrl(u)) return false
                                    if (request.isForMainFrame) {
                                        loadError = "已拦截非白名单链接"
                                    }
                                    return true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    loading = false
                                    loadError = ""
                                    url?.let { currentUrl = it }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    // 主文档加载失败时收起遮罩并显示原因
                                    if (request?.isForMainFrame == true) {
                                        loading = false
                                        loadError = "加载失败（${error?.errorCode ?: -1}）：" +
                                            "${error?.description ?: "未知错误"}"
                                    }
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    errorResponse: WebResourceResponse?
                                ) {
                                    if (request?.isForMainFrame == true) {
                                        loading = false
                                        loadError = "服务器返回 HTTP ${errorResponse?.statusCode ?: -1}"
                                    }
                                }
                            }
                            loadUrl(url)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("该链接不在允许的域名范围内", fontSize = 14.sp, color = TextTertiary)
                }
            }

            if (loadProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { loadProgress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.TopCenter)
                )
            }

            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }

            if (loadError.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                ) {
                    Surface(color = Color(0xFFFFF4E0), shape = RoundedCornerShape(10.dp)) {
                        Text(
                            text = loadError,
                            fontSize = 12.sp,
                            color = Color(0xFFB26A00),
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 网页登录页 —— 移植自 HarmonyOS 端 pages/WebLogin.ets
 *
 * 用 WebView 加载教务系统/WebVPN 登录页，由用户在真实浏览器环境中完成登录
 * （salt+SHA1 加密、302 跳转 Cookie、指纹检查、验证码等全部由 WebView 原生处理），
 * 登录成功后由调用方从 CookieManager 取出会话 Cookie 灌入原生请求。
 *
 * 两种模式（经参数 mode 指定）：
 * - eams  ：直连教务系统登录页（校内网络）
 * - webvpn：WebVPN 统一身份认证登录页（校外网络）
 */
@Composable
fun WebLoginPage(
    mode: String,
    onSessionExtracted: (String, String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var activeMode by remember(mode) { mutableStateOf(mode) }
    // 2026-10 起教务系统走统一身份认证（CAS）：直接加载 CAS 登录页
    // （service 指向教务主页），登录成功后 CAS 会 302 回教务系统完成会话建立。
    // 若仍加载旧的 loginExt.action，用户填完表单会被 302 弹到 CAS 页，造成「跳到别的页」。
    val loginUrl = if (activeMode == "webvpn") {
        "https://vpn.neuq.edu.cn/login?cas_login=true"
    } else {
        com.yuruan.neuqassist.net.CasAuth.EAMS_CAS_LOGIN_URL
    }
    var currentUrl by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var loadProgress by remember { mutableStateOf(0) }
    var toastMsg by remember { mutableStateOf("") }
    /** 加载失败信息（错误码/描述），非空时显示在页面上——把「打不开」变成可读原因 */
    var errorText by remember { mutableStateOf("") }
    /** 页面卡住（网关未返回登录表单）：显示兜底操作而不是白屏 */
    var stuck by remember { mutableStateOf(false) }
    /** WebView 实例引用：指纹预置完成后再触发加载 */
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var loginDetected by remember { mutableStateOf(false) }
    var exiting by remember { mutableStateOf(false) }

    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else if (!exiting) {
            exiting = true
            onBack()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let { wv ->
                try {
                    wv.stopLoading()
                    wv.destroy()
                } catch (_: Exception) {
                }
            }
            webViewRef = null
        }
    }

    // WebVPN 模式：先用原生请求预置指纹 Cookie，再加载登录页。
    // 不走网关的 /fingerprint JS 跳转页（该页无可见内容，脚本一失败就永久白屏）。
    LaunchedEffect(activeMode) {
        currentUrl = ""
        loading = true
        stuck = false
        errorText = ""
        loginDetected = false
        if (activeMode == "webvpn") {
            WebvpnFingerprint.prime()
        }
        // 等 AndroidView 完成 WebView 创建
        repeat(50) {
            if (webViewRef != null) return@repeat
            kotlinx.coroutines.delay(20)
        }
        webViewRef?.let { wv ->
            wv.stopLoading()
            wv.loadUrl(loginUrl)
        } ?: run {
            errorText = "WebView 初始化失败"
            loading = false
        }
    }

    // —— 白屏兜底 1：停在 /fingerprint 说明页面那段 FingerprintJS 脚本没跑起来。
    //    该页全部内容就是「算指纹 → location.href 跳转」，没有 try/catch，
    //    脚本一失败就永久白屏。这里等 3 秒仍无跳转就直接注入跳转脚本，
    //    与 Rust 客户端「用伪造指纹直接请求 /set-fingerprint」的做法一致。
    LaunchedEffect(currentUrl) {
        if (!currentUrl.contains(FP_PATH)) return@LaunchedEffect
        kotlinx.coroutines.delay(3000)
        if (currentUrl.contains(FP_PATH)) {
            val fp = com.yuruan.neuqassist.net.WebvpnCrypto.fakeFingerprint()
            webViewRef?.evaluateJavascript(
                "window.location.replace('/set-fingerprint?fingerprint=$fp');",
                null
            )
        }
    }

    // —— 白屏兜底 2：12 秒后仍停在指纹页 / 完全没加载出地址，则给可操作提示
    LaunchedEffect(currentUrl) {
        stuck = false
        kotlinx.coroutines.delay(12000)
        if (currentUrl.isEmpty() || currentUrl.contains(FP_PATH)) {
            loading = false
            stuck = true
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF2F3F5))) {
        // 顶栏：返回 / 标题 / 提取会话
        // 顶部补 safeDrawing inset：本页为全屏页，避让状态栏与挖孔
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "←",
                fontSize = 20.sp,
                color = TextPrimary,
                modifier = Modifier
                    .clickable {
                        if (!exiting) {
                            exiting = true
                            onBack()
                        }
                    }
                    .padding(8.dp)
            )
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    text = if (activeMode == "webvpn") "WebVPN 登录" else "教务系统登录",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                Text(
                    text = if (loginDetected) {
                        "已检测到登录成功，请点击右上角「提取会话」"
                    } else {
                        "登录完成后点击右侧按钮"
                    },
                    fontSize = 10.sp,
                    color = if (loginDetected) MaterialTheme.colorScheme.primary else TextTertiary
                )
            }
            Text(
                text = "↻",
                fontSize = 18.sp,
                color = TextPrimary,
                modifier = Modifier
                    .clickable {
                        stuck = false
                        errorText = ""
                        loading = true
                        webViewRef?.reload()
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
            TextButton(onClick = {
                if (exiting) return@TextButton
                if (currentUrl.isNotEmpty() && isWebLoginPendingUrl(currentUrl)) {
                    toastMsg = "当前仍在登录页，请先完成登录后再点击「提取会话」"
                    return@TextButton
                }
                val cm = CookieManager.getInstance()
                cm.flush()
                var cookies = if (activeMode == "webvpn") {
                    cm.getCookie("https://vpn.neuq.edu.cn/") ?: ""
                } else {
                    cm.getCookie("http://jwxt.neuq.edu.cn/eams/homeExt.action") ?: ""
                }
                if (cookies.isEmpty() && activeMode == "eams") {
                    cookies = cm.getCookie("http://jwxt.neuq.edu.cn/") ?: ""
                }
                if (cookies.isEmpty() && currentUrl.startsWith("http")) {
                    cookies = cm.getCookie(currentUrl) ?: ""
                }
                if (cookies.isEmpty()) {
                    toastMsg = "未取到 Cookie，请确认已完成登录"
                } else {
                    exiting = true
                    onSessionExtracted(activeMode, cookies)
                }
            }) {
                Text(
                    "提取会话",
                    fontSize = 13.sp,
                    fontWeight = if (loginDetected) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = currentUrl,
                fontSize = 9.sp,
                color = TextTertiary,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (activeMode == "webvpn") {
                    "当前：WebVPN（点此切校园网直连）"
                } else {
                    "当前：校园网直连（点此切WebVPN）"
                },
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clickable {
                        activeMode = if (activeMode == "webvpn") "eams" else "webvpn"
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        // 底部补 navigationBars inset：登录页的提交按钮可能位于网页最下方，
        // 被导航栏压住会导致用户无法完成登录，故网页区不下沉到导航栏之下。
        // ⚠️ 必须用 weight(1f) 而不是 fillMaxSize()：本 Box 是 Column 的最后一个子项，
        // fillMaxSize() 会请求「整个父容器高度」，导致 WebView 底边超出屏幕且滚不到，
        // 登录表单下部（含提交按钮）永远不可见 —— 表现为「登录页渲染错乱」。
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        configureWebView(this, databaseEnabled = false, desktopUa = true)
                        webViewRef = this
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loadProgress = newProgress
                                if (newProgress >= 80 && currentUrl.isNotEmpty() && !currentUrl.contains(FP_PATH)) {
                                    loading = false
                                }
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                // http/https 与页面内部 scheme（about/javascript/blob/data）留在 WebView 内；
                                // 其余 scheme（tel:/mailto: 等）交给系统处理，避免 ERR_UNKNOWN_URL_SCHEME
                                val u = request?.url?.toString() ?: return false
                                if (u.startsWith("http://") || u.startsWith("https://") || isInternalWebScheme(u)) {
                                    return false
                                }
                                return try {
                                    view?.context?.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                                    true
                                } catch (e: Exception) {
                                    true
                                }
                            }

                            override fun onPageCommitVisible(view: WebView?, url: String?) {
                                url?.let { currentUrl = it }
                                if (url?.contains(FP_PATH) != true) {
                                    loading = false
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                url?.let { currentUrl = it }
                                // 深澜网关的 /fingerprint 是纯 JS 跳转页（页面本身没有任何
                                // 可见内容，靠 FingerprintJS 算完指纹再跳转）。此时继续显示
                                // 加载遮罩，避免用户看到一瞬间白屏以为页面坏了。
                                if (url?.contains("/fingerprint") == true) return

                                loading = false
                                errorText = ""
                                // eams / webvpn 检测到登录落地时仅高亮提示并弹 Toast，不自动退栈
                                val loadedUrl = url ?: ""
                                if (activeMode != "webvpn" && !loginDetected && isEamsHomeUrl(loadedUrl)) {
                                    loginDetected = true
                                    toastMsg = "已检测到登录成功，请点击右上角「提取会话」"
                                } else if (activeMode == "webvpn" && !loginDetected && isWebvpnLoggedInUrl(loadedUrl)) {
                                    loginDetected = true
                                    toastMsg = "已检测到 WebVPN 登录成功，请点击右上角「提取会话」"
                                }
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                // 主文档加载失败：收起遮罩并显示具体错误，便于定位
                                if (request?.isForMainFrame == true) {
                                    loading = false
                                    errorText = "加载失败（${error?.errorCode ?: -1}）：" +
                                        "${error?.description ?: "未知错误"}\n$currentUrl"
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?
                            ) {
                                if (request?.isForMainFrame == true) {
                                    loading = false
                                    errorText = "服务器返回 HTTP ${errorResponse?.statusCode ?: -1}\n" +
                                        "${request.url}"
                                }
                            }

                            override fun onReceivedSslError(
                                view: WebView?,
                                handler: SslErrorHandler?,
                                error: SslError?
                            ) {
                                handler?.cancel()
                                val errUrl = error?.url ?: ""
                                val curHost = runCatching { Uri.parse(currentUrl).host }.getOrNull() ?: ""
                                val errHost = runCatching { Uri.parse(errUrl).host }.getOrNull() ?: ""
                                if (errUrl.isEmpty() || currentUrl.isEmpty() ||
                                    (errHost.isNotEmpty() && errHost.equals(curHost, ignoreCase = true))
                                ) {
                                    loading = false
                                    errorText = "SSL 证书校验未通过（${error?.primaryError ?: -1}）：" +
                                        errUrl.ifEmpty { currentUrl }
                                }
                            }
                        }
                        // loadUrl 由 LaunchedEffect 在指纹预置完成后调用（见上方）
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            if (loadProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { loadProgress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.TopCenter)
                )
            }
            if (errorText.isNotEmpty() || stuck) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                ) {
                    Surface(
                        color = Color(0xFFFFF4E0),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = if (errorText.isNotEmpty()) {
                                    errorText
                                } else {
                                    "网关未返回登录表单（停留在指纹校验页）。\n" +
                                        "当前地址：${currentUrl.ifEmpty { "（未加载出地址）" }}"
                                },
                                fontSize = 12.sp,
                                color = Color(0xFFB26A00)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row {
                                Text(
                                    text = "在浏览器中打开",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        try {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(loginUrl))
                                            )
                                        } catch (e: Exception) {
                                            toastMsg = "无法打开浏览器"
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.width(20.dp))
                                Text(
                                    text = "重试",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        stuck = false
                                        errorText = ""
                                        loading = true
                                        webViewRef?.reload()
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = com.yuruan.neuqassist.data.BuildInfo.display(),
                                fontSize = 10.sp,
                                color = TextTertiary
                            )
                        }
                    }
                }
            }
            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (activeMode == "webvpn") {
                                "正在打开 WebVPN 登录页…"
                            } else {
                                "正在打开教务登录页…"
                            },
                            fontSize = 13.sp,
                            color = TextTertiary
                        )
                    }
                }
                // 兜底：JS 跳转失败时不能让遮罩一直盖住页面
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(20000)
                    loading = false
                }
            }
        }
    }

    if (toastMsg.isNotEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(bottom = 60.dp),
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
        LaunchedEffect(toastMsg) {
            kotlinx.coroutines.delay(2500)
            toastMsg = ""
        }
    }
}
