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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
private fun isAllowedUrl(url: String): Boolean {
    val m = Regex("^https?://([^/:?#]+)", RegexOption.IGNORE_CASE).find(url) ?: return false
    val host = m.groupValues[1].lowercase()
    val allowedDomains = listOf("neuq.edu.cn", "tsiao.io", "pages.dev", "yuruan111.github.io")
    return allowedDomains.any { host == it || host.endsWith(".$it") }
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
 *    WebView 默认拦截混合内容 → 样式表与脚本加载不全，页面「渲染不全」；
 *  - 第三方 Cookie：CAS 登录要在 vpn.neuq.edu.cn 与统一身份认证域之间跳转，
 *    不允许第三方 Cookie 时会在跳转环节丢失会话；
 *  - setSupportMultipleWindows(false)：登录页里的 target=_blank / window.open
 *    改为在当前 WebView 打开，否则点击后「没反应」。
 */
private fun configureWebView(web: WebView) {
    web.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        useWideViewPort = true
        loadWithOverviewMode = true
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(false)
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
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
    var loadError by remember { mutableStateOf("") }

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
                            webViewClient = object : WebViewClient() {
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
    onSessionExtracted: (String) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    // 2026-10 起教务系统走统一身份认证（CAS）：直接加载 CAS 登录页
    // （service 指向教务主页），登录成功后 CAS 会 302 回教务系统完成会话建立。
    // 若仍加载旧的 loginExt.action，用户填完表单会被 302 弹到 CAS 页，造成「跳到别的页」。
    val loginUrl = if (mode == "webvpn") {
        "https://vpn.neuq.edu.cn/login?cas_login=true"
    } else {
        com.yuruan.neuqassist.net.CasAuth.EAMS_CAS_LOGIN_URL
    }
    var currentUrl by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var toastMsg by remember { mutableStateOf("") }
    /** 加载失败信息（错误码/描述），非空时显示在页面上——把「打不开」变成可读原因 */
    var errorText by remember { mutableStateOf("") }
    /** 页面卡住（网关未返回登录表单）：显示兜底操作而不是白屏 */
    var stuck by remember { mutableStateOf(false) }
    /** WebView 实例引用：指纹预置完成后再触发加载 */
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // WebVPN 模式：先用原生请求预置指纹 Cookie，再加载登录页。
    // 不走网关的 /fingerprint JS 跳转页（该页无可见内容，脚本一失败就永久白屏）。
    LaunchedEffect(Unit) {
        if (mode == "webvpn") {
            WebvpnFingerprint.prime()
        }
        // 等 AndroidView 完成 WebView 创建
        repeat(50) {
            if (webViewRef != null) return@repeat
            kotlinx.coroutines.delay(20)
        }
        webViewRef?.let { wv ->
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
                    .clickable { onBack() }
                    .padding(8.dp)
            )
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    text = if (mode == "webvpn") "WebVPN 登录" else "教务系统登录",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                Text(text = "登录完成后点击右侧按钮", fontSize = 10.sp, color = TextTertiary)
            }
            TextButton(onClick = {
                val cookieUrl = if (mode == "webvpn") {
                    "https://vpn.neuq.edu.cn/"
                } else {
                    "http://jwxt.neuq.edu.cn/"
                }
                val cookies = android.webkit.CookieManager.getInstance()
                    .getCookie(cookieUrl) ?: ""
                if (cookies.isEmpty()) {
                    toastMsg = "未取到 Cookie，请确认已完成登录"
                } else {
                    onSessionExtracted(cookies)
                }
            }) {
                Text("提取会话", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
        }

        Text(
            text = currentUrl,
            fontSize = 9.sp,
            color = TextTertiary,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
        )

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
                        configureWebView(this)
                        webViewRef = this
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                // http/https 留在 WebView 内；其余 scheme（tel:/mailto: 等）
                                // 交给系统处理，避免 ERR_UNKNOWN_URL_SCHEME
                                val u = request?.url?.toString() ?: return false
                                if (u.startsWith("http://") || u.startsWith("https://")) {
                                    return false
                                }
                                return try {
                                    view?.context?.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                                    true
                                } catch (e: Exception) {
                                    true
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
                                // eams 模式可自动判定：登录成功后 eams 会重定向到 homeExt.action
                                if (mode != "webvpn" && url?.contains("homeExt.action") == true) {
                                    val cookieUrl = "http://jwxt.neuq.edu.cn/"
                                    val cookies = CookieManager.getInstance()
                                        .getCookie(cookieUrl) ?: ""
                                    if (cookies.isNotEmpty()) onSessionExtracted(cookies)
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
                        }
                        // loadUrl 由 LaunchedEffect 在指纹预置完成后调用（见上方）
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
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
                            text = if (mode == "webvpn") {
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
            modifier = Modifier.fillMaxSize().padding(bottom = 60.dp),
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
    }
}
