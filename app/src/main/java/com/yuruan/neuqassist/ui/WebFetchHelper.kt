package com.yuruan.neuqassist.ui

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * 隐形 WebView 抓取助手 —— 移植自 HarmonyOS 端 Index.ets 的 fetchViaWeb /
 * eamsQueryViaWeb（隐形 Web 组件方案）
 *
 * 用途：
 * 1. 原生 HttpURLConnection 在部分运营商网络下会被中途重置时，
 *    用系统 WebView（Chromium 网络栈）兜底拉取页面；
 * 2. 实时查询的「网页通道」：加载教务页面后注入同步 XHR 调用
 *    free!search.action，从同源上下文取回 gridtable HTML。
 *
 * 实现说明：
 * - WebView 需在主线程创建与操作，故用 Handler 切主线程；
 * - 分块取回绕过 evaluateJavascript 的 ~64KB 截断问题
 *   （对应 HarmonyOS 端 runJavaScript 的 jsonUnescape + 15000 字符分块）。
 */
object WebFetchHelper {

    @Volatile
    private var webView: WebView? = null

    /** 最近一次 onPageFinished 落地的地址（供实时查询判定当前页面/复用预热） */
    @Volatile
    var lastLoadedUrl: String? = null
        private set

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 确保 WebView 已创建（应用上下文持有，不随 Activity 销毁） */
    private fun ensureWebView(context: android.content.Context): WebView {
        webView?.let { return it }
        return synchronized(this) {
            webView ?: WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = false
                settings.mediaPlaybackRequiresUserGesture = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                // 隐形：不绘制、不参与交互
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean = false
                }
            }.also { webView = it }
        }
    }

    /**
     * 把网页登录提取的会话 Cookie 回灌到 WebView 的 CookieManager。
     *
     * 为什么需要：实时查询的页面加载依赖 WebView 自身的 Cookie 罐；进程被系统回收、
     * Cookie 落盘未及时 flush、或只在原生客户端注入过 Cookie 时，WebView 会以「未登录」
     * 身份访问教务页 → 被 302 到 CAS 登录页 → 实时查询表现为「怎么都登录失效」。
     * 每次查询前用已保存的 sessionCookies 兜底回灌，可自愈这类状态。
     */
    suspend fun seedCookies(cookieHeader: String, urls: List<String>) {
        if (cookieHeader.isEmpty() || urls.isEmpty()) return
        try {
            withContext(Dispatchers.Main) {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                for (url in urls) {
                    for (raw in cookieHeader.split(";")) {
                        val pair = raw.trim()
                        if (pair.isEmpty() || !pair.contains('=')) continue
                        cm.setCookie(url, pair)
                    }
                }
                cm.flush()
            }
        } catch (e: Exception) {
            com.yuruan.neuqassist.data.Logger.warn("WebView Cookie 回灌失败: ${e.message}")
        }
    }

    /**
     * 经 WebView 拉取 URL 的完整 HTML（含分块取回）。
     * 返回空串表示失败。
     */
    suspend fun fetch(context: android.content.Context, url: String): String {
        return try {
            val wv = ensureWebView(context)
            val loaded = loadPage(wv, url)
            if (!loaded) {
                com.yuruan.neuqassist.data.Logger.warn(
                    "WebView 页面加载失败（30s 超时或主文档错误）: $url"
                )
                return ""
            }
            // 先取总长度，再按 15000 字符分块取回拼接
            val lenStr = runJs(wv, "document.documentElement.outerHTML.length")
            val total = lenStr.trim().trim('"').toIntOrNull() ?: return ""
            if (total <= 0) return ""
            val full = StringBuilder(total)
            val CHUNK = 15000
            var off = 0
            while (off < total) {
                val end = minOf(off + CHUNK, total)
                val chunk = runJs(
                    wv,
                    "document.documentElement.outerHTML.substring($off, $end)"
                )
                full.append(chunk)
                off = end
            }
            full.toString()
        } catch (e: Exception) {
            com.yuruan.neuqassist.data.Logger.warn("WebView 分块取回失败: ${e.message}")
            ""
        }
    }

    /** 加载页面并等待 onPageFinished（带 30 秒超时） */
    private suspend fun loadPage(wv: WebView, url: String): Boolean =
        suspendCancellableCoroutine { cont ->
            var done = false
            lateinit var timeoutRunnable: Runnable
            val client = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    if (done) return
                    if (loadedUrl?.contains("/fingerprint") == true) {
                        val fp = com.yuruan.neuqassist.net.WebvpnCrypto.fakeFingerprint()
                        view?.evaluateJavascript(
                            "window.location.replace('/set-fingerprint?fingerprint=$fp');",
                            null
                        )
                        return
                    }
                    if (loadedUrl != null) lastLoadedUrl = loadedUrl
                    done = true
                    mainHandler.removeCallbacks(timeoutRunnable)
                    if (cont.isActive) cont.resume(true)
                }
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    if (done) return
                    if (request?.isForMainFrame == true) {
                        done = true
                        mainHandler.removeCallbacks(timeoutRunnable)
                        if (cont.isActive) cont.resume(false)
                    }
                }
            }
            timeoutRunnable = Runnable {
                if (!done) {
                    done = true
                    if (cont.isActive) cont.resume(false)
                }
            }
            cont.invokeOnCancellation {
                done = true
                mainHandler.removeCallbacks(timeoutRunnable)
            }
            mainHandler.post {
                if (!cont.isActive) return@post
                try {
                    wv.webViewClient = client
                    wv.loadUrl(url)
                    // 30 秒超时保护
                    mainHandler.postDelayed(timeoutRunnable, 30000)
                } catch (e: Exception) {
                    if (!done) {
                        done = true
                        mainHandler.removeCallbacks(timeoutRunnable)
                        if (cont.isActive) cont.resume(false)
                    }
                }
            }
        }

    /** 执行 JS 并取回解码后的字符串结果 */
    suspend fun runJs(script: String): String {
        val wv = webView ?: return ""
        return runJs(wv, script)
    }

    private suspend fun runJs(wv: WebView, script: String): String =
        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                if (!cont.isActive) return@post
                try {
                    wv.evaluateJavascript(script) { value ->
                        if (cont.isActive) cont.resume(unescapeJs(value ?: ""))
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume("")
                }
            }
        }

    /**
     * 分块读取 window.__ncr 等 JS 变量中的字符串。
     * 对应 HarmonyOS 端 eamsQueryViaWeb 的第 ③ 步。
     */
    suspend fun readJsString(expr: String, total: Int): String {
        val wv = webView ?: return ""
        val full = StringBuilder(total)
        val CHUNK = 15000
        var off = 0
        while (off < total) {
            val end = minOf(off + CHUNK, total)
            val chunk = runJs(wv, "$expr.substring($off, $end)")
            full.append(chunk)
            off = end
        }
        return full.toString()
    }

    /**
     * 在当前同源网页上下文内发起同步 XHR POST 并分块取回响应文本。
     * 用于复用 WebView 完整 Cookie 域与 CAS SSO 会话。
     */
    suspend fun postXhr(relativePath: String, formBody: String): String {
        if (webView == null) return ""
        val pathJs = JSONObject.quote(relativePath)
        val bodyJs = JSONObject.quote(formBody)
        val js = "(function(){" +
            "var x=new XMLHttpRequest();" +
            "x.open('POST',$pathJs,false);" +
            "x.setRequestHeader('Content-Type','application/x-www-form-urlencoded');" +
            "x.send($bodyJs);" +
            "window.__nq_xhr = x.responseText || '';" +
            "return x.status + '|' + String(window.__nq_xhr.length);" +
            "})()"
        val meta = runJs(js).trim().trim('"')
        val parts = meta.split("|")
        val status = parts.getOrNull(0)?.toIntOrNull()
        val total = parts.getOrNull(1)?.toIntOrNull() ?: 0
        if (status == null || status >= 400 || total <= 0) {
            throw com.yuruan.neuqassist.data.AppError.fetch("教务网页请求异常（返回: $meta），请重新网页登录")
        }
        return readJsString("window.__nq_xhr", total)
    }

    /**
     * JSON 字符串反转义：evaluateJavascript 返回结果是 JSON 编码的
     * （< > & 变 \u003C \u003E \u0026，引号变 \"，中文变 \uXXXX 代理对）。
     */
    private fun unescapeJs(raw: String): String {
        if (raw.length < 2 || raw.first() != '"' || raw.last() != '"') return raw
        val inner = raw.substring(1, raw.length - 1)
        val out = StringBuilder(inner.length)
        var i = 0
        while (i < inner.length) {
            val ch = inner[i]
            if (ch != '\\') {
                out.append(ch)
                i++
                continue
            }
            if (i + 1 >= inner.length) {
                out.append(ch)
                i++
                continue
            }
            val next = inner[i + 1]
            when {
                next == 'u' && i + 5 < inner.length -> {
                    val code = inner.substring(i + 2, i + 6).toIntOrNull(16)
                    if (code != null) {
                        out.append(code.toChar())
                        i += 6
                        continue
                    }
                    out.append(next)
                    i += 2
                }
                next == 'n' -> { out.append('\n'); i += 2 }
                next == 'r' -> { out.append('\r'); i += 2 }
                next == 't' -> { out.append('\t'); i += 2 }
                next == 'b' -> { out.append('\b'); i += 2 }
                next == 'f' -> { out.append('\u000C'); i += 2 }
                else -> { out.append(next); i += 2 }
            }
        }
        return out.toString()
    }

    /** 清空 WebView 内容，释放 Web 资源（对应 aboutToDisappear 的 loadUrl('about:blank')） */
    fun release() {
        mainHandler.post {
            try {
                webView?.loadUrl("about:blank")
                webView?.destroy()
                webView = null
                lastLoadedUrl = null
            } catch (e: Exception) {
                // 忽略
            }
        }
    }
}
