package com.yuruan.neuqassist.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cookie 按 `(name, path)` 分桶 —— 纯逻辑单测。
 *
 * 学校链路同时存在外层 `vpn.neuq.edu.cn` 与内层 `jwxt.neuq.edu.cn`（WebVPN
 * `/http/{hex}/...`）两套同名 Cookie，绑在首跳 host 或只存 name 都会串味。
 */
class CookieStoreTest {

    private val jwxt = "http://jwxt.neuq.edu.cn/eams/homeExt.action"
    private val vpn = "https://vpn.neuq.edu.cn/"

    @Test
    fun sameCookieName_isBucketedByPath() {
        val store = CookieStore()
        store.storeFromResponse("sid=outer; Path=/", vpn)
        store.storeFromResponse("sid=inner; Path=/eams", jwxt)

        // 两条同名 Cookie 独立共存
        assertEquals(2, store.getMatchingEntries().size)
        // /eams 下取内层票据（最长 Path 优先）
        assertEquals(
            "sid=inner",
            store.toHeader("http://jwxt.neuq.edu.cn/eams/courseTableForStd.action")
        )
        // 其它路径只发外层
        assertEquals("sid=outer", store.toHeader("http://jwxt.neuq.edu.cn/"))
    }

    @Test
    fun differentNames_areFilteredByRequestPath() {
        val store = CookieStore()
        store.storeFromResponse("a=1; Path=/", vpn)
        store.storeFromResponse("b=2; Path=/eams", jwxt)

        assertEquals("b=2; a=1", store.toHeader("http://jwxt.neuq.edu.cn/eams/x.action"))
        assertEquals("a=1", store.toHeader("http://jwxt.neuq.edu.cn/other/x.action"))
    }

    @Test
    fun maxAgeZero_removesOnlyThatNamePathBucket() {
        val store = CookieStore()
        store.storeFromResponse("keep=1; Path=/", vpn)
        store.storeFromResponse("drop=1; Path=/eams", jwxt)
        store.storeFromResponse("drop=gone; Path=/eams; Max-Age=0", jwxt)

        val entries = store.getMatchingEntries()
        assertEquals(1, entries.size)
        assertEquals("keep", entries[0].name)
        assertEquals("/", entries[0].path)
    }

    @Test
    fun expiresWithComma_isNotSplitAsCookieSeparator() {
        val store = CookieStore()
        store.storeFromResponse(
            "exp=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Path=/",
            vpn
        )
        assertEquals("exp=1", store.toHeader())
        assertEquals(1, store.getMatchingEntries().size)
    }

    @Test
    fun defaultCookiePath_isDerivedFromSourceUrl() {
        assertEquals("/eams", defaultCookiePath(jwxt))
        assertEquals("/", defaultCookiePath("http://jwxt.neuq.edu.cn"))
        assertEquals("/", defaultCookiePath(null))
    }

    @Test
    fun importFromHeader_usesSourcePathForAllPairs() {
        val store = CookieStore()
        store.importFromHeader("JSESSIONID=abc; route=node1", jwxt)

        val entries = store.getMatchingEntries()
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.path == "/eams" })
        assertTrue(store.toHeader("http://jwxt.neuq.edu.cn/eams/x.action").contains("JSESSIONID=abc"))
    }

    @Test
    fun innerPathCookie_isSentToProxiedRequests() {
        val store = CookieStore()
        val hex = "deadbeef"
        store.storeFromResponse("eams=1; Path=/eams", jwxt)

        // WebVPN 代理请求：外层路径 /http/{hex}/ 不命中，内层 /eams 命中
        assertEquals(
            "eams=1",
            store.toHeader("https://vpn.neuq.edu.cn/http/$hex/eams/courseTableForStd.action")
        )
        assertEquals("", store.toHeader("https://vpn.neuq.edu.cn/http/$hex/other/x.action"))
    }
}
