package io.github.ghghuser.correctmode

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager

/** The small "it worked" vibration after a widget tap (an option of each profile). Never makes a sound. */
object Haptics {
    fun tap(ctx: Context, turnedOn: Boolean) {
        val app = ctx.applicationContext
        // a moment later: the ringer / DND change that was just made must be settled before the tick is requested
        Handler(Looper.getMainLooper()).postDelayed({ vibrate(app, turnedOn) }, 250L)
    }

    private fun vibrate(ctx: Context, turnedOn: Boolean) {
        try {
            val v = ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
            val effect = if (turnedOn) VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            else VibrationEffect.createWaveform(longArrayOf(0, 30, 70, 30), -1) // two ticks = off
            // ALARM usage: the system does not drop it because of the ringer mode, DND or the touch-vibration setting
            // (a short tick the user asked for on purpose). With the default usage it was silently suppressed.
            @Suppress("DEPRECATION")
            v.vibrate(effect, android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build())
        } catch (e: Exception) {
            // no vibrator / not allowed: nothing to report
        }
    }
}
