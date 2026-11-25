package com.yuruan.neuqassist.net

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections

/**
 * WebVPN 会话票据不被覆写 —— JVM 单测（本地桩网关，无真机/外网依赖）。
 *
 * 网页登录注入 Cookie 后必须先 `markLoggedIn()`：否则原生侧的
 * `ensureFingerprint()` 会重新访问 `/set-fingerprint`，把网关注册的设备指纹
 * 换成新值，已建立的网页会话当场失效（Android 161bbed 的回归点）。
 *
 * 桩网关用 `ServerSocket` 手写最小 HTTP/1.1 响应：Android 单测的编译
 * classpath 是 android.jar，`com.sun.net.httpserver` 不在其中。
 */
class WebvpnSessionTest {

    private var serverSocket: ServerSocket? = null
    private val requestedPaths = Collections.synchronizedList(mutableListOf<String>())

    /** 启动桩网关：任意路径都返回门户页（非 CAS 登录页 → 登录流程视为已登录） */
    private fun startPortal(): Int {
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        val worker = Thread {
            try {
                while (!socket.isClosed) {
                    val client = socket.accept()
                    client.use { conn ->
                        val reader = conn.getInputStream().bufferedReader()
                        val requestLine = reader.readLine() ?: return@use
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val rawPath = requestLine.split(" ").getOrElse(1) { "/" }
                        requestedPaths.add(rawPath.substringBefore('?'))

                        val body = "<html><body><p>portal</p></body></html>".toByteArray(Charsets.UTF_8)
                        val head = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${body.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        val out = conn.getOutputStream()
                        out.write(head.toByteArray(Charsets.UTF_8))
                        out.write(body)
                        out.flush()
                    }
                }
            } catch (e: Exception) {
                // 关停 socket 时 accept 抛异常，属正常退出路径
            }
        }
        worker.isDaemon = true
        worker.start()
        return socket.localPort
    }

    @After
    fun tearDown() {
        serverSocket?.close()
        serverSocket = null
        requestedPaths.clear()
    }

    private fun newSession(port: Int): WebvpnSession {
        val config = WebvpnConfig().apply {
            enabled = true
            baseUrl = "http://127.0.0.1:$port"
            username = "2023123456"
            password = "secret"
            authType = "cas"
        }
        return WebvpnSession(HttpService(), config)
    }

    @Test
    fun markLoggedInSkipsFingerprintAndLoginRequests() {
        val port = startPortal()
        val session = newSession(port)

        session.markLoggedIn()
        runBlocking { session.ensureLogin() }

        assertTrue(
            "已标记登录后不应再访问网关，实际请求: $requestedPaths",
            requestedPaths.isEmpty()
        )
    }

    @Test
    fun loginWithoutMarkRequestsFingerprintBeforeLoginPage() {
        val port = startPortal()
        val session = newSession(port)

        // 未注入网页会话时必须走原生登录：先指纹、再登录页
        runBlocking { session.ensureLogin() }

        assertEquals(listOf("/set-fingerprint", "/login"), requestedPaths.toList())
    }

    @Test
    fun invalidateThenLoginRequestsGatewayAgain() {
        val port = startPortal()
        val session = newSession(port)

        session.markLoggedIn()
        session.invalidate()
        runBlocking { session.ensureLogin() }

        // 会话失效后重新登录必须重新建立指纹，而不是复用旧票据
        assertEquals(listOf("/set-fingerprint", "/login"), requestedPaths.toList())
    }
}
