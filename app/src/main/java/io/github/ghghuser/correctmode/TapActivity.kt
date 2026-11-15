package io.github.ghghuser.correctmode

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.SystemClock

/**
 * Target of every widget tap. It looks at the REAL state at the moment of the tap (so a widget that has
 * not been redrawn yet can never do the wrong thing), then turns on / off. Own task, no history, not in
 * recents, always finishes with finishAndRemoveTask() so no empty transparent screen is left behind.
 *
 * Rapid taps: every tap starts its own TapActivity and they are processed one after the other, so three
 * quick taps used to mean three switches. A tap that arrives right after the previous one finished is
 * ignored (see lastDone).
 */
class TapActivity : Activity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Loc.wrap(newBase))

    private companion object {
        @Volatile var lastDone = 0L
        const val DEBOUNCE_MS = 700L
    }

    private var done = false
    private var t0 = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        t0 = SystemClock.elapsedRealtime()
        if (t0 - lastDone < DEBOUNCE_MS) { done = true; finishAndRemoveTask(); return }
        Flow.fromWidget = true
        // Look at the real device first: if it was changed from outside (volume key, quick settings), the
        // stale "on" is cleaned up here, so the tap below acts on the truth, not on an old widget picture.
        try { Engine.reconcile(this) } catch (e: Exception) { }
        val data = intent?.data
        val key = data?.lastPathSegment
        // "tap/<profile id>" = a button of the switch widget; "cycle/<widget id>" = the toggle widget
        val candidates: List<Profile> = if (data?.host == "cycle") {
            Store.widgetProfiles(this, key?.toIntOrNull() ?: -1)?.mapNotNull { Store.profile(this, it) }.orEmpty()
        } else listOfNotNull(Store.profile(this, key))
        if (candidates.isEmpty()) {
            Ui.toast(this, Loc.str(this, R.string.err_profile_missing), true)
            finishNow()
            return
        }
        // A double tap must not turn it on and straight off again (that would put the loud state back).
        val st = Store.state(this)
        if (st.activeId != null && candidates.any { it.id == st.activeId } &&
            System.currentTimeMillis() - st.startedAt < 2000L
        ) {
            finishNow()
            return
        }
        val i = candidates.indexOfFirst { it.id == st.activeId }
        when {
            data?.host != "cycle" -> Flow.toggle(this, candidates[0]) { finishNow() }
            i < 0 -> Flow.start(this, candidates[0]) { finishNow() }
            i == candidates.lastIndex -> Flow.toggle(this, candidates[i]) { finishNow() } // last one: turn off
            else -> Flow.start(this, candidates[i + 1]) { finishNow() }
        }
    }

    private fun finishNow() {
        if (done) return
        done = true
        Flow.fromWidget = false
        val now = SystemClock.elapsedRealtime()
        lastDone = now
        try { Store.setLastTapMs(this, now - t0) } catch (e: Exception) { }
        if (!isFinishing) finishAndRemoveTask()
    }
}
