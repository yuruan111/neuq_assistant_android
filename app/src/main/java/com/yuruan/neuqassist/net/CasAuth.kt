package com.yuruan.neuqassist.net

import com.yuruan.neuqassist.data.AppError
import com.yuruan.neuqassist.data.Logger
import com.yuruan.neuqassist.data.extractJsonString

/**
 * 金智统一身份认证（CAS authserver）登录 —— 从 WebvpnSession.loginCas 抽出的
 * 公共实现，供两处复用：
 *
 * 1. WebVPN 网关登录（vpn.neuq.edu.cn/login?cas_login=true 背后的 authserver）
 * 2. 教务系统直连登录 —— 2026-10 实测教务已切换为 CAS：
 *    POST /eams/loginExt.action（本地账号表单）后 GET /eams/homeExt.action
 *    会被 302 到 ids.neuq.edu.cn/authserver/login?service=...，
 *    说明本地账号已不被接受，必须走统一身份认证。
 *
 * 协议（金智 authserver，encrypt.wisedu.js 前端加密）：
 *   登录页含 pwdDefaultEncryptSalt（AES 盐）+ lt + execution 隐藏域；
 *   提交 username / password(AES-128-CBC, base64) / lt / dllt / execution / _eventId。
 *   成功：302 回 service；失败：重新渲染登录页（<span id="msg"> 给出原因）。
 */
object CasAuth {

    /** 教务系统统一身份认证地址（实测 homeExt.action 302 跳转目标） */
    const val EAMS_CAS_LOGIN_URL =
        "http://ids.neuq.edu.cn/authserver/login" +
            "?service=http%3A%2F%2Fjwxt.neuq.edu.cn%2Feams%2FhomeExt.action"

    /** 提取 `<input type="hidden" name="xxx" value="yyy">` 的 value */
    private fun extractHiddenInput(html: String, name: String): String? {
        val m = Regex("name=\"$name\"\\s+value=\"([^\"]+)\"").find(html) ?: return null
        return m.groupValues[1]
    }

    /** 提取加密盐：兼容隐藏字段（name=/id=）与 JS 变量两种形式 */
    private fun extractHiddenOrJs(html: String, name: String): String? {
        val patterns = listOf(
            "name=\"$name\"\\s+value=\"([^\"]+)\"",
            "id=\"$name\"\\s+value=\"([^\"]+)\"",
            "$name\\s*=\\s*\"([^\"]+)\""
        )
        for (p in patterns) {
            val m = Regex(p).find(html)
            if (m != null && m.groupValues.size > 1 && m.groupValues[1].isNotEmpty()) {
                return m.groupValues[1]
            }
        }
        return null
    }

    /** 从 authserver 错误页提取提示（`<span id="msg" class="errMsg">` 等） */
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

    /**
     * 教务系统登录（CAS 版，支持经 WebVPN 代理）。
     *
     * 与 [loginEams] 的差异：WebVPN 模式下所有 URL 都要经 `proxiedAsync` 加密前缀，
     * CAS 登录页也在代理之后（表单 action 是 /http/{加密段}/... 形式），
     * 故把「探测 → CAS 登录 → 验证」的入口 URL 由调用方给定。
     *
     * @param casLoginUrl  经代理（或直连）的 CAS 登录页地址
     * @param eamsHomePath EAMS 主页路径（如 "homeExt.action"），用于登录后验证
     */
    suspend fun loginEamsVia(
        casLoginUrl: String,
        client: HttpService,
        eamsBaseUrl: String,
        eamsHomePath: String,
        username: String,
        password: String
    ) {
        // ① 访问教务主页 → 302 到 CAS 登录页（Cookie 随 HttpService 保存）
        val resp = client.get(casLoginUrl)
        val landedCas = resp.body.contains("casLoginForm") ||
            resp.body.contains("pwdDefaultEncryptSalt")

        if (!landedCas) {
            // 未被弹到 CAS：教务可能仍是旧版本地登录（salt+SHA1），回退兼容
            Logger.info("教务未启用 CAS 跳转，回退本地账号登录流程")
            loginEamsLegacy(client, eamsBaseUrl, username, password)
            return
        }

        // ② CAS 登录：用 302 链实际落点作为登录页地址（service 参数动态跟随教务，
        //    不写死——教务换 service 地址时无需改代码）
        val realLoginPageUrl = resp.finalUrl.ifEmpty { casLoginUrl }
        loginCasOnPage(realLoginPageUrl, resp.body, client, username, password)

        // ③ 验证：再次访问教务主页，确认不再被弹回登录页
        val verify = client.get(casLoginUrl)
        val stillLogin = verify.body.contains("casLoginForm") ||
            Regex("name=[\"']username[\"']").containsMatchIn(verify.body)
        if (verify.status < 400 && stillLogin) {
            throw AppError.loginFailed(
                "教务系统登录未通过：CAS 认证后仍被弹回登录页。" +
                    "请确认账号密码（统一身份认证账号即学号，密码与教务本地密码可能不同），" +
                    "或在浏览器中手动登录一次验证。"
            )
        }
    }

