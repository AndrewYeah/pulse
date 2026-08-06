# sing-box libbox 类不能被混淆，否则 JNI 桥接会失效
-keep class io.nekohasekai.libbox.** { *; }
-keep class io.nekohasekai.libbox.*$* { *; }

# SharedPreferences uses Gson for these persisted models. Keep their field
# names stable so a Release upgrade can read data written by an older build.
-keepclassmembers class com.andrew.proxyapp.data.** {
    <fields>;
}

# 保留所有 native 方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保留 Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# 保留 Parcelable
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
