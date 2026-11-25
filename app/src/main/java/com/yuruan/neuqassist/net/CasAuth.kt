package com.yuruan.neuqassist.net

import com.yuruan.neuqassist.data.AppError
import com.yuruan.neuqassist.data.Logger

/**
 * 统一身份认证（金智 CAS authserver）与教务本地登录页的纯文本解析，
 * 对齐 HarmonyOS 端 `parser/CasPageParser.ets`。
 */
internal object CasPageParser {

    private val casFormBlockRe = Regex(
        """<form\b[^>]*\bid\s*=\s*(?:"casLoginForm"|'casLoginForm')[^>]*>[\s\S]*?</form>""",
        RegexOption.IGNORE_CASE
    )
    private val casFormOpenTagRe = Regex(
        """<form\b[^>]*\bid\s*=\s*(?:"casLoginForm"|'casLoginForm')[^>]*>""",
        RegexOption.IGNORE_CASE
    )
    private val anyFormOpenTagRe = Regex("""<form\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val valueAttrRe = Regex("""\bvalue\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    private val actionAttrRe = Regex("""\baction\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
    private val ampEntityRe = Regex("&amp;", RegexOption.IGNORE_CASE)
    private val originRe = Regex("""^(https?://[^/?#]+)""", RegexOption.IGNORE_CASE)
    private val vpnPrefixRe = Regex(
        """^(https?://[^/?#]+)(/https?/[0-9a-f]+)(?:/|$)""",
        RegexOption.IGNORE_CASE
    )
    private val saltIdRe = Regex(
        """<input\b[^>]*\bid\s*=\s*(?:"pwdDefaultEncryptSalt"|'pwdDefaultEncryptSalt')[^>]*>""",
        RegexOption.IGNORE_CASE
    )
    private val saltNameRe = Regex(
        """<input\b[^>]*\bname\s*=\s*(?:"pwdDefaultEncryptSalt"|'pwdDefaultEncryptSalt')[^>]*>""",
        RegexOption.IGNORE_CASE
    )
    private val saltJsRe = Regex("""\bpwdDefaultEncryptSalt\b\s*=\s*(?:"([^"]+)"|'([^']+)')""")
    private val usernameInputRe = Regex("""\bname\s*=\s*["']username["']""", RegexOption.IGNORE_CASE)
    private val casErrorPatterns = listOf(
        Regex("""<span[^>]*\bid\s*=\s*(?:"msg"|'msg')[^>]*>([\s\S]*?)</span>""", RegexOption.IGNORE_CASE),
        Regex("""<span[^>]*\bclass\s*=\s*(?:"[^"]*errMsg[^"]*"|'[^']*errMsg[^']*')[^>]*>([\s\S]*?)</span>""", RegexOption.IGNORE_CASE),
        Regex("""<span[^>]*\bid\s*=\s*(?:"errorMsg"|'errorMsg')[^>]*>([\s\S]*?)</span>""", RegexOption.IGNORE_CASE)
    )
    private val titleRe = Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
    private val scriptBlockRe = Regex("""<script[\s\S]*?</script>""", RegexOption.IGNORE_CASE)
    private val styleBlockRe = Regex("""<style[\s\S]*?</style>""", RegexOption.IGNORE_CASE)
    private val anyTagRe = Regex("""<[^>]*>""")
    private val multiSpaceRe = Regex("""\s+""")

    fun isCasLoginPage(html: String): Boolean =
        html.contains("casLoginForm") || html.contains("pwdDefaultEncryptSalt")

    fun isEamsLocalLoginPage(html: String): Boolean =
        html.contains("form['password']") || usernameInputRe.containsMatchIn(html)

    private fun extractCasFormHtml(html: String): String =
        casFormBlockRe.find(html)?.value ?: ""

    private fun extractAttrValue(match: MatchResult?): String {
        if (match == null) return ""
        return match.groups[1]?.value ?: match.groups[2]?.value ?: ""
    }

    private fun findInputValue(segment: String, name: String): String {
        val escaped = Regex.escape(name)
        val tagRe = Regex(
            """<input\b[^>]*\bname\s*=\s*(?:"$escaped"|'$escaped')[^>]*>""",
            RegexOption.IGNORE_CASE
        )
        for (m in tagRe.findAll(segment)) {
            val v = extractAttrValue(valueAttrRe.find(m.value))
            if (v.isNotEmpty()) return v
        }
        return ""
    }

    /** 提取密码加密盐 pwdDefaultEncryptSalt（隐藏域 id= / name= / JS 变量） */
    fun extractCasSalt(html: String): String {
        val casForm = extractCasFormHtml(html)
        val scopes = if (casForm.isNotEmpty()) listOf(casForm, html) else listOf(html)
        for (scope in scopes) {
            for (re in listOf(saltIdRe, saltNameRe)) {
                val m = re.find(scope)
                if (m != null) {
                    val v = extractAttrValue(valueAttrRe.find(m.value))
                    if (v.isNotEmpty()) return v
                }
            }
        }
        return extractAttrValue(saltJsRe.find(html))
    }

    /** 优先在 `<form id="casLoginForm">` 内提取 name="X" 的非空 value（支持单/双引号与属性乱序） */
    fun extractHiddenInputValue(html: String, name: String): String {
        val casForm = extractCasFormHtml(html)
        if (casForm.isNotEmpty()) {
            val inForm = findInputValue(casForm, name)
            if (inForm.isNotEmpty()) return inForm
        }
        return findInputValue(html, name)
    }

    /** 优先取 `id="casLoginForm"` 表单的 action（支持单/双引号、属性乱序，解码 `&amp;`） */
    fun extractFormAction(html: String): String {
        val formMatch = casFormOpenTagRe.find(html) ?: anyFormOpenTagRe.find(html) ?: return ""
        val raw = extractAttrValue(actionAttrRe.find(formMatch.value)).trim()
        if (raw.isEmpty()) return ""
        return ampEntityRe.replace(raw, "&")
    }

    private fun originOf(url: String): String =
        originRe.find(url)?.groupValues?.get(1) ?: url

    /**
     * 解析登录表单提交地址：
     * 支持绝对地址、`//` 协议相对、`/` 根相对（含 WebVPN 未改写 `/authserver`/`/eams` 时补回 `/http/{hex}` 前缀）
     * 以及相对目录路径。
     */
    fun resolveFormAction(html: String, pageUrl: String): String {
        val raw = extractFormAction(html)
        if (raw.isEmpty()) return pageUrl
        if (raw.contains("://")) return raw
        if (raw.startsWith("//")) {
            val schemeEnd = pageUrl.indexOf("://")
            val scheme = if (schemeEnd > 0) pageUrl.substring(0, schemeEnd) else "https"
            return "$scheme:$raw"
        }
        if (raw.startsWith("/")) {
            if (!raw.startsWith("/http/") && !raw.startsWith("/https/") &&
                (raw.startsWith("/authserver") || raw.startsWith("/eams"))
            ) {
                val vpnMatch = vpnPrefixRe.find(pageUrl)
                if (vpnMatch != null) {
                    return "${vpnMatch.groupValues[1]}${vpnMatch.groupValues[2]}$raw"
                }
            }
            return "${originOf(pageUrl)}$raw"
        }
        val baseNoQuery = pageUrl.substringBefore('#').substringBefore('?')
        val origin = originOf(baseNoQuery)
        val dirEnd = baseNoQuery.lastIndexOf('/')
        return if (dirEnd > origin.length) {
            baseNoQuery.substring(0, dirEnd + 1) + raw
        } else {
            "$origin/$raw"
        }
    }

    private fun collapseWhitespace(text: String): String =
        multiSpaceRe.replace(anyTagRe.replace(text, " "), " ").trim()

    /** 提取 authserver 登录失败提示文案（span#msg / class 含 errMsg / id="errorMsg"） */
    fun extractCasFailureReason(html: String): String {
        for (re in casErrorPatterns) {
            val m = re.find(html)
            if (m != null) {
                val msg = collapseWhitespace(m.groupValues[1])
                if (msg.isNotEmpty()) return msg
            }
        }
        return ""
    }

    /** 错误诊断用的安全页面摘要（剥离脚本、样式与标签，不含凭据） */
    fun casPageDigest(html: String): String {
        val titleMatch = titleRe.find(html)
        val title = if (titleMatch == null) "" else collapseWhitespace(titleMatch.groupValues[1])
        val cleaned = styleBlockRe.replace(scriptBlockRe.replace(html, " "), " ")
        var snippet = collapseWhitespace(cleaned)
        if (snippet.length > 120) snippet = snippet.substring(0, 120) + "…"
        return if (title.isEmpty()) snippet else "标题「$title」；$snippet"
    }
}

/**
 * 金智统一身份认证（CAS authserver）登录 —— 对齐 HarmonyOS 端 `network/CasAuth.ets`。
 *
 * 协议（金智 authserver，encrypt.wisedu.js 前端加密）：
 *   登录页含 pwdDefaultEncryptSalt（AES 盐）+ execution（及可选 lt）隐藏域；
 *   提交 username / password(AES-128-CBC, base64) / [lt] / dllt / execution / _eventId。
 *   成功：302 回 service；失败：重新渲染登录页（<span id="msg"> 给出原因）。
 */
object CasAuth {

    /** 教务系统统一身份认证地址（实测 homeExt.action 302 跳转目标） */
    const val EAMS_CAS_LOGIN_URL =
        "http://ids.neuq.edu.cn/authserver/login" +
            "?service=http%3A%2F%2Fjwxt.neuq.edu.cn%2Feams%2FhomeExt.action"

    private const val CAPTCHA_HINT =
        "（出现验证码通常是同一网络出口登录过于频繁触发了 IP 限流，" +
            "请稍等几分钟再试，或切换到其他网络如手机热点后重试）"

    private fun joinBaseUrl(base: String, path: String): String {
        if (base.isEmpty()) return ""
        return if (base.endsWith("/")) "$base$path" else "$base/$path"
    }

    /**
     * 教务系统登录（CAS 版，支持经 WebVPN 代理）。
     *
     * @param casLoginUrl   经代理（或直连）的 CAS 登录页地址
     * @param eamsBaseUrl   教务基地址（直连形式，以 / 结尾）
     * @param eamsHomePath  EAMS 主页路径（如 "homeExt.action"），用于登录后验证
     * @param legacyUrlBase WebVPN 模式下传入代理后的教务基地址（legacy/验证用）；
     *                      直连模式留空，使用 eamsBaseUrl
     */
    suspend fun loginEamsVia(
        casLoginUrl: String,
        client: HttpService,
        eamsBaseUrl: String,
        eamsHomePath: String,
        username: String,
        password: String,
        legacyUrlBase: String = ""
    ) {
        val probe = client.get(casLoginUrl)
        if (probe.status >= 400) {
            throw AppError.fetch("获取统一身份认证页面失败，状态码: ${probe.status}")
        }
        if (WebvpnSession.isLoginPage(probe.body)) {
            throw AppError.loginFailed("WebVPN 会话未建立或已失效，请检查 WebVPN 登录状态")
        }

        if (CasPageParser.isCasLoginPage(probe.body)) {
            val realLoginPageUrl = probe.finalUrl.ifEmpty { casLoginUrl }
            loginCasOnPage(realLoginPageUrl, probe.body, client, username, password)

            val verifyBase = legacyUrlBase.ifEmpty { eamsBaseUrl }
            val verifyUrl = if (verifyBase.isNotEmpty() && eamsHomePath.isNotEmpty()) {
                joinBaseUrl(verifyBase, eamsHomePath)
            } else {
                casLoginUrl
            }
            val verify = client.get(verifyUrl)
            if (verify.status >= 400) {
                throw AppError.fetch("教务系统登录验证失败，状态码: ${verify.status}")
            }
            if (WebvpnSession.isLoginPage(verify.body)) {
                throw AppError.loginFailed("WebVPN 会话未建立或已失效，请检查 WebVPN 登录状态")
            }
            val stillLogin = CasPageParser.isCasLoginPage(verify.body) ||
                CasPageParser.isEamsLocalLoginPage(verify.body)
            if (stillLogin) {
                throw AppError.loginFailed(
                    "教务系统登录未通过：CAS 认证后仍被弹回登录页。" +
                        "请确认账号密码（统一身份认证账号即学号，密码与教务本地密码可能不同），" +
                        "或在浏览器中手动登录一次验证。"
                )
            }
            Logger.info("教务 CAS 登录验证通过")
            return
        }

        if (CasPageParser.isEamsLocalLoginPage(probe.body)) {
            Logger.info("教务未启用 CAS 跳转，回退本地账号登录流程")
            loginEamsLegacy(client, eamsBaseUrl, username, password, eamsHomePath, legacyUrlBase)
            return
        }

        Logger.info("CAS 探测显示教务会话已可用（状态 ${probe.status}/${probe.body.length} 字节），跳过登录提交")
    }

    /**
     * 在已拿到的 authserver 登录页 HTML 上执行 CAS 登录。
     * @param loginPageUrl 登录页地址（表单 action 的补全基准）
     * @param loginPageHtml 登录页 HTML（已含 salt/execution）
     */
    private suspend fun loginCasOnPage(
        loginPageUrl: String,
        loginPageHtml: String,
        client: HttpService,
        username: String,
        password: String
    ) {
        if (username.isEmpty() || password.isEmpty()) {
            throw AppError.loginFailed("统一身份认证账号或密码为空，请先在设置页填写后再登录")
        }

        val salt = CasPageParser.extractCasSalt(loginPageHtml).ifEmpty {
            throw AppError.fetch(
                "统一身份认证登录页解析失败：未找到密码加密盐（pwdDefaultEncryptSalt）。" +
                    "页面特征：${CasPageParser.casPageDigest(loginPageHtml)}"
            )
        }
        val lt = CasPageParser.extractHiddenInputValue(loginPageHtml, "lt")
        val execution = CasPageParser.extractHiddenInputValue(loginPageHtml, "execution").ifEmpty {
            throw AppError.fetch(
                "统一身份认证登录页解析失败：未找到 execution 字段。" +
                    "页面特征：${CasPageParser.casPageDigest(loginPageHtml)}"
            )
        }

        val action = CasPageParser.resolveFormAction(loginPageHtml, loginPageUrl)
        val encryptedPassword = WebvpnCrypto.encryptCasPassword(password, salt)
        val params = mutableListOf(
            ParamPair("username", username),
            ParamPair("password", encryptedPassword),
            ParamPair("dllt", "userNamePasswordLogin"),
            ParamPair("execution", execution),
            ParamPair("_eventId", "submit"),
            ParamPair("rmShown", "1")
        )
        if (lt.isNotEmpty()) {
            // 金智当前部署含 lt；CAS 5+ 部分版本已移除该字段，缺省时不提交
            params.add(2, ParamPair("lt", lt))
        }

        val resp = client.postForm(action, params)
        if (resp.status >= 400) {
            throw AppError.fetch("统一身份认证提交失败，状态码: ${resp.status}")
        }
        if (WebvpnSession.isLoginPage(resp.body)) {
            throw AppError.loginFailed("WebVPN 会话未建立或已失效，请检查 WebVPN 登录状态")
        }
        val respAction = CasPageParser.extractFormAction(resp.body)
        if (CasPageParser.isCasLoginPage(resp.body) ||
            respAction.contains("authserver") ||
            resp.finalUrl.contains("authserver")
        ) {
            var reason = CasPageParser.extractCasFailureReason(resp.body)
                .ifEmpty { "未知原因（可能用户名或密码错误，或需要验证码）" }
            if (reason.contains("验证码")) {
                reason += CAPTCHA_HINT
            }
            throw AppError.loginFailed("统一身份认证（CAS）登录失败: $reason")
        }
        Logger.info("CAS 登录成功")
    }

    /**
     * 执行 CAS 登录。
     */
    suspend fun login(
        casLoginUrl: String,
        client: HttpService,
        username: String,
        password: String
    ) {
        if (username.isEmpty() || password.isEmpty()) {
            throw AppError.loginFailed("统一身份认证账号或密码为空")
        }

        val resp = client.get(casLoginUrl)
        if (resp.status >= 400) {
            throw AppError.fetch("获取统一身份认证登录页失败，状态码: ${resp.status}")
        }
        if (WebvpnSession.isLoginPage(resp.body)) {
            throw AppError.loginFailed("WebVPN 会话未建立或已失效，请检查 WebVPN 登录状态")
        }

        if (!CasPageParser.isCasLoginPage(resp.body)) {
            Logger.info("CAS 会话仍然有效，无需重新登录")
            return
        }

        val pageUrl = resp.finalUrl.ifEmpty { casLoginUrl }
        loginCasOnPage(pageUrl, resp.body, client, username, password)
    }

    /**
     * 教务系统直连登录（CAS 版）
     */
    suspend fun loginEams(
        client: HttpService,
        eamsBaseUrl: String,
        username: String,
        password: String
    ) {
        loginEamsVia(
            casLoginUrl = joinBaseUrl(eamsBaseUrl, "homeExt.action"),
            client = client,
            eamsBaseUrl = eamsBaseUrl,
            eamsHomePath = "homeExt.action",
            username = username,
            password = password
        )
    }

    /**
     * 旧版 EAMS 本地账号登录（salt+SHA1）—— 教务若回退登录方式时的兼容路径。
     */
    suspend fun loginEamsLegacy(
        client: HttpService,
        eamsBaseUrl: String,
        username: String,
        password: String,
        eamsHomePath: String = "homeExt.action",
        legacyUrlBase: String = ""
    ) {
        if (username.isEmpty() || password.isEmpty()) {
            throw AppError.loginFailed("教务系统学号或密码为空，请先在设置页填写后再登录")
        }
        val urlBase = legacyUrlBase.ifEmpty { eamsBaseUrl }
        val loginUrl = joinBaseUrl(urlBase, "loginExt.action")
        val resp = client.get(loginUrl)
        if (resp.status >= 400) {
            throw AppError.fetch("获取登录页面失败，状态码: ${resp.status}")
        }
        if (WebvpnSession.isLoginPage(resp.body)) {
            throw AppError.loginFailed("WebVPN 会话未建立或已失效，请检查 WebVPN 登录状态")
        }
        val salt = extractSaltFromScript(resp.body)
            ?: throw AppError.loginFailed("未能从登录页提取 salt，教务可能已改版")

        kotlinx.coroutines.delay(800) // 与原版一致：取 salt 后稍候

        val hashed = WebvpnCrypto.sha1Hex("$salt-$password")
        val post = client.postForm(
            loginUrl,
            listOf(
                ParamPair("username", username),
                ParamPair("password", hashed)
            )
        )
        if (post.status >= 400) {
            throw AppError.loginFailed(
                "登录请求失败（状态码 ${post.status}）。请检查用户名或密码。"
            )
        }
        if (WebvpnSession.isLoginPage(post.body)) {
            throw AppError.loginFailed("WebVPN 会话未建立或已失效，请检查 WebVPN 登录状态")
        }
        // 显式 GET 主页验证（不依赖 POST 重定向后的最终响应体）
        val verify = client.get(joinBaseUrl(urlBase, eamsHomePath))
        if (verify.status >= 400) {
            throw AppError.fetch("教务主页验证失败，状态码: ${verify.status}")
        }
        if (WebvpnSession.isLoginPage(verify.body)) {
            throw AppError.loginFailed("WebVPN 会话未建立或已失效，请检查 WebVPN 登录状态")
        }
        val stillLoginPage = CasPageParser.isCasLoginPage(verify.body) ||
            CasPageParser.isEamsLocalLoginPage(verify.body)
        if (stillLoginPage) {
            throw AppError.loginFailed(
                "教务系统登录未通过。请确认：① 学号与教务密码正确；" +
                    "② 在浏览器中手动登录一次验证；③ 校外网络需开启 WebVPN。"
            )
        }
    }
}
