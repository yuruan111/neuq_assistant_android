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

data class CookieEntry(
    val name: String,
    val value: String,
    val path: String,
    val order: Long
)

/** 提取 URL 或路径中的标准化请求路径（始终以 '/' 开头，剥离协议/主机/查询串/锚点） */
internal fun extractRequestPath(urlOrPath: String): String {
    var s = urlOrPath.trim()
    if (s.isEmpty()) return "/"
    val hashIdx = s.indexOf('#')
    if (hashIdx >= 0) s = s.substring(0, hashIdx)
    val queryIdx = s.indexOf('?')
    if (queryIdx >= 0) s = s.substring(0, queryIdx)
    val schemeIdx = s.indexOf("://")
    if (schemeIdx >= 0) {
        val slashIdx = s.indexOf('/', schemeIdx + 3)
        if (slashIdx < 0) return "/"
        s = s.substring(slashIdx)
    } else if (s.startsWith("//")) {
        val slashIdx = s.indexOf('/', 2)
        if (slashIdx < 0) return "/"
        s = s.substring(slashIdx)
    }
    if (s.isEmpty()) return "/"
    return if (s.startsWith("/")) s else "/$s"
}

/**
 * 若 URL/路径为 WebVPN 代理格式 `/http/{hex}/...`（或 `/https/{hex}/...`），
 * 返回剥离代理段后的内层路径（至少为 `'/'`）；否则返回 `null`。
 */
internal fun extractWebvpnInnerPath(urlOrPath: String): String? {
    val fullPath = extractRequestPath(urlOrPath)
    val m = Regex("""^/https?/[0-9a-f]+(/.*)?$""", RegexOption.IGNORE_CASE).find(fullPath)
        ?: return null
    val inner = m.groupValues[1]
    return inner.ifEmpty { "/" }
}

/** 规范化 Cookie Path 属性（缺失或非 '/' 开头一律回退 `'/'`） */
internal fun normalizeCookiePath(rawPath: String): String {
    val trimmed = rawPath.trim()
    return if (trimmed.isEmpty() || !trimmed.startsWith("/")) "/" else trimmed
}

/** 按 RFC 6265 路径目录边界判断 `targetPath` 是否命中 `cookiePath` */
internal fun isPathPrefixMatch(targetPath: String, cookiePath: String): Boolean {
    val req = targetPath.ifEmpty { "/" }
    val cp = normalizeCookiePath(cookiePath)
    if (cp == "/") return req.startsWith("/")
    if (req == cp) return true
    return if (cp.endsWith("/")) req.startsWith(cp) else req.startsWith("$cp/")
}

/** 按 RFC 6265 Section 5.1.4 从来源 URL 推导默认 Path（响应未显式携带 Path 时使用） */
internal fun defaultCookiePath(sourceUrl: String?): String {
    if (sourceUrl.isNullOrBlank()) return "/"
    val reqPath = extractRequestPath(sourceUrl)
    if (!reqPath.startsWith("/")) return "/"
    val lastSlash = reqPath.lastIndexOf('/')
    return if (lastSlash <= 0) "/" else reqPath.substring(0, lastSlash)
}

/**
 * 判断一条带有 `cookiePath` 的 Cookie 是否应发送给 `requestUrlOrPath`：
 * 同时匹配外层请求路径与 WebVPN `/http/{hex}/` 内层路径，且不绑定首跳 host。
 */
internal fun matchCookiePath(cookiePath: String, requestUrlOrPath: String): Boolean {
    val outerPath = extractRequestPath(requestUrlOrPath)
    if (isPathPrefixMatch(outerPath, cookiePath)) return true
    val innerPath = extractWebvpnInnerPath(outerPath)
    return innerPath != null && isPathPrefixMatch(innerPath, cookiePath)
}

class CookieStore {
    private val jar = LinkedHashMap<String, CookieEntry>()
    private var seqCounter: Long = 0L

    private fun entryKey(name: String, path: String): String = "$name\u0000$path"

    /**
     * 从响应头中的 set-cookie 值更新存储。
     * 按 `(name, path)` 分桶存储，支持 `Max-Age<=0` 删桶及 `Expires` 含逗号防误切。
     */
    fun storeFromResponse(raw: String?, sourceUrl: String? = null) {
        if (raw.isNullOrEmpty()) return
        val fallbackPath = defaultCookiePath(sourceUrl)
        val pieces = raw.split(Regex(""",\s*(?=[^;,=\s]+\s*=)"""))
        synchronized(this) {
            for (piece in pieces) {
                val segments = piece.trim().split(';')
                if (segments.isEmpty()) continue
                val head = segments[0].trim()
                val eq = head.indexOf('=')
                if (eq <= 0) continue
                val name = head.substring(0, eq).trim()
                val value = head.substring(eq + 1).trim()
                if (name.isEmpty()) continue

                var path = fallbackPath
                var expiredByMaxAge = false
                for (i in 1 until segments.size) {
                    val attr = segments[i].trim()
                    if (attr.isEmpty()) continue
                    val attrEq = attr.indexOf('=')
                    if (attrEq <= 0) continue
                    val attrName = attr.substring(0, attrEq).trim().lowercase()
                    val attrVal = attr.substring(attrEq + 1).trim()
                    if (attrName == "path" && attrVal.isNotEmpty()) {
                        path = normalizeCookiePath(attrVal)
                    } else if (attrName == "max-age") {
                        val secs = attrVal.toDoubleOrNull()
                        if (secs != null && !secs.isNaN() && secs <= 0.0) {
                            expiredByMaxAge = true
                        }
                    }
                }

                val key = entryKey(name, path)
                if (expiredByMaxAge) {
                    jar.remove(key)
                    continue
                }
                seqCounter++
                jar[key] = CookieEntry(name = name, value = value, path = path, order = seqCounter)
            }
        }
    }

