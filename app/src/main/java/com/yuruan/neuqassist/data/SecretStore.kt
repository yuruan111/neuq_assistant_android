package com.yuruan.neuqassist.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 敏感信息存取封装 —— 移植自 HarmonyOS 端 common/SecretStore.ets
 * （T-04：教务/WebVPN 密码迁移到系统安全存储）
 *
 * HarmonyOS 端使用 Asset Store Kit（系统级加密存储）；Android 端对应方案为
 * EncryptedSharedPreferences（AES256-SIV 键加密 + AES256-GCM 值加密，主密钥
 * 存放于 AndroidKeyStore，不参与应用云备份）。
 */
class SecretStore private constructor(private val prefs: SharedPreferences) {

    /** 读取；条目不存在或读取失败返回空串（调用方按"未保存"处理） */
    fun read(alias: String): String = try {
        prefs.getString(alias, "") ?: ""
    } catch (e: Exception) {
        Logger.warn("读取加密存储失败($alias): ${e.message}")
        ""
    }

    /** 写入 */
    fun save(alias: String, secret: String) {
        try {
            prefs.edit().putString(alias, secret).apply()
        } catch (e: Exception) {
            Logger.warn("保存加密存储失败($alias): ${e.message}")
        }
    }

    /** 删除；不存在时静默（幂等） */
    fun remove(alias: String) {
        try {
            prefs.edit().remove(alias).apply()
        } catch (e: Exception) {
            // 首次保存前条目不存在：属正常路径
        }
    }

    companion object {
        @Volatile
        private var instance: SecretStore? = null

        fun get(context: Context): SecretStore =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): SecretStore = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                "neuq_secrets",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            SecretStore(prefs)
        } catch (e: Exception) {
            // 加密存储不可用（极少数设备 Keystore 异常）时退化为普通偏好，
            // 保证功能可用；日志留痕便于排查
            Logger.warn("加密存储初始化失败，退化为普通偏好: ${e.message}")
            SecretStore(
                context.getSharedPreferences("neuq_secrets_fallback", Context.MODE_PRIVATE)
            )
        }
    }
}
