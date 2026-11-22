# Android 移植版工程（小秦帮）

从 HarmonyOS ArkTS 工程移植而来的 Kotlin + Jetpack Compose 版本。
构建指南、目录结构、与 HarmonyOS 版的差异说明见 [README.md](README.md)。

快速开始：用 Android Studio 打开本 `android/` 目录 → Sync Gradle → Run。
命令行：`gradlew assembleDebug` → 产物 `app/build/outputs/apk/debug/app-debug.apk`。

## 构建前置

- JDK 17+
- Android SDK Platform 34
- （可选）Android Studio Ladybug 或更高

## 图标

未附带 Launcher 图标资源。可用 Android Studio 的 Image Asset 工具生成，
源图可取自仓库 `../tools/icon_out2/store_icon_1024.png`。
