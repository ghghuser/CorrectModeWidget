package io.github.ghghuser.correctmode

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager

/** The small "it worked" vibration after a widget tap (an option of each profile). Never makes a sound. */
object Haptics {
    fun tap(ctx: Context, turnedOn: Boolean) {
        try {
            val v = ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
            val effect = if (turnedOn) VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            else VibrationEffect.createWaveform(longArrayOf(0, 30, 70, 30), -1) // two ticks = off
            v.vibrate(effect)
        } catch (e: Exception) {
            // no vibrator / not allowed: nothing to report
        }
    }
}
