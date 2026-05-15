package com.rodyto.lenspro

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.SoundPool
import android.media.ToneGenerator
import android.util.Log

/**
 * Audio feedback minimalista y moderno.
 *
 *  - Disparo de foto       → MediaActionSound.SHUTTER_CLICK (estándar del sistema).
 *  - Inicio/fin de video   → MediaActionSound.START_/STOP_VIDEO_RECORDING.
 *  - Enfoque conseguido    → **TICK suave** generado vía ToneGenerator (DTMF corto,
 *    100 ms a 25% de volumen) — sustituye al beep agresivo de FOCUS_COMPLETE.
 *
 * La clase es segura de cerrar (`release`) y soporta múltiples llamadas
 * concurrentes sin reproducir tonos solapados.
 */
class ShutterFx {

    private val action = MediaActionSound().apply {
        load(MediaActionSound.SHUTTER_CLICK)
        load(MediaActionSound.START_VIDEO_RECORDING)
        load(MediaActionSound.STOP_VIDEO_RECORDING)
    }

    // ToneGenerator con bajo volumen para el "click" de enfoque
    private val tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_SYSTEM, 25)
    } catch (e: RuntimeException) {
        Log.w("ShutterFx", "ToneGenerator no disponible", e); null
    }

    fun shutter()       { runCatching { action.play(MediaActionSound.SHUTTER_CLICK) } }
    fun videoStart()    { runCatching { action.play(MediaActionSound.START_VIDEO_RECORDING) } }
    fun videoStop()     { runCatching { action.play(MediaActionSound.STOP_VIDEO_RECORDING) } }

    /** Tick minimalista de enfoque (~90 ms). */
    fun focusTick() {
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_PROMPT, 90) }
    }

    fun release() {
        runCatching { action.release() }
        runCatching { tone?.release() }
    }
}