    /**
     * 在已拿到的 authserver 登录页 HTML 上执行 CAS 登录。
     * @param loginPageUrl 登录页地址（表单 action 的补全基准）
     * @param loginPageHtml 登录页 HTML（已含 salt/lt/execution）
     */
    private suspend fun loginCasOnPage(
        loginPageUrl: String,
        loginPageHtml: String,
        client: HttpService,
        username: String,
        password: String
    ) {
        val html = loginPageHtml

        val salt = extractHiddenOrJs(html, "pwdDefaultEncryptSalt")
            ?: throw AppError.fetch("统一身份认证登录页解析失败：未找到密码加密盐")
        val lt = extractHiddenInput(html, "lt")
            ?: throw AppError.fetch("统一身份认证登录页解析失败：未找到 lt")
        val execution = extractHiddenInput(html, "execution")
            ?: throw AppError.fetch("统一身份认证登录页解析失败：未找到 execution")

        var action = loginPageUrl
        Regex("<form[^>]*action=\"([^\"]+)\"[^>]*>", RegexOption.IGNORE_CASE)
            .find(html)?.let { m ->
                var act = m.groupValues[1]
                if (act.startsWith("/")) {
                    val host = Regex("^(https?://[^/]+)").find(loginPageUrl)?.groupValues?.get(1)
                        ?: loginPageUrl
                    act = "$host$act"
                }
                action = act
            }

        val encryptedPassword = WebvpnCrypto.encryptCasPassword(password, salt)
        val params = listOf(
            ParamPair("username", username),
            ParamPair("password", encryptedPassword),
            ParamPair("lt", lt),
            ParamPair("dllt", "userNamePasswordLogin"),
            ParamPair("execution", execution),
            ParamPair("_eventId", "submit"),
            ParamPair("rmShown", "1")
        )

        val resp = client.postForm(action, params)
        if (resp.body.contains("casLoginForm")) {
            var reason = extractCasError(resp.body)
                ?: "未知原因（可能用户名或密码错误，或需要验证码）"
            if (reason.contains("验证码")) {
                reason += "（IP 短时间内登录失败次数过多，请稍后重试或更换网络环境）"
            }
            throw AppError.loginFailed("统一身份认证（CAS）登录失败: $reason")
        }
        Logger.info("CAS 登录成功")
    }

