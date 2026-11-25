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

    /** 标记会话已在外部（如网页登录 Cookie 注入）建立，避免被前置预检覆写 */
    fun markLoggedIn() {
        loggedIn = true
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
        @Volatile
        var cooldownLeft: Int = 0
            internal set

        @Volatile
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
        if (resp.status >= 400) {
            throw AppError.fetch("获取 WebVPN 统一身份认证登录页失败（HTTP ${resp.status}）")
        }
        var html = resp.body
        if (html.contains("/set-fingerprint")) {
            ensureFingerprint()
            resp = client.get(loginUrl)
            if (resp.status >= 400) {
                throw AppError.fetch("获取 WebVPN 统一身份认证登录页失败（HTTP ${resp.status}）")
            }
            html = resp.body
        }
        if (html.contains("/set-fingerprint")) {
            throw AppError.fetch(
                "WebVPN 网关反复要求设置设备指纹，会话无法建立。请检查网关地址填写是否正确、当前网络能否访问 $base"
            )
        }

        // 已处于登录态（cookie 有效）时会直接进入门户页
        if (!CasPageParser.isCasLoginPage(html)) {
            Logger.info("WebVPN 会话仍然有效，无需重新登录")
            return
        }

        val landingUrl = resp.finalUrl.ifEmpty { loginUrl }
        val salt = CasPageParser.extractCasSalt(html).ifEmpty {
            throw AppError.fetch(
                "统一身份认证登录页解析失败：未找到密码加密盐（pwdDefaultEncryptSalt）。" +
                    "页面特征：${CasPageParser.casPageDigest(html)}"
            )
        }
        val lt = CasPageParser.extractHiddenInputValue(html, "lt")
        val execution = CasPageParser.extractHiddenInputValue(html, "execution").ifEmpty {
            throw AppError.fetch(
                "统一身份认证登录页解析失败：未找到 execution 字段。" +
                    "页面特征：${CasPageParser.casPageDigest(html)}"
            )
        }
        val action = CasPageParser.resolveFormAction(html, landingUrl)

        val encryptedPassword = WebvpnCrypto.encryptCasPassword(config.password, salt)
        val params = mutableListOf(
            ParamPair("username", config.username),
            ParamPair("password", encryptedPassword),
            ParamPair("dllt", "userNamePasswordLogin"),
            ParamPair("execution", execution),
            ParamPair("_eventId", "submit"),
            ParamPair("rmShown", "1")
        )
        if (lt.isNotEmpty()) {
            params.add(2, ParamPair("lt", lt))
        }

        val postResp = client.postForm(action, params)
        if (postResp.status >= 400) {
            throw AppError.fetch("统一身份认证登录请求失败（HTTP ${postResp.status}）")
        }
        val body = postResp.body
        val respAction = CasPageParser.extractFormAction(body)
        val postFinalUrl = postResp.finalUrl

        // 失败时 authserver 会重新渲染登录页或停留在 authserver；成功时重定向回 WebVPN 门户
        if (CasPageParser.isCasLoginPage(body) ||
            respAction.contains("authserver") ||
            postFinalUrl.contains("authserver")
        ) {
            var reason = CasPageParser.extractCasFailureReason(body)
                .ifEmpty { "未知原因（可能用户名或密码错误，或需要验证码）" }
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
        if (resp.status >= 400) {
            throw AppError.fetch("获取 WebVPN 本地登录页失败（HTTP ${resp.status}）")
        }
        if (html.contains("/set-fingerprint")) {
            throw AppError.fetch(
                "WebVPN 网关反复要求设置设备指纹，会话无法建立。请检查网关地址填写是否正确、当前网络能否访问 $base"
            )
        }

        val csrf = CasPageParser.extractHiddenInputValue(html, "_csrf").ifEmpty {
            throw AppError.fetch("WebVPN 登录页解析失败：未找到 _csrf 字段")
        }

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
        val errMsg = unescapeJsonString(extractJsonString(body, "message") ?: "")
        val errCode = unescapeJsonString(extractJsonString(body, "error") ?: "")
        val jumpUrl = unescapeJsonString(extractJsonString(body, "url") ?: "")
        if (errCode == "TOO_MANY_ATTEMPTS" || errMsg.contains("稍后")) {
            Logger.warn("WebVPN 登录被限流，进入 8s 可取消冷却倒计时")
            cooldownCountdown(8)
            throw AppError.fetch("WebVPN 登录被限流（同一 IP 短时间内登录过多）: $errMsg")
        }
        val successField = jsonBoolField(body, "success")
        val succeeded = (successField == true) ||
            (successField == null && jumpUrl.isNotEmpty() && errCode.isEmpty())
        if (!succeeded) {
            throw AppError.loginFailed(
                "WebVPN 本地账号登录失败: $errMsg ($errCode)。" +
                    "WebVPN 本地账号与统一身份认证账号可能不是同一套，" +
                    "若不确定请使用默认的 cas 认证方式。"
            )
        }

        // 登录成功后访问跳转目标以完成会话建立
        if (jumpUrl.isNotEmpty()) {
            val finishUrl = resolveAfterLoginUrl(base, jumpUrl)
            client.get(finishUrl)
        }
        Logger.info("WebVPN 登录成功")
    }

    /**
     * 登录限流冷却倒计时（T-14b-2）：替代原先冻结 30 秒且不可取消的 sleep。
     * 剩余秒数写入 Cooldown.cooldownLeft 供查询页展示倒计时；
     * 用户点「取消等待」置 Cooldown.cooldownCanceled = true 时立即结束（200ms 粒度响应）。
     * 冷却结束后仍抛限流错误（与旧行为一致），取消只是让用户尽早拿回控制权。
     */
    private suspend fun cooldownCountdown(seconds: Int) {
        Cooldown.cooldownCanceled = false
        try {
            for (left in seconds downTo 1) {
                Cooldown.cooldownLeft = left
                for (step in 0 until 5) {
                    if (Cooldown.cooldownCanceled) return
                    kotlinx.coroutines.delay(200)
                }
            }
        } finally {
            Cooldown.cooldownLeft = 0
            Cooldown.cooldownCanceled = false
        }
    }

    private fun resolveAfterLoginUrl(base: String, url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return base
        if (trimmed.contains("://")) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"
        return if (trimmed.startsWith("/")) "$base$trimmed" else "$base/$trimmed"
    }

    private fun jsonBoolField(text: String, field: String): Boolean? {
        val m = Regex("\"$field\"\\s*:\\s*(true|false)", RegexOption.IGNORE_CASE).find(text)
        if (m != null) return m.groupValues[1].equals("true", ignoreCase = true)
        val str = extractJsonString(text, field)?.trim()?.lowercase()
        return when (str) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    /** 还原 JSON 字符串值中的转义序列（含 \uXXXX，保证中文限流提示可正常识别与展示） */
    private fun unescapeJsonString(raw: String): String {
        if (raw.isEmpty()) return ""
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch != '\\' || i + 1 >= raw.length) {
                out.append(ch)
                i++
                continue
            }
            val next = raw[i + 1]
            if (next == 'u' && i + 6 <= raw.length) {
                val code = raw.substring(i + 2, i + 6).toIntOrNull(16)
                if (code != null) {
                    out.append(code.toChar())
                    i += 6
                    continue
                }
            }
            when (next) {
                'n', 'r', 't', 'f', 'b' -> {
                    out.append(' ')
                    i += 2
                }
                '"', '\\', '/' -> {
                    out.append(next)
                    i += 2
                }
                else -> {
                    out.append(ch)
                    i++
                }
            }
        }
        return out.toString().trim()
    }
}
