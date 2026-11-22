package com.yuruan.neuqassist.ui

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
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
     * 经 WebView 拉取 URL 的完整 HTML（含分块取回）。
     * 返回空串表示失败。
     */
    suspend fun fetch(context: android.content.Context, url: String): String {
        return try {
            val wv = ensureWebView(context)
            val loaded = loadPage(wv, url)
            if (!loaded) return ""
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
                full.append(unescapeJs(chunk))
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
            val client = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (done) return
                    done = true
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
                        if (cont.isActive) cont.resume(false)
                    }
                }
            }
            wv.webViewClient = client
            mainHandler.post {
                try {
                    wv.loadUrl(url)
                } catch (e: Exception) {
                    if (!done) {
                        done = true
                        if (cont.isActive) cont.resume(false)
                    }
                }
            }
            // 30 秒超时保护
            mainHandler.postDelayed({
                if (!done) {
                    done = true
                    if (cont.isActive) cont.resume(false)
                }
            }, 30000)
        }

    /** 执行 JS 并取回字符串结果 */
    suspend fun runJs(script: String): String {
        val wv = webView ?: return ""
        return runJs(wv, script)
    }

    private suspend fun runJs(wv: WebView, script: String): String =
        suspendCancellableCoroutine { cont ->
            mainHandler.post {
                try {
                    wv.evaluateJavascript(script) { value ->
                        if (cont.isActive) cont.resume(value ?: "")
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
            full.append(unescapeJs(chunk))
            off = end
        }
        return full.toString()
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
            val next = if (i + 1 < inner.length) inner[i + 1] else ' '
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
            } catch (e: Exception) {
                // 忽略
            }
        }
    }
}
