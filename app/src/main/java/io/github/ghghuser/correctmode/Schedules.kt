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

    /** A one-shot planned start ("30 minutes later", from the tap): [at] = start, [expires] = absolute release time (0 = none). */
    data class Once(val id: String, val at: Long, val expires: Long)

    private const val ONCE_WINDOW_MS = 30 * 60_000L

    fun onceList(ctx: Context): List<Once> = prefs(ctx).all.entries.mapNotNull { e ->
        if (!e.key.startsWith("once_")) return@mapNotNull null
        val v = (e.value as? String)?.split(",") ?: return@mapNotNull null
        val at = v.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        val ex = v.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
        Once(e.key.removePrefix("once_"), at, ex)
    }.sortedBy { it.at }

    fun onceFor(ctx: Context, id: String): Once? = onceList(ctx).firstOrNull { it.id == id }

    fun planOnce(ctx: Context, id: String, at: Long, expires: Long) {
        prefs(ctx).edit().putString("once_$id", "$at,$expires").commit()
        reschedule(ctx)
    }

    fun cancelOnce(ctx: Context, id: String) {
        prefs(ctx).edit().remove("once_$id").commit()
        reschedule(ctx)
    }

    /** Starts the planned turn-ons whose time has come. One that is too late (phone was off) is dropped with a note. */
    private fun runOnceDue(app: Context, now: Long) {
        for (o in onceList(app)) {
            if (o.at > now) continue
            prefs(app).edit().remove("once_" + o.id).commit() // handled now, whatever happens next
            val p = Store.profile(app, o.id) ?: continue
            if (now - o.at > ONCE_WINDOW_MS) {
                Notifier.info(app, Loc.str(app, R.string.start_missed, p.name))
            } else if (o.expires > 0 && o.expires <= now) {
                continue // its release time has already passed
            } else {
                startChecked(app, p, o.expires)
            }
        }
    }

    /** Turns [p] on unless that would make the phone louder than the profile that is on. */
    private fun startChecked(app: Context, p: Profile, expires: Long) {
        val active = Store.state(app).activeId?.let { Store.profile(app, it) }
        if (active != null && active.id != p.id && Engine.wouldBeLouder(p, active)) {
            // never louder by itself: the profile that is on is quieter than the timer wants
            Notifier.info(app, Loc.str(app, R.string.sched_skipped, p.name, active.name))
        } else if (active?.id != p.id) {
            val r = Engine.activate(app, p, expires)
            if (!r.ok) Notifier.info(app, r.message ?: Loc.str(app, R.string.err_apply_failed, ""))
        }
    }

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
                startChecked(app, p, end ?: if (p.duration > 0) now + p.duration * 60_000L else 0L)
            }
            runOnceDue(app, now)
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
            val daily = Store.profiles(app).filter { it.schedOn }.mapNotNull { nextStart(it, now) }.minOrNull()
            val once = onceList(app).minOfOrNull { maxOf(it.at, now + 1000L) } // an overdue one fires at once
            val next = listOfNotNull(daily, once).minOrNull()
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
