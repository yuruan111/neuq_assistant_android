package com.yuruan.neuqassist.net

import android.util.Base64
import com.yuruan.neuqassist.data.AppError
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 深澜（srun wengine）WebVPN 加密原语 —— 移植自 HarmonyOS 端 crypto/WebvpnCrypto.ets
 * （移植自 Rust src/webvpn/crypto.rs）
 *
 * 1. 资源 URL 加密段：AES-128-CFB（key/iv 均为 "wrdvpnisthebest!" 的 ASCII）
 * 2. 统一身份认证（CAS）密码加密：AES-128-CBC-PKCS7，输出 base64，
 *    与金智 encrypt.wisedu.js 前端行为一致。
 *
 * 已知测试向量（来自 Rust 单元测试，实测学校网关生成链接）：
 *   encryptHostPort("ids.neuq.edu.cn")
 *     = "77726476706e69737468656265737421f9f352d229357d41300d8db9d6562d"
 *   encryptHostPort("jwxt.neuq.edu.cn")
 *     = "77726476706e69737468656265737421fae05988693e6d456f468ca88d1b203b"
 */
object WebvpnCrypto {

    private const val DEFAULT_KEY = "wrdvpnisthebest!"

    /** 深澜 WebVPN 默认 AES-128-CFB 的 key/iv（16 字节） */
    private fun defaultIvBytes(): ByteArray = strToBytes(DEFAULT_KEY)

    fun strToBytes(s: String): ByteArray = s.toByteArray(Charsets.US_ASCII)

    fun bytesToHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xFF
            sb.append(if (v < 16) "0${v.toString(16)}" else v.toString(16))
        }
        return sb.toString()
    }

    /** SHA1 摘要（hex 输出），对应 Rust sha1::Sha1 */
    fun sha1Hex(data: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val digest = md.digest(data.toByteArray(Charsets.UTF_8))
        return bytesToHex(digest)
    }

    /** 通过 AES-ECB 单块加密获取 16 字节密钥流（NoPadding，输入必须为 16 字节） */
    private fun aesEcbEncryptBlock(keyBytes: ByteArray, block: ByteArray): ByteArray {
        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(block)
    }

    /**
     * 将 host[:port] 按深澜规则加密并编码 —— 对应 Rust encrypt_host_port
     *
     * 明文右补 ASCII '0' 到 16 字节倍数，AES-128-CFB 加密后截断到原明文长度，
     * 输出 hex(iv) + hex(密文)。
     */
    fun encryptHostPort(hostPort: String): String {
        val plainBytes = strToBytes(hostPort)
        val plainLen = plainBytes.size // host[:port] 恒为 ASCII
        if (plainLen == 0) throw AppError.config("WebVPN 目标主机名为空")
        // 右补 ASCII '0' 到 16 字节倍数
        val paddedLen = ((plainLen + 15) / 16) * 16
        val padded = ByteArray(paddedLen)
        System.arraycopy(plainBytes, 0, padded, 0, plainLen)
        for (i in plainLen until paddedLen) {
            padded[i] = 0x30 // ASCII '0'
        }

        val keyBytes = strToBytes(DEFAULT_KEY)
        val iv = defaultIvBytes()

        // 手工 CFB-128：keystream = AES_ECB_encrypt(feedback)，ciphertext = P ^ keystream
        val feedback = iv.copyOf()
        val result = ByteArray(padded.size)
        var offset = 0
        while (offset < padded.size) {
            val keystream = aesEcbEncryptBlock(keyBytes, feedback)
            for (i in 0 until 16) {
                if (offset + i >= padded.size) break
                result[offset + i] = (padded[offset + i].toInt() xor keystream[i].toInt()).toByte()
            }
            System.arraycopy(result, offset, feedback, 0, 16)
            offset += 16
        }

        return bytesToHex(iv) + bytesToHex(result.copyOfRange(0, plainLen))
    }

    /**
     * 把教务系统直连 URL 转换为经 WebVPN 代理的 URL —— 对应 Rust build_resource_url
     *
     * http://jwxt.neuq.edu.cn/eams/loginExt.action
     *   -> https://vpn.neuq.edu.cn/http/{加密段}/eams/loginExt.action
     */
    fun buildResourceUrl(webvpnBase: String, targetUrl: String): String {
        val m = Regex("^(https?)://([^/?#]+)((?:/[^?#]*)?)(\\?[^#]*)?$").find(targetUrl)
            ?: throw AppError.config("无法解析目标 URL '$targetUrl'")
        val scheme = m.groupValues[1]
        if (scheme != "http" && scheme != "https") {
            throw AppError.config("WebVPN 仅支持代理 http/https 资源，收到: $scheme")
        }
        val authority = m.groupValues[2]
        var path = m.groupValues[3]
        val query = if (m.groupValues.size > 4) m.groupValues[4] else ""

        val colonIdx = authority.lastIndexOf(':')
        var host = authority
        var portStr = ""
        if (colonIdx > 0) {
            host = authority.substring(0, colonIdx)
            portStr = authority.substring(colonIdx + 1)
        }

        // 与网关行为一致：默认端口省略，仅非默认端口写入明文
        val defaultPort = if (scheme == "https") "443" else "80"
        val hostPort = if (portStr.isNotEmpty() && portStr != defaultPort) {
            "$host:$portStr"
        } else {
            host
        }

        val encrypted = encryptHostPort(hostPort)
        val base = webvpnBase.trimEnd('/')
        if (path.isEmpty() && query.isEmpty()) path = "/"
        return "$base/$scheme/$encrypted$path$query"
    }

    /** 金智统一身份认证随机字符串字符集（出自 encrypt.wisedu.js） */
    private const val CAS_RANDOM_CHARS =
        "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"

    /** 生成 len 位随机字符串（字符集与金智 encrypt.wisedu.js 一致） */
    fun casRandomString(len: Int): String {
        val sb = StringBuilder(len)
        val rnd = java.security.SecureRandom()
        for (i in 0 until len) {
            sb.append(CAS_RANDOM_CHARS[rnd.nextInt(CAS_RANDOM_CHARS.length)])
        }
        return sb.toString()
    }

    /**
     * 金智统一身份认证密码加密（AES-128-CBC-PKCS7，输出 base64）
     * 对应 Rust encrypt_cas_data。
     */
    fun encryptCasData(data: String, key: String, iv: String): String {
        if (key.length != 16 || iv.length != 16) {
            throw AppError.config(
                "统一身份认证加密参数长度错误：key ${key.length} 字节、" +
                    "iv ${iv.length} 字节（均应为 16）"
            )
        }
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.US_ASCII), "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding") // PKCS5 == PKCS7 for AES
        cipher.init(
            Cipher.ENCRYPT_MODE,
            secretKey,
            IvParameterSpec(iv.toByteArray(Charsets.US_ASCII))
        )
        val out = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** 按 encrypt.wisedu.js 的 encryptAES(data, aesKey) 加密统一身份认证密码 */
    fun encryptCasPassword(password: String, salt: String): String {
        if (password.isEmpty()) throw AppError.config("统一身份认证密码为空")
        val data = casRandomString(64) + password
        return encryptCasData(data, salt, casRandomString(16))
    }

    /** 生成伪造的浏览器指纹（32 位小写十六进制，绕过 FingerprintJS 检查环节） */
    fun fakeFingerprint(): String {
        val rnd = java.security.SecureRandom()
        val sb = StringBuilder(32)
        for (i in 0 until 16) {
            val x = rnd.nextInt(256)
            sb.append(if (x < 16) "0${x.toString(16)}" else x.toString(16))
        }
        return sb.toString()
    }
}
