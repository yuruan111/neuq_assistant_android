package com.yuruan.neuqassist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuruan.neuqassist.ui.theme.CardBg
import com.yuruan.neuqassist.ui.theme.Dimens
import com.yuruan.neuqassist.ui.theme.SurfaceLight
import com.yuruan.neuqassist.ui.theme.TextPrimary
import com.yuruan.neuqassist.ui.theme.TextSecondary
import com.yuruan.neuqassist.ui.theme.TextTertiary

/**
 * 通用 UI 组件 —— 对应 HarmonyOS 端各页的常用构建块
 */

/** 卡片容器（对应原端 Column ... .borderRadius(12).backgroundColor(#FFFFFF)） */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    padding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(Dimens.cardPadding),
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = CardBg,
        shape = RoundedCornerShape(Dimens.cardRadius)
    ) {
        Column(modifier = Modifier.padding(padding)) { content() }
    }
}

/** 区块标题（对应原端 "数据来源" 等小标题） */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = TextSecondary,
        modifier = modifier
    )
}

/** 主按钮（对应原端 Button ... backgroundColor('#007DFF')） */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(22.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = SurfaceLight
        )
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            color = if (enabled) Color.White else TextSecondary
        )
    }
}

/** 次级按钮（对应原端 Button ... backgroundColor('#F1F3F5')） */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(40.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = SurfaceLight,
            contentColor = TextPrimary
        )
    ) {
        Text(text = text, fontSize = 14.sp)
    }
}

/** 文字按钮（对应原端 fontColor('#576B95') 的文字入口） */
@Composable
fun TextLinkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Text(text = text, fontSize = 13.sp, color = Color(0xFF576B95))
    }
}

/** 加载指示行（对应原端 LoadingProgress + Text） */
@Composable
fun LoadingRow(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.width(20.dp).height(20.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text, fontSize = 13.sp, color = TextSecondary)
    }
}

/** 错误提示条（对应原端 fontColor('#E53935') 的提示） */
@Composable
fun ErrorBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = SurfaceLight,
        shape = RoundedCornerShape(Dimens.smallRadius)
    ) {
        Text(
            text = "查询失败：$text",
            fontSize = 13.sp,
            color = com.yuruan.neuqassist.ui.theme.ErrorRed,
            modifier = Modifier.padding(Dimens.cardPadding)
        )
    }
}

/** 警示提示条（对应原端 backgroundColor('#FFF4E0')） */
@Composable
fun WarnBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = com.yuruan.neuqassist.ui.theme.WarnBg,
        shape = RoundedCornerShape(Dimens.smallRadius)
    ) {
        Text(
            text = "⚠ $text",
            fontSize = 12.sp,
            color = com.yuruan.neuqassist.ui.theme.WarnOrange,
            modifier = Modifier.padding(10.dp)
        )
    }
}

/** 来源标注（对应原端 cacheInfoText / sourceText） */
@Composable
fun SourceNote(text: String, modifier: Modifier = Modifier) {
    Text(text = text, fontSize = 12.sp, color = TextTertiary, modifier = modifier.fillMaxWidth())
}

/** 可横向滚动的选项芯片行（对应原端 Scroll + Row + Text 组合） */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChipRow(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    wrap: Boolean = false
) {
    if (wrap) {
        androidx.compose.foundation.layout.FlowRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items.forEachIndexed { i, item ->
                Chip(item = item, selected = i == selectedIndex, onClick = { onSelect(i) })
            }
        }
    } else {
        androidx.compose.foundation.lazy.LazyRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items.size) { i ->
                Chip(item = items[i], selected = i == selectedIndex, onClick = { onSelect(i) })
            }
        }
    }
}

/** 单个选项芯片 */
@Composable
fun Chip(
    item: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        color = if (selected) MaterialTheme.colorScheme.primary else Color.White,
        shape = RoundedCornerShape(Dimens.chipRadius),
        border = if (selected) null else androidx.compose.foundation.BorderStroke(
            1.dp, Color(0x33182431)
        )
    ) {
        Text(
            text = item,
            fontSize = 13.sp,
            color = if (selected) Color.White else TextPrimary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/** 输入框（对应原端 TextInput） */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, fontSize = 13.sp) },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        visualTransformation = if (isPassword) {
            androidx.compose.ui.text.input.PasswordVisualTransformation('*')
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
        shape = RoundedCornerShape(Dimens.smallRadius)
    )
}

/** 空状态提示 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(40.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, fontSize = 15.sp, color = TextSecondary)
    }
}

/** 说明文字（对应原端 fontSize(11).fontColor(tertiary)） */
@Composable
fun HelpText(text: String, modifier: Modifier = Modifier) {
    Text(text = text, fontSize = 11.sp, color = TextTertiary, modifier = modifier.fillMaxWidth())
}

/** 加粗标题行 */
@Composable
fun BoldTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = TextPrimary,
        modifier = modifier
    )
}
