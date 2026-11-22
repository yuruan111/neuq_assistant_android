# 小秦帮 · Android 移植版

「小秦帮」的 Android 版本，由 HarmonyOS ArkTS 工程移植而来。功能与 HarmonyOS 版一致：

- **空教室查询**：云端数据（免登录，每小时更新）+ 教务实时查询（网页通道，免账号密码）
- **课表**：周网格课表（副列并排、连课跨行、休/班徽标、今日高亮）、网页通道导入、教务导出文件导入、手动增删改、自定义作息、学期起点
- **小秦帮**：校园地图 / 校历与作息 / 意见反馈 / 隐私政策 / 开源许可 / 免责声明

不包含 HarmonyOS 特有的沉浸光感、智感握姿换边等能力（依赖系统权限，Android 无对应物）。

---

## 一、工程结构

```
android/
├── settings.gradle.kts              # 仓库配置（已内置国内镜像，构建更快）
├── build.gradle.kts                 # 根构建脚本
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml           # 版本目录
│   └── wrapper/
│       └── gradle-wrapper.properties
└── app/
    ├── build.gradle.kts             # 应用构建脚本
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        │   ├── values/strings.xml   # 应用名「小秦帮」
        │   ├── values/themes.xml
        │   ├── drawable/            # 启动图标前景/背景（矢量）
        │   ├── mipmap-anydpi*/      # 启动图标（API 26+ 自适应 / API 24-25 兼容）
        │   └── xml/                 # 备份排除规则
        └── java/com/yuruan/neuqassist/
            ├── MainActivity.kt                  # 主壳：底部三栏 + 隐私闸门 + 各对话框
            ├── data/                            # 业务逻辑层（无 UI 依赖）
            │   ├── AppConfig.kt                 # 教学楼/时段/黑名单等常量
            │   ├── AppError.kt                  # 错误类型 + 指数退避重试 + Logger
            │   ├── ClassroomModels.kt           # 原始/处理后教室数据模型
            │   ├── ClassroomService.kt          # 查询编排 + 按楼/按层分组
            │   ├── CloudCache.kt                # 云端数据本地缓存（行文本格式）
            │   ├── CloudService.kt              # 云端镜像拉取 + day-N 切片解析
            │   ├── CourseFileParser.kt          # 教务导出文件解析（免登录通道）
            │   ├── CourseModels.kt              # 课表模型 + 重叠课程分副列算法
            │   ├── DateUtil.kt                  # 北京时间/日期标签/中文排序
            │   ├── HolidayService.kt            # 法定节假日（timor.tech）
            │   ├── Keys.kt                      # 存储键名（与 HarmonyOS 版一致）
            │   ├── Processor.kt                 # 教室筛选与名称清洗（Rust Rules 1-13）
            │   ├── ScheduleService.kt           # 课表导入（TaskActivity 解析）
            │   ├── SettingsStore.kt             # SharedPreferences 封装 + ScheduleViewUtil 工具对象
            │   ├── SecretStore.kt               # 密码加密存储（EncryptedSharedPreferences）
            │   └── …
            ├── net/                             # 网络层
            │   ├── HttpService.kt               # HttpURLConnection + 手动 Cookie 管理
            │   ├── WebvpnCrypto.kt              # AES-128-CFB / CAS 密码加密 / SHA1
            │   ├── WebvpnSession.kt             # 深澜 WebVPN 会话（cas / local）
            │   ├── ClassroomClient.kt           # 教务登录/查询客户端
            │   └── HtmlParser.kt                # gridtable / salt 提取
            └── ui/                              # UI 层（Jetpack Compose）
                ├── MainViewModel.kt             # 全部页面状态与业务编排
                ├── Common.kt                    # 通用组件（按钮/芯片/输入框等）
                ├── WebFetchHelper.kt            # 隐形 WebView 兜底抓取
                ├── theme/Theme.kt               # 配色与排版
                └── screens/
                    ├── ConsentDialog.kt         # 首启隐私政策闸门
                    ├── EmptyClassroomScreen.kt  # 空教室查询页
                    ├── CourseGridView.kt        # 周课表网格
                    ├── ScheduleScreen.kt        # 课表页 + 课程管理
                    ├── XiaoQinScreen.kt         # 小秦帮页
                    ├── SettingsDialogs.kt       # 高级设置/学期起点/上课时间/导入
                    └── WebViewPage.kt           # 通用网页查看器 + 网页登录
```

> 说明：`net/ClassroomClient.kt` 是教务客户端的唯一落点，`ScheduleViewUtil` 工具对象
> 位于 `data/SettingsStore.kt` 内（不是独立文件）。

---

## 二、构建要求

| 项 | 要求 |
|---|---|
| JDK | 17 或更高（推荐 17，与 AGP 8.5 匹配） |
| Android Studio | Ladybug (2024.2) 或更高；或用命令行 Gradle |
| Android SDK | Platform 34（`compileSdk 34`）、Build-Tools 34+ |
| minSdk | 24（Android 7.0） |
| Kotlin | 2.0.20（Compose 编译器插件配套） |

