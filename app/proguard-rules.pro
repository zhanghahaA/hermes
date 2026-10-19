# kotlinx.serialization：保留 @Serializable 类生成的序列化器（官方推荐规则）
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.hermes.mobile.**$$serializer { *; }
-keepclassmembers class com.hermes.mobile.** {
    *** Companion;
}
-keepclasseswithmembers class com.hermes.mobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# SSH 隧道（sshj + BouncyCastle）：大量通过反射/名字加载算法，整体保留
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn net.schmizz.**
-dontwarn com.hierynomus.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**

# Bot 屏幕：WebView 里的 noVNC 页面经 @JavascriptInterface 调 App（方法名不能被混淆）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
