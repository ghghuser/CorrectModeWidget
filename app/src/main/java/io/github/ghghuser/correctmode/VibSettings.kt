package io.github.ghghuser.correctmode

import android.content.Context
import android.provider.Settings

/**
 * The phone's vibration switches ("Vibration & haptics"), read from the system settings table, which any app may read.
 * The keys are not public API constants and phone makers may differ: a key that is missing gives [State.UNKNOWN],
 * never a guess.
 */
object VibSettings {
    enum class State { ON, OFF, UNKNOWN }

    private fun int(ctx: Context, key: String): Int? =
        try { Settings.System.getInt(ctx.contentResolver, key) } catch (e: Exception) { null }

    private fun state(vararg values: Int?): State {
        val known = values.filterNotNull()
        return when {
            known.isEmpty() -> State.UNKNOWN
            known.any { it == 0 } -> State.OFF
            else -> State.ON
        }
    }

    /** "Ring vibration" (while a call comes in). */
    fun ring(ctx: Context): State {
        val intensity = int(ctx, "ring_vibration_intensity")
        return if (intensity != null) state(intensity) else state(int(ctx, "vibrate_when_ringing"))
    }

    /** "Alarm vibration". */
    fun alarm(ctx: Context): State = state(int(ctx, "alarm_vibration_intensity"))

    /** "Touch feedback" (haptics of the interface). */
    fun touch(ctx: Context): State = state(int(ctx, "haptic_feedback_enabled"), int(ctx, "haptic_feedback_intensity"))

    fun label(ctx: Context, s: State): String = Loc.str(ctx, when (s) {
        State.ON -> R.string.vibs_on
        State.OFF -> R.string.vibs_off
        State.UNKNOWN -> R.string.vibs_unknown
    })
}
