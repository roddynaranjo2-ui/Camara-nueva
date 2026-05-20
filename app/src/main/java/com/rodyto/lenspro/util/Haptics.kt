package com.rodyto.lenspro.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Pequeña capa de respuesta háptica unificada.
 * - En API 30+ usa VibrationEffect.createPredefined / createOneShot con baja amplitud.
 * - En APIs anteriores cae en performHapticFeedback con constantes adecuadas.
 */
object Haptics {

    enum class Kind { TAP, SELECT, SUCCESS, WARN, LONG }

    fun perform(view: View, kind: Kind = Kind.TAP, enabled: Boolean = true) {
        if (!enabled) return
        try {
            // Camino moderno (Android 12+) con feedback constants extendidos
            val constant = when (kind) {
                Kind.TAP     -> HapticFeedbackConstants.KEYBOARD_TAP
                Kind.SELECT  -> HapticFeedbackConstants.CLOCK_TICK
                Kind.SUCCESS -> HapticFeedbackConstants.CONFIRM
                Kind.WARN    -> HapticFeedbackConstants.REJECT
                Kind.LONG    -> HapticFeedbackConstants.LONG_PRESS
            }
            view.performHapticFeedback(constant)
        } catch (_: Throwable) {
            // Fallback explícito al vibrador del sistema
            vibrate(view.context, kind)
        }
    }

    private fun vibrate(context: Context, kind: Kind) {
        try {
            val vib: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vib ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val (duration, amp) = when (kind) {
                    Kind.TAP     -> 12L to 60
                    Kind.SELECT  -> 18L to 80
                    Kind.SUCCESS -> 35L to 120
                    Kind.WARN    -> 60L to 180
                    Kind.LONG    -> 45L to 140
                }
                vib.vibrate(VibrationEffect.createOneShot(duration, amp))
            } else {
                @Suppress("DEPRECATION") vib.vibrate(30)
            }
        } catch (_: Throwable) { /* No interrumpir UI por vibración */ }
    }
}
