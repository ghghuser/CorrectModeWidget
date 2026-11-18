package io.github.ghghuser.correctmode

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar

/**
 * Timers that turn a profile ON by themselves: start time, optional end time, weekdays. One alarm is kept
 * for the nearest start of all profiles. When it fires (or after a reboot / app update) [runDue] looks at
 * what should have started and has not been handled yet, so a late (inexact) alarm or a phone that was off
 * is caught up while the start window is still open (until the end time, or 10 minutes if there is none).
 * Turning on goes through Engine.activate, i.e. the same quiet-first, verified path as a manual tap.
 */
object Schedules {
    const val ACTION = "io.github.ghghuser.correctmode.SCHEDULE"
    private const val TAG = "CorrectModeSched"
    private const val NO_END_WINDOW_MS = 10 * 60_000L

    private fun pi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 6, Intent(ctx, ExpireReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun dayStart(base: Long, offsetDays: Int): Calendar = Calendar.getInstance().apply {
        timeInMillis = base
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, offsetDays)
    }

    private fun at(day: Calendar, minutes: Int): Long = (day.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, minutes / 60); set(Calendar.MINUTE, minutes % 60)
    }.timeInMillis

    private fun allowed(p: Profile, day: Calendar): Boolean {
        val idx = (day.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Monday = 0 ... Sunday = 6
        return (p.schedDays shr idx) and 1 != 0
    }

    /** The latest start that is <= now (looking back one week). */
    fun previousStart(p: Profile, now: Long): Long? {
        for (off in 0 downTo -7) {
            val d = dayStart(now, off)
            val t = at(d, p.schedStart)
            if (allowed(p, d) && t <= now) return t
        }
        return null
    }

    fun nextStart(p: Profile, now: Long): Long? {
        for (off in 0..7) {
            val d = dayStart(now, off)
            val t = at(d, p.schedStart)
            if (allowed(p, d) && t > now) return t
        }
        return null
    }

    /** The end belonging to a start: the next time the clock shows the end time (null = no end time). */
    fun endAfter(p: Profile, start: Long): Long? {
        if (p.schedEnd < 0) return null
        val d0 = dayStart(start, 0)
        var t = at(d0, p.schedEnd)
        if (t <= start) t = at(dayStart(start, 1), p.schedEnd)
        return t
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sched", Context.MODE_PRIVATE)

    /** Starts what is due. Safe to call at any time (alarm, boot, update). */
    @Synchronized
    fun runDue(ctx: Context) {
        val app = ctx.applicationContext
        try {
            val now = System.currentTimeMillis()
            val due = ArrayList<Triple<Profile, Long, Long?>>()
            val sp = prefs(app)
            val edit = sp.edit()
            for (p in Store.profiles(app)) {
                if (!p.schedOn) continue
                val prev = previousStart(p, now) ?: continue
                if (prev <= sp.getLong("last_" + p.id, 0L)) continue
                edit.putLong("last_" + p.id, prev) // handled now, whatever happens next
                val end = endAfter(p, prev)
                if (now >= (end ?: (prev + NO_END_WINDOW_MS))) continue // window closed: too late to start
                due.add(Triple(p, prev, end))
            }
            edit.commit()
            // if several are due, the most recent start wins
            val pick = due.maxByOrNull { it.second }
            if (pick != null) {
                val (p, _, end) = pick
                val active = Store.state(app).activeId?.let { Store.profile(app, it) }
                if (active != null && active.id != p.id && Engine.quietRank(p) < Engine.quietRank(active)) {
                    // never louder by itself: the profile that is on is quieter than the timer wants
                    Notifier.info(app, Loc.str(app, R.string.sched_skipped, p.name, active.name))
                } else if (active?.id != p.id) {
                    val expires = end ?: if (p.duration > 0) now + p.duration * 60_000L else 0L
                    val r = Engine.activate(app, p, expires)
                    if (!r.ok) Notifier.info(app, r.message ?: Loc.str(app, R.string.err_apply_failed, ""))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "runDue", e)
            Store.setLastError(app, Loc.str(app, R.string.err_timer, e.javaClass.simpleName + ": " + (e.message ?: "")))
        } finally {
            reschedule(app)
        }
    }

    /** One alarm for the nearest start of all enabled timers. Call after any change to profiles. */
    fun reschedule(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        try {
            val now = System.currentTimeMillis()
            val next = Store.profiles(app).filter { it.schedOn }.mapNotNull { nextStart(it, now) }.minOrNull()
            if (next == null) { am.cancel(pi(app)); return }
            try {
                if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi(app))
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi(app))
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi(app))
            }
        } catch (e: Exception) {
            Log.w(TAG, "reschedule", e)
        }
    }
}
