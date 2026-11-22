package com.yuruan.neuqassist.net

import com.yuruan.neuqassist.data.AppError
import com.yuruan.neuqassist.data.Logger
import com.yuruan.neuqassist.data.RawClassroomData
import com.yuruan.neuqassist.data.RetryConfig
import com.yuruan.neuqassist.data.withRetry
import com.yuruan.neuqassist.net.HttpService
import com.yuruan.neuqassist.net.ParamPair
import com.yuruan.neuqassist.net.WebvpnCrypto
import com.yuruan.neuqassist.net.WebvpnSession
import com.yuruan.neuqassist.net.extractSaltFromScript
import com.yuruan.neuqassist.net.parseClassroomHtml

/**
 * 教务系统客户端 —— 移植自 HarmonyOS 端 service/ClassroomClient.ets（Rust src/client.rs）
 *
 * 登录流程：GET loginExt.action 提取 salt → SHA1(salt-password) →
 * POST loginExt.action 表单 → 校验会话有效。
 * 查询流程：POST classroom/apply/free!search.action（与 Rust 端参数完全一致），
 * 内含 WebVPN 会话失效与教务系统会话过期的自动恢复。
 */
class ClassroomClient(private val config: ClassroomQueryConfig) {

    private val client = HttpService().apply { timeoutMs = 45000 }

    private val webvpn: WebvpnSession

    private val retryConfig = RetryConfig()

    init {
        val webvpnConfig = WebvpnConfig().apply {
            enabled = config.webvpnEnabled
            baseUrl = config.webvpnBaseUrl
            username = if (config.webvpnUsername.isEmpty()) config.username
            else config.webvpnUsername
            password = if (config.webvpnPassword.isEmpty()) config.password
            else config.webvpnPassword
            authType = config.webvpnAuthType
        }
        if (webvpnConfig.enabled && !webvpnConfig.baseUrl.startsWith("https://")) {
            throw AppError.config("启用 WebVPN 时，WebVPN 地址必须以 https:// 开头")
        }
        webvpn = WebvpnSession(client, webvpnConfig)
    }

    /** 注入来自网页登录的会话 Cookie（"k1=v1; k2=v2" 形式），跳过脚本登录 */
    fun applyWebSession(cookieHeader: String) {
        client.cookieStore.clear()
        client.cookieStore.importFromHeader(cookieHeader)
        Logger.info("已注入网页会话 Cookie，长度: ${cookieHeader.length}")
    }

    /** 登录教务系统（内含 WebVPN 会话建立，如启用） */
    suspend fun login(username: String, password: String) {
        webvpn.ensureLogin()

        // 2026-10 实测：教务系统已切换为统一身份认证（CAS）——
        // 旧的本地账号表单（salt+SHA1）提交后，homeExt.action 会被 302 到
        // ids.neuq.edu.cn/authserver。因此登录改走 CasAuth：
        //   ① 访问 homeExt → 302 到 CAS（拿到 service 与 CAS 会话）
        //   ② CAS AES 加密密码提交
        //   ③ 302 回业务系统，会话建立
        // CasAuth.loginEams 内部会先探测教务是否真的启用了 CAS，
        // 未启用时自动回退旧的 salt+SHA1 流程（兼容教务回退登录方式的情况）。
        val casLoginUrl = webvpn.proxiedAsync(CasAuth.EAMS_CAS_LOGIN_URL)
        CasAuth.loginEamsVia(
            casLoginUrl = casLoginUrl,
            client = client,
            eamsBaseUrl = config.baseUrl,
            eamsHomePath = "homeExt.action",
            username = username,
            password = password
        )
        Logger.info("教务登录成功（含 WebVPN 前缀=${config.webvpnEnabled}）")
    }

    /** 获取登录页面并提取 salt */
    suspend fun getSalt(): String {
        webvpn.ensureLogin()

        val url = webvpn.proxiedAsync("${config.baseUrl}loginExt.action")
        var resp = client.get(url)

        if (resp.status >= 400) {
            throw AppError.fetch("获取登录页面失败，状态码: ${resp.status}")
        }

        // WebVPN 会话失效自动恢复：被重定向回登录页则重登录后重试一次
        if (webvpn.recoverIfKicked(resp.body)) {
            resp = client.get(url)
            if (resp.status >= 400) {
                throw AppError.fetch("获取登录页面失败，状态码: ${resp.status}")
            }
        }

        val salt = extractSaltFromScript(resp.body)
            ?: throw AppError.fetch("获取登录页面失败：未找到 salt（可能响应为错误页）")
        return salt
    }

    /**
     * 查询空闲教室 —— 对应 Rust search_free_classrooms
     *
     * 空结果（表格无数据行）返回空数组；响应异常（登录页/会话过期/无表格）
     * 抛出错误，绝不把异常响应当作合法空结果。
     */
    suspend fun searchFreeClassrooms(
        date: String,
        timeBegin: Int,
        timeEnd: Int
    ): List<RawClassroomData> {
        webvpn.ensureLogin()

        val url = webvpn.proxiedAsync(
            "${config.baseUrl}classroom/apply/free!search.action"
        )

        fun makeParams() = listOf(
            ParamPair("classroom.building.id", ""),
            ParamPair("cycleTime.dateBegin", date),
            ParamPair("cycleTime.dateEnd", date),
            ParamPair("timeBegin", "$timeBegin"),
            ParamPair("timeEnd", "$timeEnd"),
            ParamPair("pageSize", "1000"),
            ParamPair("classroom.type.id", ""),
            ParamPair("classroom.campus.id", ""),
            ParamPair("seats", ""),
            ParamPair("classroom.name", ""),
            ParamPair("cycleTime.cycleCount", "1"),
            ParamPair("cycleTime.cycleType", "1"),
            ParamPair("roomApplyTimeType", "0")
        )

        var resp = client.postForm(url, makeParams())

        if (resp.status >= 400) {
            throw AppError.fetch("查询空闲教室失败，状态码: ${resp.status}")
        }

        // WebVPN 会话失效自动恢复：被重定向回登录页则重登录后重试一次
        if (webvpn.recoverIfKicked(resp.body)) {
            resp = client.postForm(url, makeParams())
            if (resp.status >= 400) {
                throw AppError.fetch("查询空闲教室失败，状态码: ${resp.status}")
            }
        }

        // 二次防护：仍被重定向到登录页时绝不能当作"空结果"处理
        if (WebvpnSession.isLoginPage(resp.body)) {
            throw AppError.fetch("响应被重定向到 WebVPN 登录页，会话恢复失败")
        }

        // 教务系统会话过期自动恢复（同账号重复登录或超时被踢）
        if (resp.body.contains("会话已经被过期")) {
            Logger.warn("教务系统会话过期，重新登录后重试")
            login(config.username, config.password)
            resp = client.postForm(url, makeParams())
            if (resp.status >= 400) {
                throw AppError.fetch("查询空闲教室失败，状态码: ${resp.status}")
            }
        }

        return parseClassroomHtml(resp.body)
    }
}

/** 查询配置（对应 Rust AppConfig 的网络相关子集） */
class ClassroomQueryConfig {
    var username: String = ""
    var password: String = ""
    var baseUrl: String = "http://jwxt.neuq.edu.cn/eams/"
    var webvpnEnabled: Boolean = false
    var webvpnBaseUrl: String = "https://vpn.neuq.edu.cn"
    var webvpnUsername: String = ""
    var webvpnPassword: String = ""
    var webvpnAuthType: String = "cas"
}
