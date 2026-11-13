package io.github.ghghuser.correctmode

import android.app.NotificationManager
import android.app.NotificationManager.Policy
import android.content.Context
import android.media.AudioManager
import android.os.Build
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
 *  5. A failed switch goes back to the previous (quiet) profile, never to the loud original state.
 *
 * Android 15+ (this app targets API 36): an app can no longer change the GLOBAL Do Not Disturb. Its
 * setInterruptionFilter / setNotificationPolicy calls drive an implicit rule that belongs to this app, and
 * the system combines all active rules (most restrictive wins). Consequences handled here:
 *  - releasing = switching OUR rule off (setInterruptionFilter(ALL)); the DND the user had before is still
 *    theirs and is never re-applied by us (re-applying it would switch our own rule ON and leave it on);
 *  - DND that the user / another mode turned on cannot be turned off by a "DND: off" profile -> warning.
 */
object Engine {
    private const val TAG = "CorrectMode"
    private const val RETRIES = 3
    private const val MAX_RELEASE_TRIES = 2
    private val IMPLICIT_RULE = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
    private const val ALL_CATEGORIES = Policy.PRIORITY_CATEGORY_REMINDERS or Policy.PRIORITY_CATEGORY_EVENTS or
        Policy.PRIORITY_CATEGORY_MESSAGES or Policy.PRIORITY_CATEGORY_CALLS or
        Policy.PRIORITY_CATEGORY_REPEAT_CALLERS or Policy.PRIORITY_CATEGORY_ALARMS or
        Policy.PRIORITY_CATEGORY_MEDIA or Policy.PRIORITY_CATEGORY_SYSTEM or
        Policy.PRIORITY_CATEGORY_CONVERSATIONS
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
        var prev = State()
        var snapForRollback: Snapshot? = null
        var bt = false
        return try {
            prev = Store.state(app)
            val snap = prev.snapshot ?: takeSnapshot(app)
            snapForRollback = snap
            bt = Bt.audioConnected(app)
            val st = State(p.id, System.currentTimeMillis(), expiresAt, snap, bt)
            Store.saveState(app, st) // 1. save before touching anything
            val problem = applySystem(app, p, bt)
            if (problem != null) {
                Result(false, failAndRollBack(app, prev, snap, bt, problem))
            } else {
                Store.setLastError(app, null)
                Store.setReleaseFailures(app, 0)
                scheduleSafely(app, expiresAt)
                Notifier.showActive(app, p, st)
                Widgets.updateAll(app)
                // "DND: off" cannot switch off a DND that the user / another mode turned on (Android 15+).
                Result(true, if (p.dnd == Dnd.OFF) dndRemainsWarning(app, 0) else null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "activate", e)
            val snap = snapForRollback
            if (snap != null) Result(false, failAndRollBack(app, prev, snap, bt, describe(e)))
            else {
                val msg = Loc.str(app, R.string.err_apply_failed, describe(e))
                Store.setLastError(app, msg)
                Result(false, msg)
            }
        }
    }

