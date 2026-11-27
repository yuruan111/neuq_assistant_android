package com.yuruan.neuqassist.data

import android.content.Context
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 个性化设置中心（主题配色 / 课表显示 / 默认启动页）—— 对应 hap common/Personalize.ets
 *
 * 职责（集中管理，避免各页面分别硬编码）：
 *  - 预设定义：四套主题色、课表字号三档、卡片圆角范围、默认启动页选项
 *  - 合法值校验：持久化数据缺失 / 损坏 / 类型不符时一律回退默认值
 *  - 持久化：复用 SettingsStore（PREF_FILE 'classroom_settings'），键名见 Keys
 *  - 跨页联动：由 MainViewModel 持有 Compose 状态（themePrimary 为派生色，不落盘）
 *
 * 说明：本文件位于 data/ 层（无 Compose 依赖），主题色以 ARGB Long 表达；
 * UI 层（MainViewModel.themePrimary）负责转换为 androidx Color。
 */
object Personalize {

    // ===== 主题预设 =====

    /** 主题预设项（key 为存储值；argb 为强调色，主要按钮 / 选中态 / 滑块统一使用） */
    data class ThemePresetItem(val key: String, val name: String, val argb: Long)

    /**
     * 四套预设主题色（与 hap 完全一致）。
     * 白字对比度均 ≥3.5:1；正文仍为 #182431 系深色，不随主题变化。
     */
    val THEME_PRESETS = listOf(
        ThemePresetItem("blue", "默认蓝", 0xFF007DFF),
        ThemePresetItem("orange", "活力橙", 0xFFD2600A),
        ThemePresetItem("green", "清新绿", 0xFF128A5E),
        ThemePresetItem("purple", "典雅紫", 0xFF6F4BD8)
    )

    const val DEFAULT_THEME_KEY = "blue"

    /** 默认主题的强调色（未初始化时各处的兜底值） */
    const val DEFAULT_THEME_ARGB = 0xFF007DFFL

    /** 按预设键取强调色 ARGB（非法键回退默认蓝） */
    fun themeColor(presetKey: String): Long =
        THEME_PRESETS.firstOrNull { it.key == presetKey }?.argb ?: DEFAULT_THEME_ARGB

    /** 校验主题预设键：不在预设列表内一律回退默认 */
    fun normalizeThemeKey(raw: String): String =
        if (THEME_PRESETS.any { it.key == raw }) raw else DEFAULT_THEME_KEY

    // ===== 课表显示：字号档位 =====

    /** 字号档位项（factor 乘在课程块各基准字号上，标准档 = 现状值） */
    data class FontScaleOption(val key: String, val name: String, val factor: Float)

    val FONT_SCALE_OPTIONS = listOf(
        FontScaleOption("small", "小", 0.85f),
        FontScaleOption("standard", "标准", 1.0f),
        FontScaleOption("large", "大", 1.18f)
    )

    const val DEFAULT_FONT_SCALE = "standard"

    /** 校验字号档位键：非法档位回退标准（= 现状） */
    fun normalizeFontScale(raw: String): String =
        if (FONT_SCALE_OPTIONS.any { it.key == raw }) raw else DEFAULT_FONT_SCALE

    /** 档位系数（非法档位回退 1.0 = 现状） */
    fun fontFactor(scaleKey: String): Float =
        FONT_SCALE_OPTIONS.firstOrNull { it.key == scaleKey }?.factor ?: 1.0f

    /**
     * 课程块内文字缩放：base × 档位系数，四舍五入并保下限 6sp，
     * 避免小字号档把 '@' 等辅助文字压到不可见。
     */
    fun scaleCourseFont(base: Float, scaleKey: String): Int =
        max(6, (base * fontFactor(scaleKey)).roundToInt())

    // ===== 课表显示：卡片圆角 =====

    const val CARD_RADIUS_MIN = 2
    const val CARD_RADIUS_MAX = 16
    /**
     * 默认值 = Android 端原硬编码 4dp，标准观感不变。
     * （hap 端默认 8 = 其原硬编码 8vp；两端默认值各自对齐自己改动前的观感。）
     */
    const val DEFAULT_CARD_RADIUS = 4

    /** 校验圆角：越界一律钳制到合法区间 */
    fun normalizeCardRadius(raw: Int): Int = raw.coerceIn(CARD_RADIUS_MIN, CARD_RADIUS_MAX)

