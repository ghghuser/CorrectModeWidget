package io.github.ghghuser.correctmode

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.VibrationEffect
import android.os.VibratorManager

/**
 * The small vibration after a widget tap (an option of each profile). Never makes a sound.
 * On: one tick. Off: two ticks. Settle gives it once the change has settled.
 *
 * The usage decides which phone switch and which silent state applies. ALARM works while the alarm channel is open
 * (reported to work for the normal / vibrate ringer and a filter that lets alarms through). In the other states
 * (muted ringer, a filter without alarms, all blocked) the same vibration stayed silent, so the "accessibility" usage
 * is used there: it is not one of the categories the ringer / filter silence. UNVERIFIED on a device (see the
 * diagnostics line in Settings, which shows the usage of the last tick).
 */
object Haptics {
    fun vibrate(ctx: Context, turnedOn: Boolean) {
        try {
            val v = ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
            val effect = if (turnedOn) VibrationEffect.createWaveform(longArrayOf(0, 40), -1)
            else VibrationEffect.createWaveform(longArrayOf(0, 30, 70, 30), -1)
            val alarm = alarmChannelOpen(ctx)
            val usage = if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
            v.vibrate(effect, AudioAttributes.Builder().setUsage(usage).build())
            Store.setLastTick(ctx, if (alarm) "alarm" else "accessibility")
        } catch (e: Exception) {
            // no vibrator / not allowed: nothing to report
        }
    }

    /** true while the ringer / filter lets alarm sounds (and their vibration) through. */
    private fun alarmChannelOpen(ctx: Context): Boolean {
        if (ctx.getSystemService(AudioManager::class.java)?.ringerMode == AudioManager.RINGER_MODE_SILENT) return false
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return true
        return when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_NONE -> false
            NotificationManager.INTERRUPTION_FILTER_PRIORITY ->
                nm.notificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS != 0
            else -> true
        }
    }
}
