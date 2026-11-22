package com.yuruan.neuqassist.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.yuruan.neuqassist.net.HttpService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 校历 PDF 渲染 —— 对应 HarmonyOS 端 WebViewPage.ets 的 calendarMode
 * （原端注释：「Android WebView 原生不能渲染 PDF」同理成立：教务处校历文章页
 * 只是一个挂着 <iframe src="...pdf"> 的壳，WebView 加载后除空白外什么都没有，
 * 因此校历查看必须把 PDF 取回本地后自行渲染。）
 *
 * 流程：下载 PDF 到应用私有目录 → PdfRenderer 逐页渲染为 Bitmap。
 * 渲染结果按「页码.png」落盘缓存，第二次打开直接复用。
 */
object CalendarPdf {

    /** 教务处校历文章页里挂载的 PDF（当前届次）。原端 FALLBACK_CALENDAR_PDF 同款地址。 */
    const val CALENDAR_ARTICLE_URL = "https://jwc.neuq.edu.cn/info/1077/3582.htm"
    const val FALLBACK_PDF_URL =
        "https://jwc.neuq.edu.cn/__local/8/BF/A6/13E1234384AC0159E4D80696C36_7BCC83CF_D607D.pdf"

    private fun pdfFile(context: Context) = File(context.filesDir, "calendar/calendar.pdf")
    private fun pageFile(context: Context, page: Int) =
        File(context.filesDir, "calendar/page_$page.png")

    /** 是否已有可用的本地缓存（下载/渲染过） */
    fun hasCache(context: Context): Boolean = pdfFile(context).let { it.exists() && it.length() > 1000 }

    /** 清空缓存（「重新下载」用） */
    fun clearCache(context: Context) {
        File(context.filesDir, "calendar").deleteRecursively()
    }

    /**
     * 从校历文章页解析 PDF 链接。
     * 文章页是 GBK/UTF-8 混排的老式 CMS，直接按正则找相对路径即可，
     * 找不到时返回 null（调用方回退到 FALLBACK_PDF_URL）。
     */
    suspend fun extractPdfLink(http: HttpService): String? =
        withContext(Dispatchers.IO) {
            try {
                val resp = http.get(CALENDAR_ARTICLE_URL)
                if (resp.status >= 400) return@withContext null
                val m = Regex("src=\"([^\"]*?\\.pdf[^\"]*)\"", RegexOption.IGNORE_CASE)
                    .find(resp.body) ?: return@withContext null
                val raw = m.groupValues[1]
                // 相对路径 → 绝对路径
                when {
                    raw.startsWith("http") -> raw
                    raw.startsWith("/") -> "https://jwc.neuq.edu.cn$raw"
                    else -> "https://jwc.neuq.edu.cn/$raw"
                }
            } catch (e: Exception) {
                Logger.warn("校历 PDF 链接解析失败: ${e.message}")
                null
            }
        }

    /**
     * 下载 PDF（缓存优先）。
     * @return 本地文件，失败返回 null
     */
    suspend fun ensurePdf(context: Context, http: HttpService): File? =
        withContext(Dispatchers.IO) {
            try {
                val f = pdfFile(context)
                if (f.exists() && f.length() > 1000) return@withContext f

                f.parentFile?.mkdirs()

                // 先解析文章页拿真实地址，失败用兜底地址
                val urlStr = extractPdfLink(http) ?: FALLBACK_PDF_URL
                Logger.info("校历 PDF 地址: $urlStr")

                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/119 Mobile Safari/537.36"
                )
                val code = conn.responseCode
                if (code != 200) {
                    Logger.warn("校历 PDF 下载失败: HTTP $code")
                    return@withContext null
                }
                conn.inputStream.use { input ->
                    f.outputStream().use { output -> input.copyTo(output) }
                }
                // 校验文件头，防止下回来的是错误页
                val head = f.inputStream().use {
                    val buf = ByteArray(5)
                    it.read(buf)
                    String(buf, Charsets.US_ASCII)
                }
                if (!head.startsWith("%PDF")) {
                    Logger.warn("校历 PDF 内容异常（非 PDF 文件头）")
                    f.delete()
                    return@withContext null
                }
                f
            } catch (e: Exception) {
                Logger.warn("校历 PDF 下载失败: ${e.message}")
                null
            }
        }

    /**
     * 渲染第 page 页（0 起）为 Bitmap（页码缓存，二次打开零开销）。
     * @return 渲染结果，失败返回 null
     */
    suspend fun renderPage(context: Context, page: Int, targetWidth: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                val cache = pageFile(context, page)
                if (cache.exists()) {
                    android.graphics.BitmapFactory.decodeFile(cache.absolutePath)
                        ?.let { return@withContext it }
                }
                val pdf = pdfFile(context)
                if (!pdf.exists()) return@withContext null

                ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        if (page < 0 || page >= renderer.pageCount) return@withContext null
                        renderer.openPage(page).use { pdfPage ->
                            // 按目标宽等比缩放
                            val w = targetWidth
                            val h = (targetWidth.toFloat() / pdfPage.width * pdfPage.height).toInt()
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            Canvas(bmp).drawColor(Color.WHITE)
                            pdfPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            // 落盘缓存
                            cache.parentFile?.mkdirs()
                            cache.outputStream().use { out ->
                                bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
                            }
                            bmp
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.warn("校历 PDF 渲染失败(第 ${page + 1} 页): ${e.message}")
                null
            }
        }

    /** 已缓存的 PDF 页数（渲染前用于判断总数） */
    suspend fun pageCount(context: Context): Int = withContext(Dispatchers.IO) {
        try {
            val pdf = pdfFile(context)
            if (!pdf.exists()) return@withContext 0
            ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { it.pageCount }
            }
        } catch (e: Exception) {
            0
        }
    }
}