### 用 Android Studio 构建

1. 打开 Android Studio → **Open** → 选择本 `android/` 目录（注意是 `android/`，不是仓库根目录）
2. 等待 Gradle Sync 完成（首次会下载依赖，`settings.gradle.kts` 已配国内镜像）
3. 连接手机或启动模拟器 → 点 **Run** ▶

### 命令行构建

```bash
cd android

# Debug APK（可直接安装）
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# Release APK（未签名；需自行配置签名后才能安装）
./gradlew assembleRelease
```

Windows 下用 `gradlew.bat`。

> **命令行构建需要 `local.properties`**：Android Studio 会自动生成，
> 命令行构建请先复制 `local.properties.example` 为 `local.properties`，
> 并把 `sdk.dir` 改成本机 Android SDK 路径。
>
> Wrapper 已随包提供（标准 Gradle 8.9 wrapper，distributionUrl 指向腾讯云镜像），
> 首次执行 `gradlew` 会自动下载 Gradle 发行版（约 136 MB），之后走缓存。

---

## 三、首次运行行为

1. **隐私政策闸门**：首次启动弹出《隐私政策》与《用户协议》，未同意不进入主界面，也不发起任何网络请求；点「退出应用」直接结束进程。
2. **默认数据来源 = 云端**：无需登录即可查空教室（数据来自公开镜像 `neuq.tsiao.io`，每小时更新）。
3. **课表页首次显示演示课表**（页面有标注），导入后替换为真实课表。

---

## 四、与原 HarmonyOS 版的差异

| 方面 | HarmonyOS 版 | Android 版 |
|---|---|---|
| UI 框架 | ArkUI 声明式 | Jetpack Compose（Material 3） |
| 密码存储 | Asset Store Kit（系统加密） | EncryptedSharedPreferences（AES256-GCM，主密钥在 AndroidKeyStore） |
| 偏好存储 | `@kit.ArkData` preferences | SharedPreferences |
| 隐形 WebView 兜底 | ArkWeb `Web` 组件 | `android.webkit.WebView` + `evaluateJavascript` |
| 沉浸光感、智感握姿 | 有（依赖 DETECT_GESTURE 权限） | **未移植**（无对应系统能力） |
| 校历图片 OCR 识别开学日期 | Core Vision Kit 端侧 OCR | 未移植（可手动选择日期） |
| 课表文件导入 | DocumentViewPicker | SAF `ACTION_OPEN_DOCUMENT` |
| 备份排除 | `backup_config` excludes | `xml/backup_rules.xml` + `data_extraction_rules.xml` 排除 preferences |
| 网络栈 | `@ohos.net.http` | `HttpURLConnection`（手动跟随 302，上限 20 次） |

**存储键名保持一致**（`classroom_settings` / `sessionCookies` / `scheduleCourses` 等），
便于跨平台对照维护；但两边数据互不打通（沙箱独立）。

---

## 五、安全与合规要点（对应原版上架红线）

- **隐私闸门**：`MainActivity` 在未同意隐私政策时不渲染三个 Tab，ViewModel 的联网入口同样有二次校验。
- **零注入面**：无 deep link / App Links 声明；`WebViewPage` 的 URL 走域名白名单校验（`neuq.edu.cn` / `tsiao.io` / `pages.dev` / `yuruan111.github.io`，后缀边界匹配，`evil-neuq.edu.cn.attacker.com` 不会误放行）。
- **明文 HTTP**：教务系统（`jwxt.neuq.edu.cn`）不支持 HTTPS，Manifest 中声明 `usesCleartextTraffic="true"`；隐私政策已说明。若你的构建环境不需要，可自行收紧。
- **日志**：`Logger` 不含 cookie、密码、完整响应体。
- **第三方许可**：课表模块参考/移植自 shiguangschedule（Apache-2.0），WebView 注入方案参考 neuq-classroom-app（MIT），空教室逻辑移植自 neuq-classroom-query（**无许可证，授权状态见根目录 `THIRD-PARTY-NOTICES.md`**）。App 内「开源许可」入口已同步展示。
- **密码不进仓库**：`SecretStore` 的密码仅存本机加密存储；不要把填了密码的 `classroom_settings.xml` 提交。

---

## 六、构建验证状态

**本工程已通过一次完整 Gradle 构建（含真实 Android SDK，AAPT 资源链接 / Manifest 合并 / Compose 编译 / dex 全链路）。**

| 项 | 结果 |
|---|---|
| 构建命令 | `gradlew assembleDebug`（标准 wrapper → Gradle 8.9 + JDK 21 + Android SDK Platform 34 / Build-Tools 34.0.0） |
| 结果 | `BUILD SUCCESSFUL in 4m 44s`，36 个 task 全部执行 |
| 产物 | `app/build/outputs/apk/debug/app-debug.apk` = 17.49 MB（18,343,515 字节量级） |
| 编译告警 | 仅 2 条代码告警，均无害：`URL(String)` 弃用、`Icons.Filled.ArrowBack` 弃用 |
| 静态校验 | Kotlin 编译器全量编译 32 个源文件（含 Compose 插件）：0 error，228 个 class |

