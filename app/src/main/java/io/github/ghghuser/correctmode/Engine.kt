package io.github.ghghuser.correctmode

import android.app.NotificationManager
import android.app.NotificationManager.Policy
import android.content.Context
import android.media.AudioManager
import android.util.Log
import kotlin.math.roundToInt

class Result(val ok: Boolean, val message: String? = null, val needAccess: Boolean = false)

enum class Reason { USER, EXPIRED, DELETED }

/**
 * Applies / releases profiles. Design rules (all of them are about never being LOUD by mistake):
 *  1. The "before" state is saved to disk BEFORE anything is changed.
 *  2. Going quiet: ringer first (it is the quickest to silence), then DND, then the ringer is re-asserted
 *     (DND changes can move it).
 *  3. Every change is read back. If the device is LOUDER than requested, retry; if it still is, roll back
 *     to the saved state and report the reason instead of pretending it is on.
 *  4. Our own notifications are silent (see Notifier).
 */
object Engine {
    private const val TAG = "CorrectMode"
    private const val RETRIES = 3
    private val STREAMS = intArrayOf(
        AudioManager.STREAM_RING, AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_MUSIC, AudioManager.STREAM_ALARM,
    )

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)
    private fun am(ctx: Context) = ctx.getSystemService(AudioManager::class.java)

    fun hasAccess(ctx: Context): Boolean = nm(ctx).isNotificationPolicyAccessGranted

    // ---- public API -----------------------------------------------------------------------

    /** expiresAt: epoch millis, 0 = no limit. If another profile is active this is a "switch". */
    @Synchronized
    fun activate(ctx: Context, p: Profile, expiresAt: Long): Result {
        val app = ctx.applicationContext
        if (!hasAccess(app)) {
            return Result(false, Loc.str(app, R.string.err_no_access), needAccess = true)
        }
        return try {
            val prev = Store.state(app)
            val snap = prev.snapshot ?: takeSnapshot(app)
            val btConnected = Bt.audioConnected(app)
            val st = State(p.id, System.currentTimeMillis(), expiresAt, snap, btConnected)
            Store.saveState(app, st) // 1. save before touching anything
            val problem = applySystem(app, p, btConnected)
            if (problem != null) {
                restoreSnapshot(app, snap)
                Store.clearState(app)
                Scheduler.cancel(app)
                Notifier.cancelActive(app)
                val msg = Loc.str(app, R.string.err_apply_failed, problem)
                Store.setLastError(app, msg)
                Log.w(TAG, "activate failed: $problem")
                Widgets.updateAll(app)
                Result(false, msg)
            } else {
                Store.setLastError(app, null)
                Scheduler.schedule(app, expiresAt)
                Notifier.showActive(app, p, st)
                Widgets.updateAll(app)
                Result(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "activate", e)
            val msg = Loc.str(app, R.string.err_apply_failed, e.javaClass.simpleName + ": " + e.message)
            Store.setLastError(app, msg)
            Result(false, msg)
        }
    }

    @Synchronized
    fun release(ctx: Context, reason: Reason): Result {
        val app = ctx.applicationContext
        val st = Store.state(app)
        Scheduler.cancel(app)
        var problem: String? = null
        val snap = st.snapshot
        if (st.activeId != null && snap != null) {
            problem = try {
                if (!hasAccess(app)) {
                    Loc.str(app, R.string.err_no_access)
                } else {
                    val r = restoreSnapshot(app, snap)
                    // Volumes only AFTER the ringer / DND are back: while DND is still on, Android can refuse
                    // (or flip the ringer) when the ring volume is changed.
                    if (r == null) restoreVolumes(app, snap)
                    r
                }
            } catch (e: Exception) {
                e.javaClass.simpleName + ": " + e.message
            }
        }
        Store.clearState(app)
        Notifier.cancelActive(app)
        if (problem != null) {
            val msg = Loc.str(app, R.string.err_restore_failed, problem)
            Store.setLastError(app, msg)
            Notifier.info(app, msg)
            Log.w(TAG, "release problem: $problem")
        } else {
            Store.setLastError(app, null)
            if (reason == Reason.EXPIRED) Notifier.info(app, Loc.str(app, R.string.released_text))
        }
        Widgets.updateAll(app)
        return Result(problem == null, problem)
    }

    /** Called by the alarm, boot, package update, app start and Bluetooth events. */
    @Synchronized
    fun reconcile(ctx: Context) {
        val app = ctx.applicationContext
        val st = Store.state(app)
        val id = st.activeId
        if (id == null) {
            Scheduler.cancel(app)
            Notifier.cancelActive(app)
            Widgets.updateAll(app)
            return
        }
        val p = Store.profile(app, id)
        if (p == null) { release(app, Reason.DELETED); return }
        val now = System.currentTimeMillis()
        if (st.expiresAt > 0 && now >= st.expiresAt - 2000) { release(app, Reason.EXPIRED); return }
        if (!hasAccess(app)) { endExternally(app, Loc.str(app, R.string.err_no_access)); return }
        val drift = verify(app, p)
        if (drift != null) {
            // The user (volume key, quick settings, another app) made the device LOUDER than this profile.
            // Do not claim to be "on" and do not overwrite what they did: just end the session.
            endExternally(app, drift)
            return
        }
        if (st.expiresAt > 0) Scheduler.schedule(app, st.expiresAt)
        onBtChanged(app)
        Notifier.showActive(app, p, Store.state(app))
        Widgets.updateAll(app)
    }

    /** Re-evaluates "is Bluetooth audio connected" and updates the media permission of the DND policy. */
    @Synchronized
    fun onBtChanged(ctx: Context, justDisconnected: String? = null) {
        val app = ctx.applicationContext
        val st = Store.state(app)
        val id = st.activeId ?: return
        val p = Store.profile(app, id) ?: return
        val bt = Bt.audioConnected(app, justDisconnected)
        if (bt == st.btMedia) return
        try {
            if (p.dnd == Dnd.PRIORITY && p.mediaOnBt && !p.media && hasAccess(app)) {
                nm(app).notificationPolicy = buildPolicy(p, bt, nm(app).notificationPolicy)
            }
            // saved only after it worked, so a failure is retried on the next event
            Store.saveState(app, st.copy(btMedia = bt))
        } catch (e: Exception) {
            Log.w(TAG, "onBtChanged", e)
            Store.setLastError(app, Loc.str(app, R.string.err_bt_policy, e.javaClass.simpleName + ": " + e.message))
        }
    }

    // ---- internals ------------------------------------------------------------------------

    private fun endExternally(app: Context, why: String) {
        Scheduler.cancel(app)
        Store.clearState(app)
        Notifier.cancelActive(app)
        Notifier.info(app, Loc.str(app, R.string.ended_external, why))
        Widgets.updateAll(app)
    }

    private fun ringerOf(r: Int): Int = when (r) {
        Ringer.NORMAL -> AudioManager.RINGER_MODE_NORMAL
        Ringer.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
        else -> AudioManager.RINGER_MODE_SILENT
    }

    private fun filterOf(d: Int): Int = when (d) {
        Dnd.OFF -> NotificationManager.INTERRUPTION_FILTER_ALL
        Dnd.PRIORITY -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
        Dnd.ALARMS -> NotificationManager.INTERRUPTION_FILTER_ALARMS
        else -> NotificationManager.INTERRUPTION_FILTER_NONE
    }

    /** Higher = quieter. Unknown counts as "not restricting". */
    private fun strictness(f: Int): Int = when (f) {
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> 1
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> 2
        NotificationManager.INTERRUPTION_FILTER_NONE -> 3
        else -> 0
    }

    private fun senderOf(s: Int): Int = when (s) {
        2 -> Policy.PRIORITY_SENDERS_CONTACTS
        3 -> Policy.PRIORITY_SENDERS_STARRED
        else -> Policy.PRIORITY_SENDERS_ANY
    }

    /**
     * The complete list of categories is built from scratch: everything not ticked in the profile is OFF
     * (reminders, events, repeat callers, conversations included), so nothing leaks through by default.
     */
    private fun buildPolicy(p: Profile, btConnected: Boolean, base: Policy): Policy {
        var c = 0
        if (p.calls > 0) c = c or Policy.PRIORITY_CATEGORY_CALLS
        if (p.messages > 0) c = c or Policy.PRIORITY_CATEGORY_MESSAGES
        if (p.media || (p.mediaOnBt && btConnected)) c = c or Policy.PRIORITY_CATEGORY_MEDIA
        if (p.alarms) c = c or Policy.PRIORITY_CATEGORY_ALARMS
        if (p.system) c = c or Policy.PRIORITY_CATEGORY_SYSTEM
        return Policy(
            c, senderOf(p.calls), senderOf(p.messages), base.suppressedVisualEffects,
            Policy.CONVERSATION_SENDERS_NONE,
        )
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun describe(e: Exception) = e.javaClass.simpleName + ": " + (e.message ?: "")

    /** @return null when applied and verified, otherwise the reason. */
    private fun applySystem(ctx: Context, p: Profile, bt: Boolean): String? {
        var last: String? = null
        for (i in 0 until RETRIES) {
            try { write(ctx, p, bt) } catch (e: Exception) { return describe(e) }
            sleep(if (i == 0) 150L else 400L)
            last = verify(ctx, p) ?: return null
        }
        return last
    }

    private fun write(ctx: Context, p: Profile, bt: Boolean) {
        val nm = nm(ctx)
        val am = am(ctx)
        val tr = ringerOf(p.ringer)
        if (tr != AudioManager.RINGER_MODE_NORMAL) am.ringerMode = tr // quiet first
        when (p.dnd) {
            Dnd.PRIORITY -> {
                nm.notificationPolicy = buildPolicy(p, bt, nm.notificationPolicy)
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            }
            else -> nm.setInterruptionFilter(filterOf(p.dnd))
        }
        // With "alarms only" / "block all" Android forces the ringer itself, so it is not touched there.
        if (p.dnd == Dnd.OFF || p.dnd == Dnd.PRIORITY) {
            if (am.ringerMode != tr) am.ringerMode = tr
        }
    }

    /** null = device is at least as quiet as the profile asks. */
    private fun verify(ctx: Context, p: Profile): String? {
        val f = nm(ctx).currentInterruptionFilter
        val wanted = filterOf(p.dnd)
        if (strictness(f) < strictness(wanted)) {
            return Loc.str(ctx, R.string.err_verify_dnd, Describe.dnd(ctx, p.dnd))
        }
        if (p.dnd == Dnd.OFF || p.dnd == Dnd.PRIORITY) {
            if (am(ctx).ringerMode > ringerOf(p.ringer)) {
                return Loc.str(ctx, R.string.err_verify_ringer, Describe.ringer(ctx, p.ringer))
            }
        }
        return null
    }

    private fun takeSnapshot(ctx: Context): Snapshot {
        val nm = nm(ctx)
        val am = am(ctx)
        val pol = nm.notificationPolicy
        val vols = HashMap<Int, Int>()
        STREAMS.forEach { s -> try { vols[s] = am.getStreamVolume(s) } catch (_: Exception) { } }
        return Snapshot(
            am.ringerMode, nm.currentInterruptionFilter, pol.priorityCategories,
            pol.priorityCallSenders, pol.priorityMessageSenders, pol.suppressedVisualEffects,
            pol.priorityConversationSenders, vols,
        )
    }

    private fun restoreSnapshot(ctx: Context, snap: Snapshot): String? {
        var last: String? = null
        for (i in 0 until RETRIES) {
            try { writeSnapshot(ctx, snap) } catch (e: Exception) { return describe(e) }
            sleep(if (i == 0) 150L else 400L)
            last = verifySnapshot(ctx, snap) ?: return null
        }
        return last
    }

    private fun writeSnapshot(ctx: Context, s: Snapshot) {
        val nm = nm(ctx)
        val am = am(ctx)
        nm.notificationPolicy = Policy(s.categories, s.callSenders, s.messageSenders, s.effects, s.convSenders)
        if (s.filter in 1..4) nm.setInterruptionFilter(s.filter)
        if (s.filter == NotificationManager.INTERRUPTION_FILTER_ALL ||
            s.filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY
        ) {
            if (am.ringerMode != s.ringer) am.ringerMode = s.ringer
        }
    }

    private fun verifySnapshot(ctx: Context, s: Snapshot): String? {
        val nm = nm(ctx)
        if (s.filter in 1..4 && nm.currentInterruptionFilter != s.filter) {
            return "DND ${nm.currentInterruptionFilter} != ${s.filter}"
        }
        if (s.filter == NotificationManager.INTERRUPTION_FILTER_ALL ||
            s.filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY
        ) {
            if (am(ctx).ringerMode != s.ringer) return "ringer ${am(ctx).ringerMode} != ${s.ringer}"
        }
        return null
    }

    /**
     * Optional volume restore. Ring / notification volumes are only touched when the restored ringer is
     * "sound": changing the ring volume while the ringer is vibrate/mute could switch the ringer on.
     */
    private fun restoreVolumes(ctx: Context, snap: Snapshot) {
        val mode = Store.volMode(ctx)
        if (mode == VolMode.OFF) return
        val am = am(ctx)
        STREAMS.forEachIndexed { idx, stream ->
            val ringStream = stream == AudioManager.STREAM_RING || stream == AudioManager.STREAM_NOTIFICATION
            if (ringStream && snap.ringer != AudioManager.RINGER_MODE_NORMAL) return@forEachIndexed
            val target = if (mode == VolMode.FIXED) {
                val min = am.getStreamMinVolume(stream)
                val max = am.getStreamMaxVolume(stream)
                (min + (max - min) * Store.volFixed(ctx, idx) / 100.0).roundToInt()
            } else {
                snap.volumes[stream] ?: return@forEachIndexed
            }
            try {
                am.setStreamVolume(
                    stream,
                    target.coerceIn(am.getStreamMinVolume(stream), am.getStreamMaxVolume(stream)),
                    0,
                )
            } catch (e: Exception) {
                Log.w(TAG, "volume $stream", e)
            }
        }
    }
}
