package com.yuruan.neuqassist.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuruan.neuqassist.data.CalendarPdf
import com.yuruan.neuqassist.net.HttpService
import com.yuruan.neuqassist.ui.theme.ErrorRed
import com.yuruan.neuqassist.ui.theme.PrimaryBlue
import com.yuruan.neuqassist.ui.theme.TextPrimary
import com.yuruan.neuqassist.ui.theme.TextSecondary

/**
 * 校历查看页 —— 对应 HarmonyOS 端 WebViewPage.ets 的 calendarMode。
 *
 * 教务处校历文章页里是 <iframe src="xxx.pdf">，Android WebView 不能渲染 PDF
 * （页面打开除空白外什么都没有），因此本页直接：
 *   下载 PDF → PdfRenderer 逐页渲染为图片 → 竖向滚动查看 + 页码导航。
 */
@Composable
fun CalendarPdfScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf("loading") }   // loading|ready|error
    var errorMsg by remember { mutableStateOf("") }
    var pageCount by remember { mutableStateOf(0) }
    var currentPage by remember { mutableStateOf(0) }
    var pageBmp by remember { mutableStateOf<Bitmap?>(null) }
    var rendering by remember { mutableStateOf(false) }

    // 首次进入：下载 PDF
    LaunchedEffect(Unit) {
        val pdf = CalendarPdf.ensurePdf(context, HttpService())
        if (pdf == null) {
            errorMsg = "校历 PDF 下载失败，请检查网络后点「重试」。"
            state = "error"
        } else {
            pageCount = CalendarPdf.pageCount(context)
            if (pageCount <= 0) {
                errorMsg = "校历文件无法打开（可能已失效，请等待教务处发布新校历）。"
                state = "error"
            } else {
                currentPage = 0
                state = "ready"
            }
        }
    }

    // 渲染当前页
    LaunchedEffect(state, currentPage) {
        if (state == "ready") {
            rendering = true
            // 用屏幕像素宽渲染（约 1080），等比高
            val metrics = context.resources.displayMetrics
            val bmp = CalendarPdf.renderPage(context, currentPage, metrics.widthPixels)
            pageBmp = bmp
            rendering = false
        }
    }

    Column(modifier = modifier.fillMaxSize().background(Color(0xFFF2F3F5))) {
        // 顶栏：返回 / 标题 / 重新下载
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "←",
                fontSize = 20.sp,
                color = TextPrimary,
                modifier = Modifier.clickable { onBack() }.padding(10.dp)
            )
            Text(
                text = "2026-2027 学年校历",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                CalendarPdf.clearCache(context)
                state = "loading"
            }) { Text("重新下载", fontSize = 13.sp, color = PrimaryBlue) }
        }

        when (state) {
            "loading" -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = PrimaryBlue)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("正在下载校历…", fontSize = 13.sp, color = TextSecondary)
                }
            }

            "error" -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = errorMsg,
                        fontSize = 14.sp,
                        color = ErrorRed,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        onClick = {
                            state = "loading"
                            errorMsg = ""
                        },
                        color = PrimaryBlue,
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            "重试",
                            fontSize = 14.sp,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            "ready" -> Column(modifier = Modifier.fillMaxSize()) {
                // 页码导航
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "‹ 上一页",
                        fontSize = 14.sp,
                        color = if (currentPage > 0) PrimaryBlue else TextSecondary,
                        modifier = Modifier
                            .clickable(enabled = currentPage > 0) { currentPage-- }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                    Text(
                        text = "第 ${currentPage + 1} / $pageCount 页",
                        fontSize = 13.sp,
                        color = TextPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    Text(
                        text = "下一页 ›",
                        fontSize = 14.sp,
                        color = if (currentPage < pageCount - 1) PrimaryBlue else TextSecondary,
                        modifier = Modifier
                            .clickable(enabled = currentPage < pageCount - 1) { currentPage++ }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                // 页面内容（可缩放前先支持滚动查看）
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color.White),
                    contentAlignment = Alignment.TopCenter
                ) {
                    val bmp = pageBmp
                    if (rendering || bmp == null) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = PrimaryBlue)
                        }
                    } else {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "校历第 ${currentPage + 1} 页",
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            contentScale = ContentScale.FillWidth
                        )
                    }
                }
            }
        }
    }
}