    /** 多值 set-cookie（HttpURLConnection getHeaderFields 形式） */
    fun storeFromResponse(values: List<String>?, sourceUrl: String? = null) {
        values?.forEach { storeFromResponse(it, sourceUrl) }
    }

    /**
     * 生成请求 Cookie 头（k1=v1; k2=v2），无匹配项时返回空串。
     * 传入 requestUrl 时按外层/内层路径过滤并按最长 Path 优先排序；未传时返回全部 Cookie。
     */
    fun toHeader(requestUrl: String? = null): String {
        val matched = getMatchingEntries(requestUrl)
        if (matched.isEmpty()) return ""
        val seenNames = HashSet<String>()
        val pairs = ArrayList<String>(matched.size)
        for (entry in matched) {
            if (seenNames.add(entry.name)) {
                pairs.add("${entry.name}=${entry.value}")
            }
        }
        return pairs.joinToString("; ")
    }

    /** 返回当前路径匹配的 Cookie 条目（按最长 Path、最新写入排序） */
    fun getMatchingEntries(requestUrl: String? = null): List<CookieEntry> {
        val hasUrl = !requestUrl.isNullOrBlank()
        val matched = synchronized(this) {
            jar.values.filter { entry ->
                !hasUrl || matchCookiePath(entry.path, requestUrl!!)
            }
        }
        return matched.sortedWith(
            compareByDescending<CookieEntry> { it.path.length }
                .thenByDescending { it.order }
        )
    }

    fun clear() = synchronized(this) {
        jar.clear()
    }

    /**
     * 从 "k1=v1; k2=v2" 形式的 Cookie 头导入（用于网页登录会话注入：
     * WebView 登录成功后经 CookieManager 取出的 Cookie 灌入原生请求）
     */
    fun importFromHeader(header: String, sourceUrl: String? = null) {
        val path = if (!sourceUrl.isNullOrBlank()) defaultCookiePath(sourceUrl) else "/"
        synchronized(this) {
            for (part in header.split(";")) {
                val trimmed = part.trim()
                if (trimmed.isEmpty()) continue
                val eq = trimmed.indexOf('=')
                if (eq <= 0) continue
                val name = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim()
                if (name.isNotEmpty()) {
                    seqCounter++
                    jar[entryKey(name, path)] = CookieEntry(
                        name = name,
                        value = value,
                        path = path,
                        order = seqCounter
                    )
                }
            }
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
        var currentMethod = method
        var redirects = 0
        var result: HttpResult? = null

        while (redirects <= MAX_REDIRECTS) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = currentMethod
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
                val cookie = cookieStore.toHeader(currentUrl)
                if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
                if (currentMethod == "POST") {
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    doOutput = true
                }
            }

            try {
                if (currentMethod == "POST") {
                    val body = encodeForm(params)
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val status = conn.responseCode

                // 提取 set-cookie，维持会话（绑定来源 URL 默认 Path）
                conn.headerFields.forEach { (key, values) ->
                    if (key != null && key.equals("Set-Cookie", ignoreCase = true)) {
                        cookieStore.storeFromResponse(values, currentUrl)
                    }
                }

                if (status in 300..399) {
                    val location = conn.getHeaderField("Location")?.trim()
                    if (!location.isNullOrEmpty()) {
                        currentUrl = resolveUrl(currentUrl, location)
                        // 301/302/303 重定向后将 POST 降级为 GET；仅 307/308 保留原方法与请求体
                        if (status != 307 && status != 308) {
                            currentMethod = "GET"
                        }
                        redirects++
                        continue
                    }
                }

                val stream: InputStream? = if (status >= 400) conn.errorStream else conn.inputStream
                val raw = if (stream != null) readAll(stream, conn.contentEncoding) else ""
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
        val loc = location.trim()
        if (loc.isEmpty()) return base
        if (loc.startsWith("http://", ignoreCase = true) ||
            loc.startsWith("https://", ignoreCase = true)
        ) {
            return loc
        }
        val u = URL(base)
        if (loc.startsWith("//")) {
            return "${u.protocol}:$loc"
        }
        val origin = "${u.protocol}://${u.authority}"
        if (loc.startsWith("/")) {
            return "$origin$loc"
        }
        val path = u.path.ifEmpty { "/" }
        if (loc.startsWith("?")) {
            return "$origin$path$loc"
        }
        val lastSlash = path.lastIndexOf('/')
        val dir = if (lastSlash >= 0) path.substring(0, lastSlash + 1) else "/"
        return "$origin$dir$loc"
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
