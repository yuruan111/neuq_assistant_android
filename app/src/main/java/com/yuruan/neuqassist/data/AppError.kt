package com.yuruan.neuqassist.data

import android.util.Log
import org.json.JSONObject

/**
 * 错误类型与重试机制 —— 移植自 HarmonyOS 端 common/AppError.ets（Rust src/error.rs）
 *
 * 原端使用 AppError 异常类 + withRetry 指数退避；Kotlin 用 Exception 子类 +
 * 内联重试函数实现同等语义。
 */
class AppError(message: String, val kind: Kind) : Exception(message) {

    enum class Kind { Config, Fetch, LoginFailed, Validation, Process, Other }

    companion object {
        fun config(msg: String) = AppError(msg, Kind.Config)
        fun fetch(msg: String) = AppError(msg, Kind.Fetch)
        fun loginFailed(reason: String) = AppError(reason, Kind.LoginFailed)
        fun validation(msg: String) = AppError(msg, Kind.Validation)
        fun process(msg: String) = AppError(msg, Kind.Process)
    }
}

/** 重试配置 —— 对应 Rust RetryConfig */
class RetryConfig(
    val maxRetries: Int = 3,
    val initialDelayMs: Long = 1000,
    val maxDelayMs: Long = 30000,
    val backoffFactor: Int = 2
)

/**
 * 指数退避重试 —— 对应 Rust error.rs 的 with_retry
 *
 * 首次执行 + maxRetries 次重试；每次失败后退避时间乘以 backoffFactor。
 */
suspend fun <T> withRetry(
    config: RetryConfig = RetryConfig(),
    taskName: String = "task",
    fn: suspend () -> T
): T {
    var delay = config.initialDelayMs
    var lastErr: Throwable = AppError("$taskName 未知失败", AppError.Kind.Other)
    for (attempt in 1..(config.maxRetries + 1)) {
        try {
            return fn()
        } catch (e: Throwable) {
            lastErr = e
            if (attempt > config.maxRetries) break
            Log.w("Retry", "[retry] $taskName 第 $attempt 次失败: ${e.message}，${delay}ms 后重试")
            kotlinx.coroutines.delay(delay)
            delay = minOf(delay * config.backoffFactor, config.maxDelayMs)
        }
    }
    throw lastErr
}

/** 统一日志封装（对应 HarmonyOS 端 common/Logger.ets）
 *  约束：任何日志不得携带 cookie、密码、完整响应体、带 token 的 URL。 */
object Logger {
    private const val TAG = "NEUQ"

    fun info(msg: String) = Log.i(TAG, msg)
    fun warn(msg: String) = Log.w(TAG, msg)
    fun error(msg: String) = Log.e(TAG, msg)
}

/** 宽松 JSON 字符串字段提取（避免整体 JSON.parse 失败时无路可退） */
fun extractJsonString(json: String, key: String): String? {
    val m = Regex("\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json) ?: return null
    return m.groupValues[1]
}

/** 安全 JSON 封装 */
fun jsonOf(vararg pairs: Pair<String, Any?>): JSONObject {
    val o = JSONObject()
    for ((k, v) in pairs) {
        when (v) {
            null -> o.put(k, JSONObject.NULL)
            is Boolean -> o.put(k, v)
            is Int -> o.put(k, v)
            is Long -> o.put(k, v)
            is Double -> o.put(k, v)
            else -> o.put(k, v.toString())
        }
    }
    return o
}
