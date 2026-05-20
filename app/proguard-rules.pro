# ============================================================
# LensPro — ProGuard / R8 Rules v2.2
# ============================================================

# ── Reglas generales ─────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses,EnclosingMethod

# Mantener nombres de clases para debugging en crashlogs
-keepattributes !LocalVariableTable,!LocalVariableTypeTable

# ── Kotlin ───────────────────────────────────────────────────
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**

# Kotlin coroutines — necesario para que el runtime no se rompa en release
-keep class kotlinx.coroutines.** { *; }
-keepclassmembernames class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ── Jetpack Compose ──────────────────────────────────────────
# Compose no necesita reglas especiales en AGP 8+, pero sí los previews
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ── ViewModel / Lifecycle ────────────────────────────────────
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**

# ── DataStore ────────────────────────────────────────────────
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ── Camera2 API ──────────────────────────────────────────────
# Las clases de Camera2 son del sistema, pero sus callbacks se
# llaman por reflexión desde el HAL — nunca ofuscar.
-keep class android.hardware.camera2.** { *; }
-keep class android.media.Image { *; }
-keep class android.media.ImageReader { *; }
-keep class android.media.MediaRecorder { *; }
-keep class android.media.DngCreator { *; }

# ── NDK / JNI ────────────────────────────────────────────────
# Mantener el método native que llama la librería rodytolenspro.so
-keep class com.rodyto.lenspro.CameraControlViewModel {
    native <methods>;
    private native java.lang.String[] getPhysicalCameraIdsNative();
}

# ── Coil ─────────────────────────────────────────────────────
-keep class coil.** { *; }
-dontwarn coil.**

# ── MediaStore / ContentProvider ─────────────────────────────
-keep class android.provider.MediaStore { *; }
-keep class android.content.ContentValues { *; }

# ── Reflexión interna de Android ─────────────────────────────
-dontwarn android.content.res.Resources$NotFoundException
-dontwarn java.awt.**
-dontwarn javax.**
-dontwarn org.slf4j.**

# ── Reglas de enumeraciones ──────────────────────────────────
# R8 puede romper enums usadas en when() si no se conservan
-keepclassmembers enum com.rodyto.lenspro.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Clases de datos (data class) ─────────────────────────────
-keepclassmembers class com.rodyto.lenspro.** {
    public <init>(...);
}

# ── SamsungVendorTags — reflexión específica al constructor (CI-2)
-keepclassmembers class android.hardware.camera2.CaptureRequest$Key {
    <init>(java.lang.String, java.lang.Class);
}

# Mantener cualquier @Keep que añadamos
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * { @androidx.annotation.Keep <fields>; @androidx.annotation.Keep <methods>; }

# Compose: configurar el atomicfu solo si se usa
-dontwarn kotlinx.atomicfu.**
