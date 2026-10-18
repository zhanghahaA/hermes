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