    /** 浮点入口（兼容历史落盘类型 / 损坏值）：NaN 回退默认，其余钳制 */
    fun normalizeCardRadius(raw: Float): Int =
        if (raw.isNaN()) DEFAULT_CARD_RADIUS else raw.roundToInt().coerceIn(CARD_RADIUS_MIN, CARD_RADIUS_MAX)

    // ===== 课表显示：节次行高 / 周末折叠 / 教师姓名 =====

    /** 节次行高档位项（slotDp 为基准行高 dp） */
    data class RowHeightOption(val key: String, val name: String, val slotDp: Int)

    val ROW_HEIGHT_OPTIONS = listOf(
        RowHeightOption("compact", "紧凑", 48),
        RowHeightOption("standard", "标准", 58),
        RowHeightOption("spacious", "舒展", 70)
    )

    const val DEFAULT_ROW_HEIGHT = "standard"

    fun normalizeRowHeight(raw: String): String =
        if (ROW_HEIGHT_OPTIONS.any { it.key == raw }) raw else DEFAULT_ROW_HEIGHT

    fun rowHeightDp(key: String): Int =
        ROW_HEIGHT_OPTIONS.firstOrNull { it.key == key }?.slotDp ?: 58

    const val DEFAULT_AUTO_HIDE_WEEKEND = true
    const val DEFAULT_HIDE_TEACHER = false

    // ===== 使用习惯：默认启动页 =====

    /** 启动页选项（index 与 MainActivity 的 Tab 顺序严格对应） */
    val LAUNCH_TAB_NAMES = listOf("课表查询", "空教室", "小秦帮")

    const val DEFAULT_LAUNCH_TAB = 0

    /** 校验启动页下标：越界 / 负数一律回退 0（保持既有行为） */
    fun normalizeLaunchTab(raw: Int): Int = if (raw in 0..LAUNCH_TAB_NAMES.size - 1) raw else DEFAULT_LAUNCH_TAB

    // ===== 既有外观默认值（与 hap 一致） =====

    /** 背景强度默认值：0-1，越大内容保护层越实（文字越清晰），背景越淡 */
    const val DEFAULT_BG_DIM = 0.88f

    /** 背景强度合法区间（越界 / NaN 回退默认） */
    fun normalizeBgDim(raw: Float): Float =
        if (raw.isNaN() || raw < 0.6f || raw > 1f) DEFAULT_BG_DIM else raw

    /** 课表网格线强度：'normal' 常规 / 'solid' 加重（非法值回退加重） */
    const val DEFAULT_LINE_WEIGHT = "solid"

    fun normalizeLineWeight(raw: String): String = if (raw == "normal") "normal" else "solid"

    // ===== 恢复默认 =====

    /** 自定义背景在应用沙箱内的固定文件名（与 hap bg_custom.jpg 同名） */
    const val BG_CUSTOM_FILE = "bg_custom.jpg"

    /**
     * 恢复默认个性化设置：仅重置 主题 / 课表显示 / 默认启动页 / 既有外观键，
     * 并清理自定义背景沙箱文件（不存在 / 清理失败均不阻断重置）。
     * ⚠️ 绝不触碰：账号密码（含 SecretStore 加密存储）、登录会话 Cookie、
     * 课程数据、节次时间、学期起点、云端缓存、隐私同意状态等其余键。
     */
    fun resetPersonalization(context: Context, store: SettingsStore) {
        try {
            File(context.filesDir, BG_CUSTOM_FILE).delete()
        } catch (e: Exception) {
            Logger.warn("重置时清理自定义背景失败: ${e.message}")
        }
        store.themePreset = DEFAULT_THEME_KEY
        store.scheduleFontScale = DEFAULT_FONT_SCALE
        store.scheduleCardRadius = DEFAULT_CARD_RADIUS
        store.scheduleRowHeight = DEFAULT_ROW_HEIGHT
        store.autoHideWeekend = DEFAULT_AUTO_HIDE_WEEKEND
        store.hideCourseTeacher = DEFAULT_HIDE_TEACHER
        store.defaultLaunchTab = DEFAULT_LAUNCH_TAB
        store.bgEnabled = true
        store.bgImagePath = ""
        store.bgDim = DEFAULT_BG_DIM
        store.lineWeight = DEFAULT_LINE_WEIGHT
    }
}
