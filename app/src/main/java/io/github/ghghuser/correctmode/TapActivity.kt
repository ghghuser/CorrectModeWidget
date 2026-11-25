package io.github.ghghuser.correctmode

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** The two "ignore this tap" windows; shared so the diagnostics text in Settings always shows the real numbers. */
object TapLimits {
    /** a tap this soon after the previous tap finished is ignored (double tap) */
    const val DEBOUNCE_MS = 700L
    /** a tap this soon after turning on is ignored (it would turn it straight off again) */
    const val ON_GUARD_MS = 1500L
}

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
        const val DEBOUNCE_MS = TapLimits.DEBOUNCE_MS
        const val ON_GUARD_MS = TapLimits.ON_GUARD_MS
    }

    private var done = false
    private var t0 = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        t0 = SystemClock.elapsedRealtime()
        if (t0 - lastDone < DEBOUNCE_MS) {
            try { Store.setTapOutcome(this, 1, t0 - lastDone) } catch (e: Exception) { }
            done = true; finishAndRemoveTask(); return
        }
        Flow.fromWidget = true
        // grey the buttons while this tap is handled and a moment after it (taps are ignored then)
        try {
            Store.setBusyUntil(this, System.currentTimeMillis() + 4000L)
            Scheduler.scheduleRedraw(applicationContext, 5000L) // safety net if the process dies while a dialog is open
            Widgets.updateAll(this)
        } catch (e: Exception) { }
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
        // A tap inside this window after turning on is ignored (it was 2 s, 1.5 s on request; to be tried on the device).
        val st = Store.state(this)
        if (st.activeId != null && candidates.any { it.id == st.activeId } &&
            System.currentTimeMillis() - st.startedAt < ON_GUARD_MS
        ) {
            try { Store.setTapOutcome(this, 2, System.currentTimeMillis() - st.startedAt) } catch (e: Exception) { }
            finishNow()
            return
        }
        try { Store.setTapOutcome(this, 0, 0L) } catch (e: Exception) { }
        val releasing = st.activeId.let { id -> val k = candidates.indexOfFirst { it.id == id }
            k >= 0 && (data?.host != "cycle" || k == candidates.lastIndex) }
        // Look at the real device first (volume key, quick settings changed from outside), so a switch starts from the
        // true state. NOT when this tap releases: reconcile could end the session as "changed from outside", and the
        // tap then saw "nothing active" and turned the profile ON again (a tap on an ON widget did not release it).
        if (!releasing) try { Engine.reconcile(this) } catch (e: Exception) { }
        // The position in the cycle is the one the widget showed when it was tapped, even if reconcile ended that session:
        // the tap still means "next one".
        val i = candidates.indexOfFirst { it.id == st.activeId }
        when {
            data?.host != "cycle" -> Flow.toggle(this, candidates[0]) { finishNow() }
            i < 0 -> Flow.start(this, candidates[0]) { finishNow() }
            i == candidates.lastIndex -> Flow.toggle(this, candidates[i]) { finishNow() } // last one: turn off
            else -> Flow.start(this, candidates[i + 1]) { finishNow() }
        }
    }

    /**
     * The greyed look ends DEBOUNCE_MS after the tap was handled. If the process is gone before that, the
     * recheck alarm redraws the widgets (Engine.reconcile ends in Widgets.updateAll), so they cannot stay grey.
     */
    private fun endBusy() {
        val app = applicationContext
        try {
            Store.setBusyUntil(app, System.currentTimeMillis() + DEBOUNCE_MS)
            Scheduler.scheduleRedraw(app, 2500L)
            Handler(Looper.getMainLooper()).postDelayed({
                try { Store.setBusyUntil(app, 0L); Widgets.updateAll(app) } catch (e: Exception) { }
            }, DEBOUNCE_MS + 50L)
        } catch (e: Exception) { }
    }

    private fun finishNow() {
        if (done) return
        done = true
        Flow.fromWidget = false
        val now = SystemClock.elapsedRealtime()
        lastDone = now
        try { Store.setLastTapMs(this, now - t0) } catch (e: Exception) { }
        endBusy()
        if (!isFinishing) finishAndRemoveTask()
    }
}
