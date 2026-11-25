package io.github.ghghuser.correctmode

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * After a switch (turn on / off / change to another profile) the system applies the ringer mode and the DND
 * asynchronously. Instead of a fixed waiting time this waits until they have stopped changing, then gives the tick
 * and ends the greyed "busy" look (during which taps are ignored): as soon as the state has been the same for
 * [STABLE_POLLS] checks in a row, at the latest after [MAX_MS].
 */
object Settle {
    enum class Tick { NONE, ON, OFF }

    const val POLL_MS = 100L
    const val STABLE_POLLS = 3
    const val MAX_MS = 1500L

    /** Called on the main thread when a settle has ended, so an open screen can redraw. */
    @Volatile var listener: (() -> Unit)? = null

    /** Number of the newest [begin]: an older one (e.g. after a duration dialog that stayed open) stops without ending anything. */
    private var generation = 0

    /** true from [begin] until the state has settled */
    @Volatile var pending = false
        private set

    fun begin(ctx: Context, tick: Tick) {
        val app = ctx.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val start = SystemClock.elapsedRealtime()
        pending = true
        val mine = ++generation
        // grey and "taps ignored" until settled; this is the longest it can last (plus a margin)
        Store.setBusyUntil(app, System.currentTimeMillis() + MAX_MS + 500L)
        Scheduler.scheduleRedraw(app, MAX_MS + 1500L) // safety net: the widgets cannot stay grey if the process dies meanwhile
        var last = signature(app)
        var same = 0
        val step = object : Runnable {
            override fun run() {
                if (mine != generation) return // a newer switch took over: it ends the busy look
                val now = signature(app)
                same = if (now == last) same + 1 else 0
                last = now
                if (same >= STABLE_POLLS || SystemClock.elapsedRealtime() - start >= MAX_MS) end(app, tick)
                else handler.postDelayed(this, POLL_MS)
            }
        }
        handler.postDelayed(step, POLL_MS)
    }

    /** A switch that did not start a settle (failed, cancelled): end the busy look right away. */
    fun endIfIdle(ctx: Context) {
        if (pending) return
        val app = ctx.applicationContext
        try {
            Store.setBusyUntil(app, 0L)
            Widgets.updateAll(app)
        } catch (e: Exception) {
            // a redraw that fails must not break the tap; the redraw alarm repairs the widgets
        }
    }

    private fun end(app: Context, tick: Tick) {
        pending = false
        try {
            if (tick != Tick.NONE) Haptics.vibrate(app, tick == Tick.ON)
            Store.setBusyUntil(app, 0L)
            Widgets.updateAll(app)
            listener?.invoke()
        } catch (e: Exception) {
            // see endIfIdle
        }
    }

    /** What the device looks like now (ringer mode, DND filter, DND policy). Equal twice = nothing changed in between. */
    private fun signature(ctx: Context): List<Int> = try {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val am = ctx.getSystemService(AudioManager::class.java)
        val pol = nm.notificationPolicy
        listOf(am.ringerMode, nm.currentInterruptionFilter, pol.priorityCategories, pol.priorityCallSenders,
            pol.priorityMessageSenders, pol.suppressedVisualEffects)
    } catch (e: Exception) {
        emptyList()
    }
}
