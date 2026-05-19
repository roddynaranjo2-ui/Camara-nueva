#!/usr/bin/env bash
# ============================================================================
#  Rodyto Lens Pro · verify.sh · v3.7 (refactor 7-file split — corregido)
#
#  Verifica que la división del antiguo CameraControlViewModel.kt y
#  MainActivity.kt en 7 archivos especializados quedó correcta y que los
#  flags críticos siguen en valores seguros por defecto.
#
#  CORRECCIONES v3.7:
#   • Reparado SYNTAX ERROR de quotes mezcladas en el check de
#     CameraControlViewModelOps.kt (la línea no terminaba bien, todo el
#     script se rompía con "unexpected EOF").
#   • Actualizado el check de pinchToZoom: la v3.7 usa awaitPointerEventScope
#     (no awaitEachGesture).
#   • Actualizado el regex de DisposableEffect: ahora coincide con
#     "DisposableEffect(active, frontFlag)" usando escape correcto.
#   • Añadidos checks nuevos para los wirings reconectados:
#     - HorizonLevel con previewBounds real (no null literal).
#     - ShutterBlink con triggerKey > 0 dinámico desde VM.
#     - LensSelector con onSelectLens conectado al ViewModel.
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

echo "=== Rodyto Lens Pro v3.7 — verificación post-refactor (7 archivos) ==="
echo ""

echo "→ build.gradle"
check_contains "$ROOT/app/build.gradle" 'namespace .com\.rodyto\.lenspro.'    "namespace = com.rodyto.lenspro"
check_contains "$ROOT/app/build.gradle" 'applicationId "com\.rodyto\.lenspro"' "applicationId = com.rodyto.lenspro"
check_contains "$ROOT/app/build.gradle" 'compose-bom:2024\.12\.01'              "Compose BOM 2024.12.01"
check_contains "$ROOT/app/build.gradle" 'camera-core:..camerax_version'         "CameraX expuesto"

echo ""
echo "→ AndroidManifest.xml"
check_contains "$ROOT/app/src/main/AndroidManifest.xml" 'requestLegacyExternalStorage="false"' "scoped storage"
check_contains "$ROOT/app/src/main/AndroidManifest.xml" 'enableOnBackInvokedCallback'           "Predictive Back habilitado"

echo ""
echo "→ División ViewModel (4 archivos)"
check_contains "$SRC/CameraEnums.kt"                  'package com\.rodyto\.lenspro'             "CameraEnums package OK"
check_contains "$SRC/CameraEnums.kt"                  'enum class VideoResolution'               "VideoResolution definido"
check_contains "$SRC/CameraEnums.kt"                  'enum class CameraSessionState'            "CameraSessionState definido"
check_contains "$SRC/CameraControlViewModelState.kt"  'package com\.rodyto\.lenspro'             "VM State package OK"
check_contains "$SRC/CameraControlViewModelCore.kt"   'ensureBackgroundThread'                   "Background thread idempotente"
check_contains "$SRC/CameraControlViewModelOps.kt"    'applyManualSettings'                      "Manual settings unificado"
check_contains "$SRC/CameraControlViewModel.kt"       'MAX_DIGITAL_ZOOM'                         "Companion MAX_DIGITAL_ZOOM"
check_contains "$SRC/CameraControlViewModel.kt"       'external fun getPhysicalCameraIdsNative'  "Binding JNI activo"

echo ""
echo "→ División MainActivity (3 archivos)"
check_contains "$SRC/MainActivityCore.kt"     'package com\.rodyto\.lenspro'  "MainActivityCore package OK"
check_contains "$SRC/MainActivityCore.kt"     'enableEdgeToEdge'              "enableEdgeToEdge presente"
check_contains "$SRC/MainActivityCore.kt"     'registerForActivityResult'     "Permisos modernos"
check_contains "$SRC/MainActivityOverlays.kt" 'ActionChipBar'                 "Overlay usa ActionChipBar"
check_contains "$SRC/MainActivityHelpers.kt"  'ShutterGlass'                  "Helper usa ShutterGlass real"

echo ""
echo "→ CameraPreview.kt"
check_contains "$SRC/CameraPreview.kt"  'awaitPointerEventScope'   "pinchToZoom v3.7 (awaitPointerEventScope)"
check_contains "$SRC/CameraPreview.kt"  'lastAppliedSw'            "guard de setAspectRatio en update"

echo ""
echo "→ CameraXBridge.kt"
check_contains "$SRC/CameraXBridge.kt"  'DisposableEffect\(active, frontFlag\)'  "key reactiva"

echo ""
echo "→ GlassUi.kt / ShutterButtonPro.kt / CameraTuning.kt"
check_contains "$SRC/GlassUi.kt"           'coerceIn\(1f, 20f\)'        "blur cap 20f"
check_contains "$SRC/ShutterButtonPro.kt"  'RecordingRingPulse'         "InfiniteTransition condicional"
check_contains "$SRC/ShutterButtonPro.kt"  'pointerInput\(isRecording, mode\)' "pointerInput con mode key (fix B-06)"
check_contains "$SRC/CameraTuning.kt"      'displayLongEdgePx'          "previewSize con display hint"

echo ""
echo "→ MultiChannelImageReader.kt / Zoom* / LensSelector / AutoFitSurfaceView / HorizonLevel"
check_contains "$SRC/MultiChannelImageReader.kt"  'lastCameraId: String'   "cache por cameraId string"
check_contains "$SRC/ZoomControl.kt"              'MIN_HAPTIC_INTERVAL_MS' "ZoomControl haptic throttle"
check_contains "$SRC/ZoomDial.kt"                 'MAX_DIGITAL_ZOOM'       "ZoomDial referencia VM"
check_contains "$SRC/LensSelector.kt"             'DampingRatioNoBouncy'   "animación lens rápida"
check_contains "$SRC/AutoFitSurfaceView.kt"       'epsilon'                "AutoFit con epsilon"
check_contains "$SRC/HorizonLevel.kt"             'if .!enabled.'          "unregister inmediato si !enabled"

echo ""
echo "→ Wirings reconectados v3.7 (fixes B-03 / B-04 / B-05)"
check_contains "$SRC/MainActivityHelpers.kt"  'onSelectLens|setLens'        "LensSelector cableado al VM (fix B-03)"
check_contains "$SRC/MainActivityOverlays.kt" 'previewBounds = previewBounds' "HorizonLevel con bounds reales (fix B-04)"
check_contains "$SRC/MainActivityOverlays.kt" 'shutterBlinkKey'             "ShutterBlink con triggerKey dinámico (fix B-05)"
check_contains "$SRC/CameraControlViewModelState.kt" '_shutterBlinkKey'     "VM expone shutterBlinkKey"

echo ""
echo "============================================="
echo "  OK: $ok    FAIL: $fail"
echo "============================================="
if [[ $fail -gt 0 ]]; then exit 1; fi
exit 0
