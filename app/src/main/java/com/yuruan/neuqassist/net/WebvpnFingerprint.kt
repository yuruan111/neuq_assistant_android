package com.yuruan.neuqassist.net

import android.webkit.CookieManager
import com.yuruan.neuqassist.data.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WebVPN 指纹预置 —— 让 WebView 登录页绕开对页面 JS 的依赖。
 *
 * 深澜（srun wengine）网关的访问流程：
 * ```
 * /login?cas_login=true
 *   → 302 → /fingerprint?cas_login=true     ← 纯 JS 页，页面没有任何可见内容
 *             页面脚本：FingerprintJS.load() → fp.get() → 跳转
 *   → /set-fingerprint?fingerprint=<visitorId>
 *   → 302 → /login                           ← 真正的登录表单页
 * ```
 * `/fingerprint` 页的**全部内容就是一段 async 脚本**，脚本里没有任何 try/catch：
 * 只要 `FingerprintJS.load()`（依赖 `/wengine-vpn/js/js/iife.min.js`）抛错或脚本被拦，
 * 后续 `window.location.href` 永远不会执行 —— 用户看到的就是**永久白屏**，
 * 表现为「WebVPN 登录页打不开」。
 *
 * 这里改成：用原生 HTTP 先请求一次 `/set-fingerprint?fingerprint=<伪造指纹>`，
 * 网关会在响应里下发 `wengine_vpn_ticket...` Cookie；把这些 Cookie 灌进 WebView
 * 共用的 CookieManager，再加载 `/login` 时网关就会直接返回登录表单，
 * 整个流程不再依赖页面 JS 能否跑起来。
 *
 * 若预置失败（网络异常等），调用方照常加载登录页，退化为原来的跳转链路。
 */
object WebvpnFingerprint {

    private const val BASE = "https://vpn.neuq.edu.cn"

    /**
     * 预置指纹 Cookie。
     * @return true=已成功注入 Cookie（WebView 可直接加载登录页）；false=未注入，走原始跳转链路
     */
    suspend fun prime(): Boolean {
        val cookieHeader: String = try {
            withContext(Dispatchers.IO) {
                val http = HttpService().apply { timeoutMs = 15000 }
                val fingerprint = WebvpnCrypto.fakeFingerprint()
                val resp = http.get("$BASE/set-fingerprint?fingerprint=$fingerprint")
                if (resp.status >= 400) {
                    Logger.warn("WebVPN 指纹预置：网关返回 ${resp.status}")
                    ""
                } else {
                    http.cookieStore.toHeader()
                }
            }
        } catch (e: Exception) {
            Logger.warn("WebVPN 指纹预置失败: ${e.message}")
            ""
        }

        if (cookieHeader.isEmpty()) return false

        // CookieManager 需在主线程调用
        return try {
            withContext(Dispatchers.Main) {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                for (pair in cookieHeader.split("; ")) {
                    if (pair.contains('=')) {
                        cm.setCookie("$BASE/", pair)
                    }
                }
                cm.flush()
            }
            Logger.info("WebVPN 指纹预置成功，已注入会话 Cookie")
            true
        } catch (e: Exception) {
            Logger.warn("WebVPN 指纹 Cookie 注入失败: ${e.message}")
            false
        }
    }
}
