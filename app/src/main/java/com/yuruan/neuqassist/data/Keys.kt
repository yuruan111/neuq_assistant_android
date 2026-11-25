package com.yuruan.neuqassist.data

/**
 * 偏好存储键名（对应 HarmonyOS 端 common/AppKeys.ets）
 *
 * ⚠️ 既有键名的字符串值不得改动——改动等于老用户已落盘数据全部失联。
 * Android 端为全新安装，但保持同一套键名便于跨平台对照维护。
 */
object Keys {
    /** SharedPreferences 文件名（账号、课表、外观、会话等设置共用一个存储） */
    const val PREF_FILE = "classroom_settings"

    /** 网页登录会话 */
    const val KEY_WEB_SESSION_COOKIES = "webSessionCookies"
    const val KEY_WEB_SESSION_MODE = "webSessionMode"
    const val KEY_SESSION_COOKIES = "sessionCookies"
    const val KEY_SESSION_MODE = "sessionMode"
    const val KEY_USE_WEBVPN = "useWebvpn"
    const val KEY_TERM_START_DATE = "termStartDate"

    /** 首启隐私政策同意标记 */
    const val KEY_PRIVACY_AGREED = "privacyAgreed"

    /** 隐私政策在线页 */
    const val PRIVACY_POLICY_URL = "https://yuruan111.github.io/neuq-privacy/"

    // —— 课表 ——
    const val KEY_TIME_SLOTS = "customTimeSlots"
    const val KEY_TERM_START = "termStartDate"
    const val KEY_SCHEDULE_COURSES = "scheduleCourses"

    // —— 空教室设置 ——
    const val KEY_USERNAME = "username"
    const val KEY_AUTH_TYPE = "authType"
    const val KEY_WEBVPN_USERNAME = "webvpnUsername"
    const val KEY_CLOUD_SOURCE = "cloudSource"
    const val KEY_SERVER_URL = "serverUrl"

    // —— 外观 ——
    const val KEY_BG_ENABLED = "bgEnabled"
    const val KEY_BG_IMAGE_PATH = "bgImagePath"
    const val KEY_BG_DIM = "bgDim"
    const val KEY_LINE_WEIGHT = "lineWeight"
    const val KEY_DOCK_OPACITY = "dockBarOpacity"

    // —— 个性化设置（与 hap common/AppKeys.ets 新增键一致；校验与回退统一在 Personalize） ——
    /** 主题预设：'blue' | 'orange' | 'green' | 'purple' */
    const val KEY_THEME_PRESET = "themePreset"
    /** 课表文字大小：'small' | 'standard' | 'large' */
    const val KEY_SCHEDULE_FONT_SCALE = "scheduleFontScale"
    /** 课表卡片圆角（dp，2-16） */
    const val KEY_SCHEDULE_CARD_RADIUS = "scheduleCardRadius"
    /** 冷启动默认栏目：0=课表查询 1=空教室 2=小秦帮 */
    const val KEY_DEFAULT_LAUNCH_TAB = "defaultLaunchTab"

    /** 密码的加密存储别名（EncryptedSharedPreferences 键） */
    const val ALIAS_JWXT_PASSWORD = "jwxt_password"
    const val ALIAS_WEBVPN_PASSWORD = "webvpn_password"
}