> 构建日志里的 `Warning: Failed to download any source lists` 是 Gradle 去取依赖的
> sources jar 失败，与本工程代码无关，不影响构建结果。

### 运行时验证：部分完成

首轮验证发现并已修复以下问题：

| 问题 | 原因 | 修复 |
|---|---|---|
| **顶部状态栏与页面内容重叠** | `MainActivity` 调用了 `enableEdgeToEdge()`（内容画到系统栏下方），但只给了底栏 `navigationBarsPadding()`，顶部缺 inset；三个页面顶部还是硬编码 `12.dp` | 内容区补 `windowInsetsPadding(WindowInsets.safeDrawing.only(Top))`；`WebLoginPage` / `WebViewPage` 顶栏同样处理，网页区另补底部 inset；首启隐私弹窗卡片约束进安全区 |

**仍未验证**：安装到真机/模拟器后的实际交互（首启闸门、查询、课表渲染、WebView 登录）。
建议按下面顺序过一遍：

1. 首启隐私弹窗（顶部不应被状态栏压住）→ 点「退出应用」应干净结束进程；
2. 空教室页「查询」（默认云端源，免登录）；
3. 课表页周网格渲染与左右滑动切周；
4. 「小秦帮 → 教务登录」走 WebView 登录后回主页刷新会话。

---

## 七、已知限制

1. **图标为临时设计**：`ic_launcher` 是本次补的矢量占位图标（蓝色底 + 白色课表格卡片），
   能正常显示但不是最终视觉。正式发布前建议用 Android Studio 的 Image Asset 工具，
   从 `../AppScope/resources/base/media/` 或 `../tools/icon_out2/store_icon_1024.png` 生成正式图标。
2. **未在真机/模拟器上跑过交互**：见上节；编译与打包产物可用，但界面行为仅做了代码层修复。
3. **校历 OCR 未移植**：学期起点支持手动选择日期；若需要图片识别开学日期，可后续接 ML Kit。
4. **JDK 版本**：实测 JDK 17 (Temurin) 构建通过。JDK 23 未实测（AGP 8.5 官方支持到 21，
   更版本可能报错），建议用 JDK 17。
5. **Compose 版本三者联动**：BOM 2024.09.02（Compose 1.7.x / Material3 1.3.x）、
   Kotlin 2.0.20、Compose 编译器插件 2.0.20 已对齐，请勿单独升级其中一个。

---

## 七、从 HarmonyOS 版同步代码

移植遵循「一一对应」原则，HarmonyOS 源码位置与 Kotlin 文件的映射：

| HarmonyOS（`entry/src/main/ets/`） | Android（`app/src/main/java/.../`） |
|---|---|
| `config/AppConfig.ets` | `data/AppConfig.kt` |
| `common/AppError.ets` | `data/AppError.kt` |
| `common/DateUtil.ets` | `data/DateUtil.kt` |
| `common/AppKeys.ets` | `data/Keys.kt` |
| `common/SecretStore.ets` | `data/SecretStore.kt` |
| `common/Logger.ets` | `data/AppError.kt`（Logger） |
| `model/ClassroomModels.ets` | `data/ClassroomModels.kt` |
| `network/HttpService.ets` + `CookieStore.ets` | `net/HttpService.kt` |
| `crypto/WebvpnCrypto.ets` | `net/WebvpnCrypto.kt` |
| `webvpn/WebvpnSession.ets` | `net/WebvpnSession.kt` |
| `parser/HtmlParser.ets` | `net/HtmlParser.kt` |
| `parser/CourseFileParser.ets` | `data/CourseFileParser.kt` |
| `service/ClassroomClient.ets` | `net/ClassroomClient.kt` |
| `service/ClassroomService.ets` | `data/ClassroomService.kt` |
| `service/CloudService.ets` | `data/CloudService.kt` |
| `service/CloudCache.ets` | `data/CloudCache.kt` |
| `service/Processor.ets` | `data/Processor.kt` |
| `service/ScheduleService.ets` | `data/ScheduleService.kt` |
| `service/HolidayService.ets` | `data/HolidayService.kt` |
| `views/CourseModels.ets` | `data/CourseModels.kt` |
| `views/CourseGridView.ets` | `ui/screens/CourseGridView.kt` |
| `views/ScheduleView.ets` | `ui/screens/ScheduleScreen.kt` + `SettingsDialogs.kt` |
| `views/XiaoQinView.ets` | `ui/screens/XiaoQinScreen.kt` |
| `views/ConsentDialog.ets` | `ui/screens/ConsentDialog.kt` |
| `pages/MainPage.ets` | `MainActivity.kt` |
| `pages/Index.ets` | `ui/screens/EmptyClassroomScreen.kt` + `MainViewModel.kt` |
| `pages/WebLogin.ets` | `ui/screens/WebViewPage.kt` |
| `pages/WebViewPage.ets` | `ui/screens/WebViewPage.kt` |

后续若 HarmonyOS 版修复了某处逻辑，按此表同步对应 Kotlin 文件即可。
