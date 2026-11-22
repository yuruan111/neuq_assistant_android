package com.yuruan.neuqassist.net

import com.yuruan.neuqassist.data.AppError
import com.yuruan.neuqassist.data.Logger
import com.yuruan.neuqassist.data.extractJsonString

/**
 * WebVPN 会话管理 —— 移植自 HarmonyOS 端 webvpn/WebvpnSession.ets
 * （移植自 Rust src/webvpn/session.rs）
 *
 * 负责登录学校 WebVPN（深澜 srun wengine 系统）并维护会话状态：
 * - cas 模式（默认）：统一身份认证（金智 authserver），
 *   密码按 encrypt.wisedu.js 算法（AES-128-CBC）加密
 * - local 模式：WebVPN 本地账号，密码经 /do-login 表单提交
 *
 * 会话失效（请求被重定向回登录页）时通过 recoverIfKicked 重新登录。
 */
class WebvpnConfig {
    var enabled: Boolean = false
    var baseUrl: String = "https://vpn.neuq.edu.cn"
    var username: String = ""
    var password: String = ""
    var authType: String = "cas"
}

class WebvpnSession(
    private val client: HttpService,
    private val config: WebvpnConfig
) {
    private var loggedIn = false

    fun enabled() = config.enabled

    /** 确保已登录（幂等，已登录或未启用则直接返回） */
    suspend fun ensureLogin() {
        if (!config.enabled || loggedIn) return
        when (config.authType) {
            "cas" -> loginCas()
            "local" -> loginLocal()
            else -> throw AppError.config(
                "未知的 WEBVPN_AUTH_TYPE: '${config.authType}'（可选值：cas / local）"
            )
        }
        loggedIn = true
    }

    /** 会话失效后重置登录状态 */
    fun invalidate() {
        loggedIn = false
    }

    companion object {
        /** 检测页面是否为 WebVPN 登录页 */
        fun isLoginPage(html: String): Boolean =
            html.contains("/do-login") || html.contains("/set-fingerprint")
    }

    /**
     * 登录限流冷却倒计时（T-14b-2）状态。
     *
     * 剩余秒数供查询页展示倒计时；用户点「取消等待」置 cooldownCanceled = true
     * 时立即结束。冷却结束后仍抛限流错误，取消只是让用户尽早拿回控制权。
     * UI 侧读取 `WebvpnSession.Cooldown.cooldownLeft`。
     */
    object Cooldown {
        var cooldownLeft: Int = 0
            internal set

        var cooldownCanceled: Boolean = false
            internal set
    }

    /** 会话恢复：若页面是 WebVPN 登录页则重新登录并返回 true（调用方应重试请求） */
    suspend fun recoverIfKicked(html: String): Boolean {
        if (!config.enabled || !WebvpnSession.isLoginPage(html)) return false
        Logger.warn("检测到 WebVPN 会话失效，尝试重新登录")
        invalidate()
        ensureLogin()
        return true
    }

    /** 将教务系统 URL 转换为经 WebVPN 代理的 URL（未启用时原样返回） */
    fun proxiedAsync(url: String): String {
        if (!config.enabled) return url
        return WebvpnCrypto.buildResourceUrl(config.baseUrl, url)
    }

    private fun baseUrl() = config.baseUrl.trimEnd('/')

    /** 确保已通过指纹检查（伪造 FingerprintJS 指纹） */
    private suspend fun ensureFingerprint() {
        val fpUrl = "${baseUrl()}/set-fingerprint?fingerprint=${WebvpnCrypto.fakeFingerprint()}"
        client.get(fpUrl)
    }

    /** 通过统一身份认证（CAS，金智 authserver）登录 WebVPN */
    private suspend fun loginCas() {
        val base = baseUrl()
        val loginUrl = "$base/login?cas_login=true"

        // 先伪造指纹再访问登录页：未设置指纹时网关可能在
        // /login 与 /set-fingerprint 之间 302 循环，耗尽重定向次数
        ensureFingerprint()

        var resp = client.get(loginUrl)
        var action = loginUrl
        var html = resp.body
        if (html.contains("/set-fingerprint")) {
            ensureFingerprint()
            resp = client.get(loginUrl)
            action = loginUrl
            html = resp.body
        }

        // 已处于登录态（cookie 有效）时会直接进入门户页
        if (!html.contains("casLoginForm")) {
            Logger.info("WebVPN 会话仍然有效，无需重新登录")
            return
        }

        val salt = extractHiddenOrJs(html, "pwdDefaultEncryptSalt")
            ?: throw AppError.fetch("统一身份认证登录页解析失败：未找到密码加密盐")
        val lt = extractHiddenInput(html, "lt")
            ?: throw AppError.fetch("统一身份认证登录页解析失败：未找到 lt")
        val execution = extractHiddenInput(html, "execution")
            ?: throw AppError.fetch("统一身份认证登录页解析失败：未找到 execution")

        // 表单 action 即当前页面 URL（经过 WebVPN 代理的 authserver 登录地址）：
        // authserver 登录页内 <form> 的 action 属性
        val actionMatch = Regex("<form[^>]*action=\"([^\"]+)\"[^>]*>", RegexOption.IGNORE_CASE)
            .find(html)
        if (actionMatch != null) {
            var act = actionMatch.groupValues[1]
            if (act.startsWith("/")) {
                // 经 WebVPN 代理的相对地址需还原为代理绝对地址：
                // 形如 /http/{加密段}/... 的路径直接拼在 WebVPN base 上
                act = "$base$act"
            }
            action = act
        }

        val encryptedPassword = WebvpnCrypto.encryptCasPassword(config.password, salt)
        val params = listOf(
            ParamPair("username", config.username),
            ParamPair("password", encryptedPassword),
            ParamPair("lt", lt),
            ParamPair("dllt", "userNamePasswordLogin"),
            ParamPair("execution", execution),
            ParamPair("_eventId", "submit"),
            ParamPair("rmShown", "1")
        )

        resp = client.postForm(action, params)
        val body = resp.body

        // 失败时 authserver 会重新渲染登录页；成功时重定向回 WebVPN 门户
        if (action.contains("authserver") || body.contains("casLoginForm")) {
            var reason = extractCasError(body)
                ?: "未知原因（可能用户名或密码错误，或需要验证码）"
            if (reason.contains("验证码")) {
                reason += "（IP 短时间内登录失败次数过多，请稍后重试或更换网络环境）"
            }
            throw AppError.loginFailed("统一身份认证（CAS）登录失败: $reason")
        }

        Logger.info("WebVPN 登录成功（统一身份认证）")
    }

    /** 通过 WebVPN 本地账号登录（深澜 /do-login 接口） */
    private suspend fun loginLocal() {
        val base = baseUrl()
        val loginUrl = "$base/login"

        // 同 CAS：先伪造指纹，避免登录页与指纹页之间的重定向循环
        ensureFingerprint()

        var resp = client.get(loginUrl)
        var html = resp.body
        if (html.contains("/set-fingerprint")) {
            ensureFingerprint()
            resp = client.get(loginUrl)
            html = resp.body
        }

        val csrf = extractCsrf(html)
            ?: throw AppError.fetch("WebVPN 登录页解析失败：未找到 _csrf 字段")

        val params = listOf(
            ParamPair("_csrf", csrf),
            ParamPair("auth_type", "local"),
            ParamPair("username", config.username),
            ParamPair("password", config.password),
            ParamPair("needCaptcha", "false")
        )

        resp = client.postForm("$base/do-login", params)
        if (resp.status >= 400) {
            throw AppError.fetch("WebVPN 登录请求失败，状态码: ${resp.status}")
        }

        // 解析 /do-login 的 JSON 响应 { success, url, error, message }
        val body = resp.body
        val success = Regex("\"success\"\\s*:\\s*true").containsMatchIn(body)
        if (!success) {
            val errMsg = extractJsonString(body, "message") ?: ""
            val errCode = extractJsonString(body, "error") ?: ""
            if (errCode == "TOO_MANY_ATTEMPTS" || errMsg.contains("稍后")) {
                Logger.warn("WebVPN 登录被限流，进入 8s 可取消冷却倒计时")
                cooldownCountdown(8)
                throw AppError.fetch("WebVPN 登录被限流（同一 IP 短时间内登录过多）: $errMsg")
            }
            throw AppError.loginFailed(
                "WebVPN 本地账号登录失败: $errMsg ($errCode)。" +
                    "WebVPN 本地账号与统一身份认证账号可能不是同一套，" +
                    "若不确定请使用默认的 cas 认证方式。"
            )
        }

        // 登录成功后访问跳转目标以完成会话建立
        val jumpUrl = extractJsonString(body, "url") ?: ""
        if (jumpUrl.isNotEmpty()) {
            val finishUrl =
                if (jumpUrl.startsWith("http")) jumpUrl else "$base$jumpUrl"
            client.get(finishUrl)
        }
        Logger.info("WebVPN 登录成功")
    }

    /**
     * 登录限流冷却倒计时（T-14b-2）：替代原先冻结 30 秒且不可取消的 sleep。
     * 剩余秒数写入 Cooldown.cooldownLeft 供查询页展示倒计时；
     * 用户点「取消等待」置 Cooldown.cooldownCanceled = true 时立即结束。
     * 冷却结束后仍抛限流错误（与旧行为一致），取消只是让用户尽早拿回控制权。
     */
    private suspend fun cooldownCountdown(seconds: Int) {
        Cooldown.cooldownCanceled = false
        for (left in seconds downTo 1) {
            Cooldown.cooldownLeft = left
            kotlinx.coroutines.delay(1000)
            if (Cooldown.cooldownCanceled) break
        }
        Cooldown.cooldownLeft = 0
        Cooldown.cooldownCanceled = false
    }

    /** 提取 `<input type="hidden" name="xxx" value="yyy">` 的 value */
    private fun extractHiddenInput(html: String, name: String): String? {
        val m = Regex("name=\"$name\"\\s+value=\"([^\"]+)\"").find(html) ?: return null
        return m.groupValues[1]
    }

    /** 提取金智 authserver 密码加密盐：兼容隐藏字段（name=/id=）与 JS 变量两种形式 */
    private fun extractHiddenOrJs(html: String, name: String): String? {
        val patterns = listOf(
            "name=\"$name\"\\s+value=\"([^\"]+)\"",
            "id=\"$name\"\\s+value=\"([^\"]+)\"",
            "$name\\s*=\\s*\"([^]+)\""
        )
        for (p in patterns) {
            val m = Regex(p).find(html)
            if (m != null && m.groupValues.size > 1 && m.groupValues[1].isNotEmpty()) {
                return m.groupValues[1]
            }
        }
        return null
    }

    /** 从金智 authserver 错误页提取错误提示（`<span id="msg" class="errMsg">`） */
    private fun extractCasError(html: String): String? {
        val patterns = listOf(
            "<span[^>]*id=\"msg\"[^>]*>\\s*([^<]+?)\\s*</span>",
            "<span[^>]*class=\"errMsg\"[^>]*>\\s*([^<]+?)\\s*</span>",
            "id=\"errorMsg\"[^>]*>\\s*([^<]+?)\\s*</span>"
        )
        for (p in patterns) {
            val m = Regex(p).find(html)
            if (m != null) {
                val msg = m.groupValues[1].trim()
                if (msg.isNotEmpty()) return msg
            }
        }
        return null
    }

    private fun extractCsrf(html: String): String? = extractHiddenInput(html, "_csrf")
}
