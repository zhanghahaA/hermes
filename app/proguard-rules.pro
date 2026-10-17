# 类型安全导航的路由类通过 kotlinx.serialization 序列化
-keepclassmembers @kotlinx.serialization.Serializable class com.hermes.mobile.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
