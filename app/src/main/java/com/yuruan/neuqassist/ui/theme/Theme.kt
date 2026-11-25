package com.yuruan.neuqassist.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 主题 —— 对应 HarmonyOS 端 AppScope/entry 资源的配色
 *
 * 原端为浅色渐变（#F2F3F5 → #E9EBF8）+ 内容保护层；Android 端沿用同一基调。
 */

/** 主色（原端 #007DFF） */
val PrimaryBlue = Color(0xFF007DFF)

/** 文本主色（原端 #182431） */
val TextPrimary = Color(0xFF182431)

/** 文本次色（原端 #66182431） */
val TextSecondary = Color(0x66182431)

/** 文本三级色（原端 #99182431） */
val TextTertiary = Color(0x99182431)

/** 卡片背景（原端 #FFFFFF） */
val CardBg = Color(0xFFFFFFFF)

/** 页面背景（原端 #F2F3F5） */
val PageBg = Color(0xFFF2F3F5)

/** 浅灰背景（原端 #F1F3F5 / #F7F8FA） */
val SurfaceLight = Color(0xFFF1F3F5)
val SurfaceGrey = Color(0xFFF7F8FA)

/** 错误红（原端 #E53935 / #D83931） */
val ErrorRed = Color(0xFFE53935)
val ErrorRedDeep = Color(0xFFD83931)

/** 警示橙（原端 #B26A00） */
val WarnOrange = Color(0xFFB26A00)
val WarnBg = Color(0xFFFFF4E0)

/** 校区徽标配色 */
val CampusSouthBg = Color(0xFFFAEEDA)
val CampusSouthText = Color(0xFF854F0B)
val CampusMainBg = Color(0xFFE6F1FB)
val CampusMainText = Color(0xFF185FA5)

/** 网格线 */
val GridLineLight = Color(0x1F182431)
val GridLineSolid = Color(0x40182431)
val FrameLine = Color(0x4D182431)

/** WakeUp 风格饱和课程配色 */
val CoursePalette = listOf(
    Color(0xFFF09A3E), Color(0xFFEE6E9F), Color(0xFF3FB39B), Color(0xFF5B9BE0),
    Color(0xFF9B7FDF), Color(0xFFD9A83F), Color(0xFF68BE7D), Color(0xFFE57373)
)

private val LightColors = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    secondary = PrimaryBlue,
    background = PageBg,
    onBackground = TextPrimary,
    surface = CardBg,
    onSurface = TextPrimary,
    error = ErrorRed
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextPrimary),
    headlineMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary),
    titleLarge = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary),
    bodyLarge = TextStyle(fontSize = 15.sp, color = TextPrimary),
    bodyMedium = TextStyle(fontSize = 14.sp, color = TextPrimary),
    bodySmall = TextStyle(fontSize = 12.sp, color = TextSecondary),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, color = TextSecondary),
    labelSmall = TextStyle(fontSize = 11.sp, color = TextTertiary)
)

@Composable
fun NEUQTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** 主题强调色（个性化设置 → 主题配色；默认蓝 = 原端 #007DFF，由调用方按预设传入） */
    primary: Color = PrimaryBlue,
    content: @Composable () -> Unit
) {
    // 原端仅浅色主题（深色资源存在但主流程为浅色），此处固定浅色保证视觉一致；
    // 强调色统一走 MaterialTheme.colorScheme.primary，底栏选中态 / 按钮 / 滑块 /
    // 开关 / 课表今日态等全部联动，正文深色不随主题变化
    MaterialTheme(
        colorScheme = LightColors.copy(primary = primary, secondary = primary),
        typography = AppTypography,
        content = content
    )
}

/** 通用尺寸 */
object Dimens {
    val screenPadding = 16.dp
    val cardPadding = 12.dp
    val cardRadius = 12.dp
    val chipRadius = 16.dp
    val smallRadius = 8.dp
    val gapSmall = 6.dp
    val gapMedium = 10.dp
    val gapLarge = 12.dp
}
