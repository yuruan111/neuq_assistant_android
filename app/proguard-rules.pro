# R8（release 已启用 isMinifyEnabled + isShrinkResources）
# Compose / DataStore / Tink 自带 consumer rules，这里只补缺失类提示。
# Tink（androidx.security:security-crypto 依赖）引用 JSR-305 与 ErrorProne 注解，运行时不需要：
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**

# 保留 WebView JS 桥方法（当前未使用 @JavascriptInterface，留作扩展安全网）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
