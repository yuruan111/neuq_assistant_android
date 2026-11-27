package com.yuruan.neuqassist.ui

import com.yuruan.neuqassist.ui.screens.isWebLoginPendingUrl
import com.yuruan.neuqassist.ui.screens.isWebvpnLoggedInUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeLoginGuardTest {

    @Test
    fun detectsWebvpnAndCasLoginUrlsAsLoginPending() {
        assertTrue(isEamsLoginPage("", "https://vpn.neuq.edu.cn/login"))
        assertTrue(isEamsLoginPage("", "https://vpn.neuq.edu.cn/login?cas_login=true"))
        assertTrue(isEamsLoginPage("", "https://vpn.neuq.edu.cn/fingerprint?cas_login=true"))
        assertTrue(isEamsLoginPage("", "http://ids.neuq.edu.cn/authserver/login?service=http%3A%2F%2Fjwxt.neuq.edu.cn%2Feams%2FhomeExt.action"))
    }

    @Test
    fun detectsLoginMarkersInPageBody() {
        assertTrue(isEamsLoginPage("<input id=\"pwdDefaultEncryptSalt\" value=\"abc\"/>", "https://vpn.neuq.edu.cn/"))
        assertTrue(isEamsLoginPage("<form action=\"/do-login\" method=\"post\"></form>", "https://vpn.neuq.edu.cn/"))
        assertTrue(isEamsLoginPage("<form id=\"casLoginForm\"></form>", "https://vpn.neuq.edu.cn/"))
        assertTrue(isEamsLoginPage("form['password'] = sha1(salt);", "http://jwxt.neuq.edu.cn/eams/"))
    }

    @Test
    fun allowsValidEamsPages() {
        val html = "<html><body><div id='menu'>教务系统</div></body></html>"
        assertFalse(isEamsLoginPage(html, "https://vpn.neuq.edu.cn/http/77726476706e69737468656265737421f9f352d229357d41300d8db9d6562d/eams/homeExt.action"))
        assertFalse(isEamsLoginPage(html, "http://jwxt.neuq.edu.cn/eams/homeExt.action"))
    }

    @Test
    fun distinguishesWebvpnLoggedInFromPendingUrls() {
        assertTrue(isWebvpnLoggedInUrl("https://vpn.neuq.edu.cn/"))
        assertFalse(isWebLoginPendingUrl("https://vpn.neuq.edu.cn/"))

        val eamsViaVpn = "https://vpn.neuq.edu.cn/http/77726476706e69737468656265737421f9f352d229357d41300d8db9d6562d/eams/homeExt.action"
        assertTrue(isWebvpnLoggedInUrl(eamsViaVpn))
        assertFalse(isWebLoginPendingUrl(eamsViaVpn))

        val pendingUrls = listOf(
            "https://vpn.neuq.edu.cn/login",
            "https://vpn.neuq.edu.cn/login?cas_login=true",
            "https://vpn.neuq.edu.cn/fingerprint",
            "https://vpn.neuq.edu.cn/https/77726476706e69737468656265737421f9f352d229357d41300d8db9d6562d/authserver/login"
        )
        for (url in pendingUrls) {
            assertFalse("Expected loggedIn=false for $url", isWebvpnLoggedInUrl(url))
            assertTrue("Expected pending=true for $url", isWebLoginPendingUrl(url))
        }
    }
}
