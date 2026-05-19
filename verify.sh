#!/usr/bin/env bash
# ============================================================================
#  Rodyto Lens Pro · verify.sh · v3.6
#
#  Script de verificación rápida POST-merge.
#  Comprueba que los archivos clave existen, contienen los marcadores v3.6
#  esperados, y que los flags críticos están en su valor seguro por defecto.
#
#  Uso:  bash verify.sh
# ============================================================================
set -u

ROOT="$(cd "$(dirname "$0")" && pwd)"
SRC="$ROOT/app/src/main/java/com/rodyto/lenspro"
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

echo "=== Rodyto Lens Pro v3.6 — verificación post-merge ==="
echo ""

echo "→ build.gradle"
check_contains "$ROOT/app/build.gradle" 'versionName "3\.6"'           "versionName = 3.6"
check_contains "$ROOT/app/build.gradle" 'versionCode 8'                "versionCode = 8"
check_contains "$ROOT/app/build.gradle" 'compose-bom:2024\.12\.01'     "Compose BOM 2024.12.01"
check_contains "$ROOT/app/build.gradle" 'camera-core:..camerax_version' "CameraX expuesto"

echo ""
echo "→ AndroidManifest.xml"
check_contains "$ROOT/app/src/main/AndroidManifest.xml" 'requestLegacyExternalStorage="false"' "requestLegacyExternalStorage=false"
check_contains "$ROOT/app/src/main/AndroidManifest.xml" 'enableOnBackInvokedCallback'           "Predictive Back habilitado"

echo ""
echo "→ ViewModel (defaults críticos)"
check_contains "$SRC/CameraControlViewModel.kt" '_useCameraXAnalysis = MutableStateFlow\(false\)'   "useCameraXAnalysis default=false"
check_contains "$SRC/CameraControlViewModel.kt" '_forceTelePhysicalId = MutableStateFlow\(false\)'  "forceTelePhysicalId default=false"
check_contains "$SRC/CameraControlViewModel.kt" 'PREVIEW_COALESCE_MS'                                "applyRepeatingPreview coalescing"
check_contains "$SRC/CameraControlViewModel.kt" 'attached = AtomicBoolean'                          "attachToRepository idempotente"

echo ""
echo "→ CameraPreview.kt"
check_contains "$SRC/CameraPreview.kt"      'awaitEachGesture'                                   "pinchToZoom con awaitEachGesture"
check_contains "$SRC/CameraPreview.kt"      'lastAppliedSw'                                      "guard de setAspectRatio en update"

echo ""
echo "→ CameraXBridge.kt"
check_contains "$SRC/CameraXBridge.kt"      'if .!active.'                                       "guard early-return useCameraXAnalysis"
check_contains "$SRC/CameraXBridge.kt"      'DisposableEffect.active, frontFlag'                 "key reactiva"

echo ""
echo "→ GlassUi.kt"
check_contains "$SRC/GlassUi.kt"            'if .!active. return this'                           "whiteGlow no-op si !active"
check_contains "$SRC/GlassUi.kt"            'coerceIn\(1f, 20f\)'                                "blur cap 20f"

echo ""
echo "→ SettingsActivity.kt"
check_contains "$SRC/SettingsActivity.kt"   'items = AccentStyle.entries'                       "paletas en items() reciclados"
check_contains "$SRC/SettingsActivity.kt"   'setAccentIndex\(idx\)'                              "selección directa de paleta"

echo ""
echo "→ ShutterButtonPro.kt"
check_contains "$SRC/ShutterButtonPro.kt"   'RecordingRingPulse'                                 "InfiniteTransition condicional"

echo ""
echo "→ CameraTuning.kt"
check_contains "$SRC/CameraTuning.kt"       'displayLongEdgePx'                                  "pickOptimalPreviewSize con display hint"
check_contains "$SRC/CameraTuning.kt"       'WIDE_FPS_RANGE_THRESHOLD'                           "bestFpsRange anti-rangos-amplios"

echo ""
echo "→ MultiChannelImageReader.kt"
check_contains "$SRC/MultiChannelImageReader.kt" 'lastCameraId: String'                          "cache por cameraId string"

echo ""
echo "→ ZoomControl.kt / ZoomDial.kt"
check_contains "$SRC/ZoomControl.kt"        'MIN_HAPTIC_INTERVAL_MS'                             "ZoomControl haptic throttle"
check_contains "$SRC/ZoomDial.kt"           'MIN_HAPTIC_INTERVAL_MS'                             "ZoomDial haptic throttle"

echo ""
echo "→ LensSelector.kt"
check_contains "$SRC/LensSelector.kt"       'DampingRatioNoBouncy'                               "animación lens rápida sin rebote"

echo ""
echo "→ AutoFitSurfaceView.kt"
check_contains "$SRC/AutoFitSurfaceView.kt" 'epsilon = 0\.006f'                                  "epsilon 0.006f"

echo ""
echo "→ HorizonLevel.kt"
check_contains "$SRC/HorizonLevel.kt"       'if .!enabled.'                                      "unregister inmediato si !enabled"

echo ""
echo "============================================="
echo "  OK: $ok    FAIL: $fail"
echo "============================================="
if [[ $fail -gt 0 ]]; then
    exit 1
fi
exit 0
