package com.yuruan.neuqassist.data

/**
 * 构建标识 —— 用于在设备上确认「当前跑的是哪一次构建」。
 *
 * 排查线上/真机问题时，先看小秦帮页底部显示的这行标识：
 * 它与交付包名里的时间戳一致，可立即排除「装的是旧包」这类干扰。
 */
object BuildInfo {

    /** 交付包时间戳（与 zip/apk 文件名一致） */
    const val BUILD_TAG: String = "20261016-1015"

    /** 版本标识 */
    const val VERSION: String = "1.0.1-android"

    /** 小秦帮页展示用的一行文本 */
    fun display(): String = "构建 $BUILD_TAG · $VERSION"
}
