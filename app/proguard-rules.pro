# Reglas básicas para Jetpack Compose y Camera2
-keep class com.rodyto.lenspro.** { *; }
-keepclasseswithmembers class * {
    native <methods>;
}
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
