# ---- HR Bridge v2.0 ProGuard Rules ----
# 注意：去掉了 v1 版本全量保留所有类的规则，让 R8 真正生效以控制 APK 体积

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# Moshi (反射)
-keep class com.squareup.moshi.** { *; }
-keep @com.squareup.moshi.JsonClass class *
-keepclassmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}

# Room
-keep class androidx.room.** { *; }
-keep @androidx.room.* class * { *; }

# Compose 生成的 Lambda
-dontwarn androidx.compose.**

# 保留 Entity / Dao / Api model（避免字段被混淆导致序列化失败）
-keep class cn.jarvis.hrbridge.data.** { *; }

# BuildConfig
-keep class cn.jarvis.hrbridge.BuildConfig { *; }
