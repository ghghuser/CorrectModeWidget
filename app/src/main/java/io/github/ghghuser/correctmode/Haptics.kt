package io.github.ghghuser.correctmode

import android.content.Context
import android.media.AudioAttributes
import android.os.VibrationEffect
import android.os.VibratorManager

/**
 * The small vibration after a widget tap (an option of each profile). Never makes a sound.
 * It uses the ALARM vibration channel, so it only works while "Alarm vibration" is on in the phone settings
 * (Sound & vibration > Vibration & haptics). On: one tick. Off: two ticks. Settle gives it once the change has settled.
 */
object Haptics {
    fun vibrate(ctx: Context, turnedOn: Boolean) {
        try {
            val v = ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
            val effect = if (turnedOn) VibrationEffect.createWaveform(longArrayOf(0, 40), -1)
            else VibrationEffect.createWaveform(longArrayOf(0, 30, 70, 30), -1)
            v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        } catch (e: Exception) {
            // no vibrator / not allowed: nothing to report
        }
    }
}
