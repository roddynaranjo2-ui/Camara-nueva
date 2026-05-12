# Reglas existentes del proyecto + mejoras (no elimino nada)
-keepclasseswithmembernames class * {
    native <methods>;
}

# Mantener todo el paquete de la app
-keep class com.rodyto.lenspro.** { *; }
-keep class com.rodyto.** { *; }

# Reglas para Compose, ViewModel y Camera2
-keep class androidx.compose.** { *; }
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }

-dontwarn kotlinx.coroutines.**
-dontwarn com.google.android.material.**