    @Synchronized
    fun release(ctx: Context, reason: Reason): Result {
        val app = ctx.applicationContext
        val st = Store.state(app)
        Scheduler.cancel(app)
        val snap = st.snapshot
        var problem: String? = null
        var noAccess = false
        if (st.activeId != null && snap != null) {
            if (!hasAccess(app)) {
                noAccess = true
                problem = Loc.str(app, R.string.err_no_access)
            } else {
                problem = try {
                    val r = restoreSnapshot(app, snap)
                    // Volumes only AFTER the ringer / DND are back: while DND is still on, Android can refuse
                    // (or flip the ringer) when the ring volume is changed.
                    if (r == null) restoreVolumes(app, snap)
                    r
                } catch (e: Exception) {
                    describe(e)
                }
            }
        }
        val tries = if (problem != null) Store.releaseFailures(app) + 1 else 0
        if (problem != null && !noAccess && tries < MAX_RELEASE_TRIES) {
            // The restore failed although we have access: the phone is most likely STILL in the quiet state.
            // Stay "on" (that is the truth, and the saved original state is kept) so a retry is possible.
            // The time limit is dropped so a failing alarm cannot loop.
            Store.setReleaseFailures(app, tries)
            Store.saveState(app, st.copy(expiresAt = 0L))
            val msg = Loc.str(app, R.string.err_restore_failed, problem)
            Store.setLastError(app, msg)
            Notifier.info(app, msg)
            Store.profile(app, st.activeId)?.let { Notifier.showActive(app, it, Store.state(app)) }
            Widgets.updateAll(app)
            Log.w(TAG, "release problem (kept on, try $tries): $problem")
            return Result(false, msg)
        }
        Store.setReleaseFailures(app, 0)
        Store.clearState(app)
        Notifier.cancelActive(app)
        var warning: String? = null
        if (problem != null) {
            val msg = Loc.str(app, R.string.err_restore_failed, problem)
            Store.setLastError(app, msg)
            Notifier.info(app, msg)
            Log.w(TAG, "release problem: $problem")
        } else {
            Store.setLastError(app, null)
            if (snap != null && st.activeId != null) warning = dndRemainsWarning(app, strictness(snap.filter))
            if (reason == Reason.EXPIRED) {
                Notifier.info(app, Loc.str(app, R.string.released_text) + (warning?.let { "\n" + it } ?: ""))
            }
        }
        Widgets.updateAll(app)
        return Result(problem == null, problem ?: warning)
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
        val drift = verify(app, p, st.btMedia)
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
                var problem: String? = null
                for (i in 0 until 2) {
                    nm(app).notificationPolicy = buildPolicy(p, bt, nm(app).notificationPolicy)
                    problem = policyProblem(app, p, bt)
                    if (problem == null) break
                    sleep(300L)
                }
                if (problem != null) throw IllegalStateException(problem)
            }
            // saved only after it worked, so a failure is retried on the next event
            Store.saveState(app, st.copy(btMedia = bt))
        } catch (e: Exception) {
            Log.w(TAG, "onBtChanged", e)
            Store.setLastError(app, Loc.str(app, R.string.err_bt_policy, describe(e)))
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

    /**
     * Something went wrong while turning a profile on. Goes back to the QUIETEST sensible state: the previous
     * profile when this was a switch (a failed switch must never make the phone louder), otherwise the
     * saved original state.
     */
    private fun failAndRollBack(app: Context, prev: State, snap: Snapshot, bt: Boolean, problem: String): String {
        val msg = Loc.str(app, R.string.err_apply_failed, problem)
        Log.w(TAG, "activate failed: $problem")
        val prevProfile = Store.profile(app, prev.activeId)
        var backToPrevious = false
        if (prevProfile != null && prev.snapshot != null) {
            backToPrevious = try { applySystem(app, prevProfile, bt) == null } catch (e: Exception) { false }
        }
        if (backToPrevious && prevProfile != null) {
            Store.saveState(app, prev.copy(btMedia = bt))
            scheduleSafely(app, prev.expiresAt)
            Notifier.showActive(app, prevProfile, Store.state(app))
        } else {
            try { restoreSnapshot(app, snap) } catch (e: Exception) { Log.w(TAG, "rollback", e) }
            Store.clearState(app)
            Scheduler.cancel(app)
            Notifier.cancelActive(app)
        }
        Store.setLastError(app, msg)
        Widgets.updateAll(app)
        return msg
    }

    private fun scheduleSafely(app: Context, at: Long) {
        try {
            Scheduler.schedule(app, at)
        } catch (e: Exception) {
            Log.w(TAG, "schedule", e)
            Store.setLastError(app, Loc.str(app, R.string.err_schedule, describe(e)))
        }
    }

    /** DND that is still on although we switched our own rule off: set by the user or by another mode. */
    private fun dndRemainsWarning(app: Context, allowedStrictness: Int): String? {
        if (strictness(nm(app).currentInterruptionFilter) <= allowedStrictness) return null
        sleep(300L) // the system may need a moment to apply "our rule is off"; look once more before warning
        return if (strictness(nm(app).currentInterruptionFilter) > allowedStrictness) Loc.str(app, R.string.warn_dnd_remains) else null
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

    private fun categoriesOf(p: Profile, btConnected: Boolean): Int {
        var c = 0
        if (p.calls > 0) c = c or Policy.PRIORITY_CATEGORY_CALLS
        if (p.messages > 0) c = c or Policy.PRIORITY_CATEGORY_MESSAGES
        if (p.media || (p.mediaOnBt && btConnected)) c = c or Policy.PRIORITY_CATEGORY_MEDIA
        if (p.alarms) c = c or Policy.PRIORITY_CATEGORY_ALARMS
        if (p.system) c = c or Policy.PRIORITY_CATEGORY_SYSTEM
        return c
    }

    /**
     * The complete list of categories is built from scratch: everything not ticked in the profile is OFF
     * (reminders, events, repeat callers, conversations included), so nothing leaks through by default.
     */
    private fun buildPolicy(p: Profile, btConnected: Boolean, base: Policy): Policy =
        Policy(
            categoriesOf(p, btConnected), senderOf(p.calls), senderOf(p.messages), base.suppressedVisualEffects,
            Policy.CONVERSATION_SENDERS_NONE,
        )

    /**
     * Reads the policy back and compares it with what the profile asked for. Only "LOUDER than asked" counts
     * (something allowed that the profile did not allow, or a wider group of senders).
     * @return null = fine, otherwise a message naming what is still allowed.
     */
    private fun policyProblem(ctx: Context, p: Profile, bt: Boolean): String? {
        val pol = nm(ctx).notificationPolicy
        val want = categoriesOf(p, bt)
        val extra = pol.priorityCategories and ALL_CATEGORIES and want.inv()
        val names = ArrayList<String>()
        if (extra and Policy.PRIORITY_CATEGORY_CALLS != 0 ||
            (p.calls > 0 && pol.priorityCallSenders < senderOf(p.calls))
        ) names.add(Loc.str(ctx, R.string.allow_calls))
        if (extra and Policy.PRIORITY_CATEGORY_MESSAGES != 0 ||
            (p.messages > 0 && pol.priorityMessageSenders < senderOf(p.messages))
        ) names.add(Loc.str(ctx, R.string.allow_messages))
        if (extra and Policy.PRIORITY_CATEGORY_MEDIA != 0) names.add(Loc.str(ctx, R.string.allow_media))
        if (extra and Policy.PRIORITY_CATEGORY_ALARMS != 0) names.add(Loc.str(ctx, R.string.allow_alarms))
        if (extra and Policy.PRIORITY_CATEGORY_SYSTEM != 0) names.add(Loc.str(ctx, R.string.allow_system))
        val other = Policy.PRIORITY_CATEGORY_REMINDERS or Policy.PRIORITY_CATEGORY_EVENTS or
            Policy.PRIORITY_CATEGORY_REPEAT_CALLERS or Policy.PRIORITY_CATEGORY_CONVERSATIONS
        if (extra and other != 0) names.add(Loc.str(ctx, R.string.allow_other))
        return if (names.isEmpty()) null else Loc.str(ctx, R.string.err_verify_policy, names.joinToString(", "))
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
            last = verify(ctx, p, bt, checkPolicy = true) ?: return null
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
    private fun verify(ctx: Context, p: Profile, bt: Boolean = false, checkPolicy: Boolean = false): String? {
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
        if (checkPolicy && p.dnd == Dnd.PRIORITY) return policyProblem(ctx, p, bt)
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
            last = verifySnapshot(ctx, snap)
            // Android 15+: if the DND is stricter than before, our rule may not be off yet -> try again.
            // If it stays stricter it is the user's / another mode's DND: the caller shows a warning.
            if (last == null && !(IMPLICIT_RULE && strictness(nm(ctx).currentInterruptionFilter) > strictness(snap.filter))) return null
        }
        return last
    }

    private fun writeSnapshot(ctx: Context, s: Snapshot) {
        val nm = nm(ctx)
        val am = am(ctx)
        if (IMPLICIT_RULE) {
            // Only OUR rule is switched off. The DND the user had before is theirs and is still there;
            // writing it back with setInterruptionFilter would turn our own rule ON and leave it on.
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        } else {
            nm.notificationPolicy = Policy(s.categories, s.callSenders, s.messageSenders, s.effects, s.convSenders)
            if (s.filter in 1..4) nm.setInterruptionFilter(s.filter)
        }
        if (ringerFree(nm.currentInterruptionFilter) && am.ringerMode != s.ringer) am.ringerMode = s.ringer
    }

    /** With "alarms only" / "block all" Android forces the ringer itself, so it is left alone there. */
    private fun ringerFree(filter: Int): Boolean =
        filter == NotificationManager.INTERRUPTION_FILTER_ALL || filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY

    private fun verifySnapshot(ctx: Context, s: Snapshot): String? {
        val nm = nm(ctx)
        val f = nm.currentInterruptionFilter
        if (!IMPLICIT_RULE && s.filter in 1..4 && f != s.filter) return "DND $f != ${s.filter}"
        if (ringerFree(f) && am(ctx).ringerMode != s.ringer) return "ringer ${am(ctx).ringerMode} != ${s.ringer}"
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
