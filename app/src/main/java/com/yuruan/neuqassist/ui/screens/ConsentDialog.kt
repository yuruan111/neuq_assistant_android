package com.yuruan.neuqassist.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuruan.neuqassist.ui.theme.Dimens
import com.yuruan.neuqassist.ui.theme.PrimaryBlue
import com.yuruan.neuqassist.ui.theme.SurfaceLight
import com.yuruan.neuqassist.ui.theme.TextPrimary

/**
 * 首启《隐私政策》与《用户协议》同意弹窗 —— 移植自 HarmonyOS 端 views/ConsentDialog.ets
 * （上架红线 R2 / 任务 T-03）
 *
 * 闸门架构：
 *  - MainActivity 在 privacyAgreed 为 false 时不渲染三个 Tab，各页的联网分支不会执行；
 *  - 同意状态持久化 SharedPreferences 键 privacyAgreed；
 *  - 点「退出应用」结束 Activity，不留后台进程；卸载重装（清数据）后重新弹出。
 */

private const val PRIVACY_TEXT = "1. 「小秦帮」是东北大学秦皇岛分校的非官方学生工具，" +
    "与学校及教务系统官方无任何隶属或授权关系。\n" +
    "2. 本应用的数据全部保存在你的设备本机：账号信息（学号/用户名；" +
    "密码使用系统加密安全存储，不落明文）、课表与课程数据、空教室查询缓存、" +
    "学期起点与作息设置、外观设置、WebVPN 会话 Cookie（仅用于维持登录态）。\n" +
    "3. 仅在你主动查询、导入课表或查看校历时发起网络请求，目标限于：" +
    "jwxt.neuq.edu.cn（教务系统，明文 HTTP——教务系统本身不支持 HTTPS）、" +
    "vpn.neuq.edu.cn（WebVPN）、jwc.neuq.edu.cn 与 yx.neuq.edu.cn（教务处/校历）、" +
    "neuq.tsiao.io 与 *.pages.dev（空教室公开镜像）、timor.tech（节假日接口）。" +
    "本应用不向任何第三方出售或上传你的数据，不含统计 SDK 与广告。\n" +
    "4. 系统能力使用说明——相册：仅在你主动选取图片时才会读取所选内容" +
    "（自定义背景/校历识别，系统免权限选择器，不做扫描）；" +
    "剪贴板：仅在点击「复制邮箱」时写入反馈邮箱，从不读取剪贴板内容；" +
    "网络：查询与登录必需；振动：时间滚轮触感反馈。\n" +
    "5. 课表页首次使用会展示内置演示课表（页面有标注），导入后即替换为你的真实课表。\n" +
    "6. 联系方式：yuruan0011@yeah.net。卸载应用即删除全部本机数据并视为撤回同意。"

private const val TERMS_TEXT = "1. 本应用仅供个人学习与校园生活参考，" +
    "请遵守学校相关管理规定，不得用于商业或不当用途。\n" +
    "2. 应用内数据来自教务系统公开查询接口，不保证完整性、准确性与时效性，" +
    "请以教务系统及学校官方发布为准；因数据偏差、功能中断或使用不当造成的损失，" +
    "开发者不承担责任。\n" +
    "3. 本协议与隐私政策可能随版本更新调整，重大变更会在应用内提示。\n" +
    "4. 点击「同意并继续」即表示你已阅读并同意以上全部内容。"

@Composable
fun ConsentDialog(
    onAgree: () -> Unit,
    onDecline: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000)),
        contentAlignment = Alignment.Center
    ) {
        // 遮罩保持全屏（含状态栏下方），卡片本身约束在安全区内，
        // 避免矮屏/横屏时卡片顶部被状态栏压住
        Surface(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)
                )
                .fillMaxWidth(),
            color = Color.White,
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "欢迎使用「小秦帮」",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(Dimens.gapLarge))

                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "隐私政策",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = PRIVACY_TEXT,
                        fontSize = 12.sp,
                        color = Color(0x4D182431),
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(Dimens.gapLarge))
                    Text(
                        text = "用户协议",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = TERMS_TEXT,
                        fontSize = 12.sp,
                        color = Color(0x4D182431),
                        lineHeight = 18.sp
                    )
                }

                Spacer(modifier = Modifier.height(Dimens.gapLarge))
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.gapLarge)) {
                    Button(
                        onClick = onDecline,
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SurfaceLight,
                            contentColor = Color(0xFF576B95)
                        )
                    ) {
                        Text("退出应用", fontSize = 14.sp)
                    }
                    Button(
                        onClick = onAgree,
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryBlue,
                            contentColor = Color.White
                        )
                    ) {
                        Text("同意并继续", fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
