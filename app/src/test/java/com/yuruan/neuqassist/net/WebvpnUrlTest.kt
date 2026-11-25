package com.yuruan.neuqassist.net

import com.yuruan.neuqassist.data.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * WebVPN 资源 URL 编解码 —— 纯逻辑单测（不依赖 Android 运行时）。
 *
 * 覆盖 `/http/{hex}/`、`/https/{hex}/` 的生成与还原：校外流量下学期的
 * 与课表都只能走这条代理链路，加密段差一个字节，网关就回 404/483。
 */
class WebvpnUrlTest {

    private val base = "https://vpn.neuq.edu.cn"

    // 学校网关实测链接中的加密段（与 Rust 端单元测试向量一致），
    // 改动 AES-CFB 实现或补位规则会立刻在这里失败
    private val jwxtHex = "77726476706e69737468656265737421fae05988693e6d456f468ca88d1b203b"
    private val idsHex = "77726476706e69737468656265737421f9f352d229357d41300d8db9d6562d"

    @Test
    fun encryptHostPort_matchesKnownGatewayVectors() {
        assertEquals(jwxtHex, WebvpnCrypto.encryptHostPort("jwxt.neuq.edu.cn"))
        assertEquals(idsHex, WebvpnCrypto.encryptHostPort("ids.neuq.edu.cn"))
    }

    @Test
    fun buildResourceUrl_prefixesHttpAndHttpsWithSchemeSpecificProxyPath() {
        assertEquals(
            "$base/http/$jwxtHex/eams/loginExt.action",
            WebvpnCrypto.buildResourceUrl(base, "http://jwxt.neuq.edu.cn/eams/loginExt.action")
        )
        val httpsUrl = WebvpnCrypto.buildResourceUrl(
            base,
            "https://jwxt.neuq.edu.cn/eams/homeExt.action?x=1"
        )
        assertTrue(httpsUrl.startsWith("$base/https/"))
        assertTrue(httpsUrl.endsWith("/eams/homeExt.action?x=1"))
    }

    @Test
    fun buildResourceUrl_omitsDefaultPortButKeepsCustomPort() {
        // 默认端口必须与省略端口产生同一加密段（网关按 host[:port] 解密）
        assertEquals(
            "$base/http/$jwxtHex/eams/loginExt.action",
            WebvpnCrypto.buildResourceUrl(base, "http://jwxt.neuq.edu.cn:80/eams/loginExt.action")
        )
        val customPort = WebvpnCrypto.buildResourceUrl(
            base,
            "https://jwxt.neuq.edu.cn:8443/eams/x.action"
        )
        assertTrue(customPort.startsWith("$base/https/"))
        assertFalse(customPort.contains("/https/$jwxtHex/")) // 带非默认端口后加密段应变化
    }

    @Test
    fun buildResourceUrl_rejectsNonHttpSchemes() {
        try {
            WebvpnCrypto.buildResourceUrl(base, "ftp://jwxt.neuq.edu.cn/x")
            fail("非 http/https 目标应抛配置错误")
        } catch (e: AppError) {
            assertEquals(AppError.Kind.Config, e.kind)
        }
    }

    @Test
    fun extractWebvpnInnerPath_decodesHttpAndHttpsProxyPaths() {
        assertEquals(
            "/eams/homeExt.action",
            extractWebvpnInnerPath(
                "https://vpn.neuq.edu.cn/http/$jwxtHex/eams/homeExt.action?sf_request_type=ajax"
            )
        )
        assertEquals(
            "/eams/homeExt.action",
            extractWebvpnInnerPath("/https/$jwxtHex/eams/homeExt.action")
        )
        // 只有加密段、没有内层路径时按根路径处理
        assertEquals("/", extractWebvpnInnerPath("/http/$jwxtHex"))
        // 非代理路径不是 WebVPN 链接
        assertNull(extractWebvpnInnerPath("https://jwxt.neuq.edu.cn/eams/homeExt.action"))
        assertNull(extractWebvpnInnerPath("https://vpn.neuq.edu.cn/login?cas_login=true"))
    }

    @Test
    fun extractWebvpnInnerPath_requiresHexSegment() {
        // 加密段必须是十六进制：/http/login/ 这类普通路径不能被当成代理链接
        assertNull(extractWebvpnInnerPath("/http/login/homeExt.action"))
    }

    @Test
    fun matchCookiePath_checksOuterAndInnerPaths() {
        assertTrue(
            matchCookiePath("/", "https://vpn.neuq.edu.cn/http/$jwxtHex/eams/homeExt.action")
        )
        assertTrue(
            matchCookiePath("/eams", "https://vpn.neuq.edu.cn/http/$jwxtHex/eams/homeExt.action")
        )
        assertFalse(
            matchCookiePath("/eams", "https://vpn.neuq.edu.cn/http/$jwxtHex/other.action")
        )
    }
}
