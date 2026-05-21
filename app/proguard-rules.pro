# ============================================================
# LensPro — ProGuard / R8 Rules v3.0
# ============================================================

-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses,EnclosingMethod
-keepattributes !LocalVariableTable,!LocalVariableTypeTable

# ── Kotlin ───────────────────────────────────────────────────
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**

# Kotlin coroutines
-keep class kotlinx.coroutines.** { *; }
-keepclassmembernames class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ── Jetpack Compose ──────────────────────────────────────────
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
-keep class android.hardware.camera2.** { *; }
-keep class android.media.Image { *; }
-keep class android.media.ImageReader { *; }
-keep class android.media.MediaRecorder { *; }
-keep class android.media.DngCreator { *; }

# ── NDK / JNI · BUG-C3 / BUG-E8 ──────────────────────────────
# La librería se llama rodytolenspro (CMakeLists.add_library(rodytolenspro …))
# El método nativo Java_com_rodyto_lenspro_CameraControlViewModel_getPhysicalCameraIdsNative
# debe permanecer con su nombre.
-keep class com.rodyto.lenspro.CameraControlViewModel {
    native <methods>;
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

# ── Enumeraciones ────────────────────────────────────────────
-keepclassmembers enum com.rodyto.lenspro.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Clases de datos ──────────────────────────────────────────
-keepclassmembers class com.rodyto.lenspro.** {
    public <init>(...);
}

# ── SamsungVendorTags — reflexión específica al constructor ──
-keepclassmembers class android.hardware.camera2.CaptureRequest$Key {
    <init>(java.lang.String, java.lang.Class);
}

# ── ShutterFx / VideoRecordingController ─────────────────────
-keep class com.rodyto.lenspro.ShutterFx { *; }
-keep class com.rodyto.lenspro.camera.VideoRecordingController { *; }

# Mantener @Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * { @androidx.annotation.Keep <fields>; @androidx.annotation.Keep <methods>; }

# Compose atomicfu
-dontwarn kotlinx.atomicfu.**
