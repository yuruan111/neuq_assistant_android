package com.yuruan.neuqassist.net

import com.yuruan.neuqassist.data.AppError
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection

/**
 * HTTP 服务封装 —— 移植自 HarmonyOS 端 network/HttpService.ets + CookieStore.ets
 * （对应 Rust src/client.rs 中 reqwest::Client 的能力子集）
 *
 * - 手动 Cookie 管理（reqwest cookie_provider 的等价实现）
 * - 表单 POST / GET
 * - 浏览器 UA（与 Rust 端一致，避免被教务系统按 UA 拦截）
 * - 自动跟随 302 重定向（上限 20 次，与 HarmonyOS 端 maxLimit:20 对齐）
 */
data class ParamPair(val name: String, val value: String)

data class HttpResult(
    val status: Int,
    val body: String,
    /** 重定向链走完后的最终 URL（CAS 登录依赖它确定 service 回跳地址） */
    val finalUrl: String = ""
)

class CookieStore {
    private val cookies = LinkedHashMap<String, String>()

    /**
     * 从响应头中的 set-cookie 值更新存储。
     * set-cookie 可能是单个字符串（多个 cookie 以逗号拼接）或字符串数组。
     */
    fun storeFromResponse(raw: String?) {
        if (raw.isNullOrEmpty()) return
        // 按 RFC 6265 的 Expires 日期中的逗号做拆分规避：简化处理——
        // 先按逗号拆分，若片段中出现 '=' 且不含 'Expires'/' expires' 关键字则独立成 cookie
        val segments = raw.split(Regex(",(?=[^;]+?=)"))
        for (seg in segments) {
            val trimmed = seg.trim()
            if (trimmed.isEmpty()) continue
            val firstSemi = trimmed.indexOf(';')
            val pair = if (firstSemi >= 0) trimmed.substring(0, firstSemi) else trimmed
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            if (name.isNotEmpty()) cookies[name] = value
        }
    }

    /** 多值 set-cookie（HttpURLConnection getHeaderFields 形式） */
    fun storeFromResponse(values: List<String>?) {
        values?.forEach { storeFromResponse(it) }
    }

    /** 生成请求 Cookie 头（k1=v1; k2=v2），无 cookie 时返回空串 */
    fun toHeader(): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    fun clear() = cookies.clear()

    /**
     * 从 "k1=v1; k2=v2" 形式的 Cookie 头导入（用于网页登录会话注入：
     * WebView 登录成功后经 CookieManager 取出的 Cookie 灌入原生请求）
     */
    fun importFromHeader(header: String) {
        for (part in header.split(";")) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            val name = trimmed.substring(0, eq).trim()
            val value = trimmed.substring(eq + 1).trim()
            if (name.isNotEmpty()) cookies[name] = value
        }
    }
}

class HttpService(val cookieStore: CookieStore = CookieStore()) {

    var timeoutMs: Int = 45000

    suspend fun get(url: String): HttpResult = request(url, "GET", emptyList())

    suspend fun postForm(url: String, params: List<ParamPair>): HttpResult =
        request(url, "POST", params)

    private suspend fun request(
        url: String,
        method: String,
        params: List<ParamPair>
    ): HttpResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        var currentUrl = url
        var redirects = 0
        var result: HttpResult? = null

        while (redirects <= MAX_REDIRECTS) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false // 手动处理：需要在跳转时手动带 Cookie
                doInput = true
                useCaches = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                setRequestProperty("Accept-Encoding", "identity")
                val cookie = cookieStore.toHeader()
                if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
                if (method == "POST") {
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    doOutput = true
                }
            }

            try {
                if (method == "POST") {
                    val body = encodeForm(params)
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val status = conn.responseCode

                // 提取 set-cookie，维持会话
                conn.headerFields.forEach { (key, values) ->
                    if (key != null && key.equals("Set-Cookie", ignoreCase = true)) {
                        cookieStore.storeFromResponse(values)
                    }
                }

                if (status in 301..308) {
                    val location = conn.getHeaderField("Location") ?: break
                    currentUrl = resolveUrl(currentUrl, location)
                    redirects++
                    conn.disconnect()
                    continue
                }

                val stream: InputStream = if (status >= 400) conn.errorStream ?: conn.inputStream
                else conn.inputStream
                val raw = readAll(stream, conn.contentEncoding)
                result = HttpResult(status, raw, finalUrl = currentUrl)
                break
            } catch (e: java.net.SocketTimeoutException) {
                throw AppError.fetch("网络请求超时，请检查网络连接后重试")
            } catch (e: java.net.UnknownHostException) {
                throw AppError.fetch("无法连接服务器（DNS 解析失败），请检查网络连接")
            } catch (e: java.io.IOException) {
                val msg = e.message ?: ""
                throw AppError.fetch(
                    if (msg.contains("write the received data") ||
                        msg.contains("Failed to write") ||
                        msg.contains("Connection reset")
                    ) {
                        "网络传输被中断（服务器提前关闭了连接）。" +
                            "校外网络请开启 WebVPN 并重新网页登录；校内网络请稍后重试。"
                    } else {
                        "网络请求失败: $msg"
                    }
                )
            } finally {
                conn.disconnect()
            }
        }

        val r = result
            ?: throw AppError.fetch("重定向次数超出上限（${MAX_REDIRECTS} 次），链接可能失效")
        if (r.status == 483) {
            // 教务系统拒绝校外直连（Rust 端同款错误提示）
            throw AppError.fetch("状态码: 483 —— 教务系统拒绝校外直连，请在设置中启用 WebVPN")
        }
        r
    }

    private fun resolveUrl(base: String, location: String): String {
        if (location.startsWith("http://") || location.startsWith("https://")) return location
        val u = URL(base)
        return if (location.startsWith("/")) {
            "${u.protocol}://${u.authority}$location"
        } else {
            val path = u.path
            val dir = path.substring(0, path.lastIndexOf('/') + 1)
            "${u.protocol}://${u.authority}$dir$location"
        }
    }

    private fun readAll(input: InputStream, encoding: String?): String {
        val stream = if (encoding?.contains("gzip", true) == true) GZIPInputStream(input) else input
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }

    companion object {
        private const val MAX_REDIRECTS = 20

        const val USER_AGENT: String =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** application/x-www-form-urlencoded 编码 */
        fun encodeForm(params: List<ParamPair>): String =
            params.joinToString("&") {
                "${URLEncoder.encode(it.name, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
            }
    }
}
