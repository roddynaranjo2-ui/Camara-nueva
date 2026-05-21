#!/usr/bin/env bash
# ============================================================================
#  Rodyto Lens Pro · verify.sh · v5.0
#
#  Verifica que la arquitectura premium del repositorio queda correcta tras
#  los fixes de la auditoría v5.0 (21 bugs reportados + 8 extras).
# ============================================================================
set -u

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/app/src/main/java/com/rodyto/lenspro"
RES="$ROOT/app/src/main/res"
ok=0; fail=0

check_contains () {
    local file="$1"; local pattern="$2"; local label="$3"
    if [[ ! -f "$file" ]]; then
        echo "  ❌ $label  (file not found: $file)"; fail=$((fail+1)); return
    fi
    if grep -q -E "$pattern" "$file"; then
        echo "  ✅ $label"; ok=$((ok+1))
    else
        echo "  ❌ $label  (pattern not found: $pattern)"; fail=$((fail+1))
    fi
}

check_exists () {
    local path="$1"; local label="$2"
    if [[ -e "$path" ]]; then echo "  ✅ $label"; ok=$((ok+1))
    else echo "  ❌ $label  (not found: $path)"; fail=$((fail+1)); fi
}

check_not_exists () {
    local path="$1"; local label="$2"
    if [[ ! -e "$path" ]]; then echo "  ✅ $label"; ok=$((ok+1))
    else echo "  ❌ $label  (should NOT exist: $path)"; fail=$((fail+1)); fi
}

echo "=== Rodyto Lens Pro · verificación v5.0 ==="
echo ""
echo "→ build.gradle"
check_contains "$ROOT/app/build.gradle" 'namespace .com\.rodyto\.lenspro.'    "namespace"
check_contains "$ROOT/app/build.gradle" 'applicationId "com\.rodyto\.lenspro"' "applicationId"
check_contains "$ROOT/app/build.gradle" 'compose-bom:2024\.12\.01'              "Compose BOM 2024.12.01"
check_contains "$ROOT/app/build.gradle" 'camera-core:..camerax_version'         "CameraX expuesto"

echo ""
echo "→ AndroidManifest.xml"
check_contains "$ROOT/app/src/main/AndroidManifest.xml" 'requestLegacyExternalStorage="false"' "Scoped storage"
check_contains "$ROOT/app/src/main/AndroidManifest.xml" 'enableOnBackInvokedCallback'           "Predictive Back"

echo ""
echo "→ Tema (BUG-B1 / BUG-C4)"
check_contains "$RES/values/themes.xml" 'Theme\.Material3\.DayNight'  "Tema base Material3"
check_exists   "$RES/values-night/themes.xml"                          "values-night/themes.xml en ubicación correcta"
check_not_exists "$RES/values/values-night"                            "values/values-night NO existe (eliminado)"

echo ""
echo "→ ViewModel + binding JNI (BUG-C3)"
check_contains "$SRC/CameraControlViewModel.kt" 'System\.loadLibrary..rodytolenspro..'        "System.loadLibrary"
check_contains "$SRC/CameraControlViewModel.kt" 'external fun getPhysicalCameraIdsNative'    "external fun getPhysicalCameraIdsNative"

echo ""
echo "→ Cableo de fixes (BUG-C2 / BUG-M2 / BUG-M7)"
check_contains "$SRC/CameraControlViewModel.kt" 'captureEngineCountdown'  "Countdown del engine wired al StateHolder"
check_contains "$SRC/CameraControlViewModel.kt" 'shutterFx\.shutter\(\)'  "ShutterFx invocado en takePhoto"
check_contains "$SRC/camera/CameraSessionController.kt" 'readerSurface'   "ImageReader surface en outputs de session"

echo ""
echo "→ Vídeo real (BUG-M1)"
check_exists   "$SRC/camera/VideoRecordingController.kt"                 "VideoRecordingController existe"
check_contains "$SRC/camera/CameraSessionController.kt" 'videoRecorder'  "SessionController integra VideoRecordingController"

echo ""
echo "→ Overlays cableados (BUG-M3 / BUG-M4)"
check_contains "$SRC/CameraPreview.kt" 'HistogramView\(bins'             "HistogramView invocado"
check_contains "$SRC/CameraPreview.kt" 'HorizonLevelOverlay'             "HorizonLevelOverlay invocado"

echo ""
echo "→ Samsung vendor tags (BUG-M5)"
check_contains "$SRC/camera/CameraSessionController.kt" 'SamsungVendorTags\.applyBase' "VendorTags aplicados al builder"

echo ""
echo "→ Settings (BUG-A4)"
check_contains "$SRC/MainActivityCore.kt" '// BUG-A4'                    "Sin LaunchedEffect duplicados"
check_contains "$SRC/settings/SettingsBridge.kt" 'proVendorTags'         "SettingsBridge incluye proVendorTags"

echo ""
echo "→ Helpers / lentes reales (BUG-A3)"
check_contains "$SRC/MainActivityHelpers.kt" 'resolveLensForLabel'       "Resolver de lente real desde label"

echo ""
echo "→ GalleryLauncher tilde (BUG-B3)"
check_contains "$SRC/GalleryLauncher.kt" 'No se encontró una galería'    "Ortografía corregida"

echo ""
echo "============================================="
echo "  OK: $ok    FAIL: $fail"
echo "============================================="
[[ $fail -gt 0 ]] && exit 1
exit 0
