package io.github.ghghuser.correctmode

import android.app.Activity
import android.content.Context
import android.os.Bundle

/**
 * Target of every widget tap. It looks at the REAL state at the moment of the tap (so a widget that has
 * not been redrawn yet can never do the wrong thing), then turns on / off. Own task, no history, not in
 * recents, always finishes with finishAndRemoveTask() so no empty transparent screen is left behind.
 */
class TapActivity : Activity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Loc.wrap(newBase))

    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Look at the real device first: if it was changed from outside (volume key, quick settings), the
        // stale "on" is cleaned up here, so the tap below acts on the truth, not on an old widget picture.
        try { Engine.reconcile(this) } catch (e: Exception) { }
        val id = intent?.data?.lastPathSegment
        val p = Store.profile(this, id)
        if (p == null) {
            Ui.toast(this, Loc.str(this, R.string.err_profile_missing), true)
            finishNow()
            return
        }
        // A double tap must not turn it on and straight off again (that would put the loud state back).
        val st = Store.state(this)
        if (st.activeId == p.id && System.currentTimeMillis() - st.startedAt < 2000L) {
            finishNow()
            return
        }
        Flow.toggle(this, p) { finishNow() }
    }

    private fun finishNow() {
        if (done) return
        done = true
        if (!isFinishing) finishAndRemoveTask()
    }
}