    /**
     * 执行 CAS 登录。
     *
     * @param casLoginUrl authserver 登录页地址（含 service 参数）
     * @param client      复用的 HttpService（Cookie 由调用方管理，登录态落在其中）
     * @param username    学号/工号（统一身份认证账号）
     * @param password    统一身份认证密码（明文，方法内做 AES 加密）
     *
     * 成功：返回（Cookie 已写入 client.cookieStore，调用方可继续访问 service 业务系统）
     * 失败：抛 AppError.loginFailed / AppError.fetch
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

        var resp = client.get(casLoginUrl)
        var html = resp.body

        // 已处于登录态（cookie 有效）时 authserver 直接 302 回 service，
        // 页面不含登录表单 —— 此时无需重复登录
        if (!html.contains("casLoginForm")) {
            Logger.info("CAS 会话仍然有效，无需重新登录")
            return
        }

        val salt = extractHiddenOrJs(html, "pwdDefaultEncryptSalt")
            ?: throw AppError.fetch("统一身份认证登录页解析失败：未找到密码加密盐")
        val lt = extractHiddenInput(html, "lt")
            ?: throw AppError.fetch("统一身份认证登录页解析失败：未找到 lt")
        val execution = extractHiddenInput(html, "execution")
            ?: throw AppError.fetch("统一身份认证登录页解析失败：未找到 execution")

        // 表单 action：authserver 登录页内 <form> 的 action 属性（相对路径补全到 host）
        var action = casLoginUrl
        Regex("<form[^>]*action=\"([^\"]+)\"[^>]*>", RegexOption.IGNORE_CASE)
            .find(html)?.let { m ->
                var act = m.groupValues[1]
                if (act.startsWith("/")) {
                    val host = Regex("^(https?://[^/]+)").find(casLoginUrl)?.groupValues?.get(1)
                        ?: casLoginUrl
                    act = "$host$act"
                }
                action = act
            }

        val encryptedPassword = WebvpnCrypto.encryptCasPassword(password, salt)
        val params = listOf(
            ParamPair("username", username),
            ParamPair("password", encryptedPassword),
            ParamPair("lt", lt),
            ParamPair("dllt", "userNamePasswordLogin"),
            ParamPair("execution", execution),
            ParamPair("_eventId", "submit"),
            ParamPair("rmShown", "1")
        )

        resp = client.postForm(action, params)
        val body = resp.body

        // 失败时 authserver 会重新渲染登录页；成功时 302 回 service
        // （HttpService 会跟随 302，故最终 body 若仍含 casLoginForm 即为失败）
        if (body.contains("casLoginForm")) {
            var reason = extractCasError(body)
                ?: "未知原因（可能用户名或密码错误，或需要验证码）"
            if (reason.contains("验证码")) {
                reason += "（IP 短时间内登录失败次数过多，请稍后重试或更换网络环境）"
            }
            throw AppError.loginFailed("统一身份认证（CAS）登录失败: $reason")
        }

        Logger.info("CAS 登录成功")
    }

    /**
     * 教务系统直连登录（CAS 版）—— 替代原 salt+SHA1 的 EAMS 本地账号登录。
     *
     * 流程：
     *   ① GET homeExt.action → 302 到 authserver（拿到 service 参数与 CAS 会话 Cookie）
     *   ② CAS login（AES 加密密码提交）
     *   ③ CAS 302 回 service → 会话建立，后续 EAMS 请求直接可用
     *
     * @return true=登录成功；false=会话仍有效（无需重复登录，视为成功）
     * @throws AppError.loginFailed 账号密码错误等
     */
    suspend fun loginEams(
        client: HttpService,
        eamsBaseUrl: String,
        username: String,
        password: String
    ) {
        // ① 访问教务主页，被 302 到 CAS 登录页（HttpService 已自动跟随并保存 Cookie）
        val resp = client.get("${eamsBaseUrl}homeExt.action")
        val landedCas = resp.body.contains("casLoginForm") ||
            resp.body.contains("authserver")

        if (!landedCas) {
            // 未被弹到 CAS：可能仍是旧版本地登录（salt+SHA1），走原流程兼容
            Logger.info("教务未启用 CAS 跳转，回退本地账号登录流程")
            loginEamsLegacy(client, eamsBaseUrl, username, password)
            return
        }

        // ② CAS 登录（body 已是 authserver 登录页，直接复用当前 URL）
        login(EAMS_CAS_LOGIN_URL, client, username, password)

        // ③ 验证：访问主页确认已进入业务系统（不再被弹回登录页）
        val verify = client.get("${eamsBaseUrl}homeExt.action")
        val stillLogin = verify.body.contains("casLoginForm") ||
            verify.body.contains("name=\"username\"")
        if (verify.status < 400 && stillLogin) {
            throw AppError.loginFailed(
                "教务系统登录未通过：CAS 认证后仍被弹回登录页。" +
                    "请确认账号密码（教务密码与统一身份认证密码可能不同），" +
                    "或在浏览器中手动登录一次验证。"
            )
        }
    }

    /**
     * 旧版 EAMS 本地账号登录（salt+SHA1）—— 教务若回退登录方式时的兼容路径。
     * 逻辑与原 ClassroomClient.login 一致。
     */
    suspend fun loginEamsLegacy(
        client: HttpService,
        eamsBaseUrl: String,
        username: String,
        password: String
    ) {
        val resp = client.get("${eamsBaseUrl}loginExt.action")
        if (resp.status >= 400) {
            throw AppError.fetch("获取登录页面失败，状态码: ${resp.status}")
        }
        val salt = extractSaltFromScript(resp.body)
            ?: throw AppError.loginFailed("未能从登录页提取 salt，教务可能已改版")

        kotlinx.coroutines.delay(800) // 与原版一致：取 salt 后稍候

        val hashed = WebvpnCrypto.sha1Hex("$salt-$password")
        val post = client.postForm(
            "${eamsBaseUrl}loginExt.action",
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
        // 显式 GET 主页验证（不依赖 POST 重定向后的最终响应体）
        val verify = client.get("${eamsBaseUrl}homeExt.action")
        val stillLoginPage = verify.body.contains("form['password']") ||
            Regex("name=[\"']username[\"']").containsMatchIn(verify.body)
        if (verify.status < 400 && stillLoginPage) {
            throw AppError.loginFailed(
                "教务系统登录未通过。请确认：① 学号与教务密码正确；" +
                    "② 在浏览器中手动登录一次验证；③ 校外网络需开启 WebVPN。"
            )
        }
    }
}
