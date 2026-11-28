package io.github.ghghuser.correctmode

import android.app.Activity
import android.app.TimePickerDialog
import android.content.Context
import android.os.Bundle
import java.text.DateFormat
import java.util.Date

/**
 * Small dialog opened from the "on" notification: change the time until the profile is released, and the
 * time of day of the next automatic start. Not exported (only our own PendingIntent opens it).
 */
class TimeDialogActivity : Activity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Loc.wrap(newBase))

    /** Identity of the "on" session shown when the dialog opened; edits are refused if another one is on now. */
    private var sessionStart = 0L

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val st = Store.state(this)
        val p = st.activeId?.let { Store.profile(this, it) }
        if (p == null) { finish(); return }
        sessionStart = st.startedAt
        val root = Ui.vbox(this).apply { setPadding(dp(20), dp(12), dp(20), dp(12)) }
        root.addView(Ui.text(this, getString(R.string.time_dialog_title), 17f, bold = true))
        val now = System.currentTimeMillis()

        // 1) release: the same rows as everywhere else (TimeChooser). The time is counted from now.
        root.addView(Ui.text(this, p.titlePrefix() + p.name, 15f, bold = true))
        root.addView(Ui.text(this, if (st.expiresAt > 0) Ui.untilText(this, st.expiresAt) else getString(R.string.no_limit), 13f, secondary = true))
        root.addView(Ui.text(this, getString(R.string.duration_label), 16f, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) })
        TimeChooser.rows(this, root, TimeChooser.Kind.RELEASE, null, false, onCancel = {}, onRefresh = { recreate() }) { code ->
            when (code) {
                Dur.NONE -> setExpiry(0L)
                Dur.ASK_TIME -> Flow.pickTimeAt(this, {}) { setExpiry(it) }
                Dur.ASK_DATETIME -> Flow.pickDateTimeAt(this, {}) { setExpiry(it) }
                else -> setExpiry(System.currentTimeMillis() + code * 60_000L)
            }
        }

        // 2) the timer that turns the profile on by itself
        root.addView(Ui.text(this, getString(R.string.sched_title), 16f, bold = true).apply { setPadding(0, dp(16), 0, dp(4)) })
        val next = if (p.schedOn) Schedules.nextStart(p, now) else null
        root.addView(Ui.text(this, if (next != null) java.text.DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(next)) else getString(R.string.time_sched_off), 13f, secondary = true))
        root.addView(Ui.button(this, getString(R.string.time_set_start)) {
            TimePickerDialog(this, { _, h, mi ->
                p.schedStart = h * 60 + mi; p.schedOn = true
                if (p.schedDays == 0) p.schedDays = 0x7F
                Store.upsert(this, p)
                try { Schedules.reschedule(this) } catch (e: Exception) { }
                Ui.toast(this, getString(R.string.time_saved))
                finish()
            }, p.schedStart / 60, p.schedStart % 60, android.text.format.DateFormat.is24HourFormat(this)).show()
        })
        if (p.schedOn) root.addView(Ui.button(this, getString(R.string.time_sched_stop)) {
            p.schedOn = false; Store.upsert(this, p)
            try { Schedules.reschedule(this) } catch (e: Exception) { }
            finish()
        })
        root.addView(Ui.button(this, getString(android.R.string.cancel)) { finish() })
        setContentView(android.widget.ScrollView(this).apply { addView(root) })
    }

    private fun setExpiry(at: Long) {
        val st = Store.state(this)
        if (st.activeId == null || st.startedAt != sessionStart) {
            Ui.toast(this, getString(R.string.time_changed))
            finish(); return
        }
        try {
            Store.saveState(this, st.copy(expiresAt = at))
            Scheduler.schedule(applicationContext, at)
            Store.profile(this, st.activeId)?.let { Notifier.showActive(applicationContext, it, Store.state(this)) }
            Widgets.updateAll(this)
        } catch (e: Exception) {
            Ui.toast(this, Loc.str(this, R.string.err_schedule, e.message ?: e.javaClass.simpleName), true)
        }
        finish()
    }
}
