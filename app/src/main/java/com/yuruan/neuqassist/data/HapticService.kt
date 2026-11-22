package com.yuruan.neuqassist.data

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 触感反馈服务 —— 移植自 HarmonyOS 端 service/HapticService.ets
 *
 * 用途：课表切周、时间滚轮滑过一档时的短促触感反馈。
 *
 * 实现要点（与原版一致）：
 *  - 15ms 轻触效果，节流 30ms：快速滑动时 onChange 高频触发，避免震动轰炸
 *  - 设备不支持/系统关闭震动时静默降级，不影响 UI
 *  - 权限：Android 的 VIBRATE 为普通权限，Manifest 已静态声明
 */
class HapticService(private val context: Context) {

    private var lastTick = 0L

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                as? VibratorManager
            mgr?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /** 滑过一档的短促触感反馈（内置 30ms 节流） */
    fun tick() {
        val now = System.currentTimeMillis()
        if (now - lastTick < 30) return
        lastTick = now
        try {
            val v = vibrator ?: return
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(
                    VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(15)
            }
        } catch (e: Exception) {
            // 部分设备禁止震动或策略限制，静默降级
        }
    }
}
