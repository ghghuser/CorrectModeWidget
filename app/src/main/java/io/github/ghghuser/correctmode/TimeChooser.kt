package io.github.ghghuser.correctmode

import android.app.Activity
import android.app.AlertDialog
import android.widget.LinearLayout

/**
 * The one list of choices for "when to turn on" (START) and "when to release" (RELEASE).
 * The profile edit screen, the question at turn-on and the notification "time" screen all show these rows,
 * in this order:
 *   [ask every time: edit screen only], now / no limit, history (newest first), 30 min / 1 h / 2 h,
 *   type it in, time of day, date and time.
 * The result is a code: [Dur.NONE] (now / no limit), [Dur.ASK], [Dur.ASK_TIME], [Dur.ASK_DATETIME] or minutes (> 0).
 */
object TimeChooser {
    enum class Kind { START, RELEASE }

    private val FIXED = listOf(30, 60, 120)

    private fun recentKey(k: Kind) = if (k == Kind.START) Store.RECENT_START else Store.RECENT_RELEASE

    /** the saved-setting label (edit screen, "every time ...") */
    fun label(a: Activity, k: Kind, code: Int): String =
        if (k == Kind.START) Ui.startLabel(a, code) else Ui.durationLabel(a, code)

    fun title(a: Activity, k: Kind): String =
        Loc.str(a, if (k == Kind.START) R.string.start_label else R.string.duration_label)

    /**
     * Adds the rows to [box]. [current] = the saved value to mark (edit screen), [withAsk] = edit screen (adds
     * "ask every time" and uses the "every time" labels for the time rows). [onRefresh] redraws after a history delete.
     */
    fun rows(a: Activity, box: LinearLayout, k: Kind, current: Int?, withAsk: Boolean, onCancel: () -> Unit, onRefresh: () -> Unit, onPick: (Int) -> Unit) {
        fun mark(code: Int, text: String) = if (current == code) "✓ $text" else text
        fun row(code: Int, text: String, remember: Boolean = false, tap: (() -> Unit)? = null) {
            box.addView(Ui.button(a, mark(code, text)) {
                if (remember) Store.addRecent(a, recentKey(k), code)
                if (tap != null) tap() else onPick(code)
            })
        }
        if (withAsk) row(Dur.ASK, label(a, k, Dur.ASK))
        row(Dur.NONE, label(a, k, Dur.NONE))

        // history: typed-in lengths (the fixed ones are not stored), long press = delete
        val key = recentKey(k)
        val hist = Store.recent(a, key).filter { it !in FIXED }
        val extra = if (withAsk && current != null && current > 0 && current !in FIXED && current !in hist) listOf(current) else emptyList()
        for (m in extra + hist) {
            box.addView(Ui.button(a, mark(m, label(a, k, m))) { Store.addRecent(a, key, m); onPick(m) }.apply {
                if (m in hist) setOnLongClickListener {
                    AlertDialog.Builder(a)
                        .setMessage(Loc.str(a, R.string.recent_delete_confirm, label(a, k, m)))
                        .setPositiveButton(android.R.string.ok) { _, _ -> Store.removeRecent(a, key, m); onRefresh() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                }
            })
        }
        if (hist.isNotEmpty()) box.addView(Ui.text(a, Loc.str(a, R.string.recent_delete_hint), 11f, secondary = true))

        for (m in FIXED) row(m, label(a, k, m))
        box.addView(Ui.button(a, Loc.str(a, R.string.duration_free)) {
            Ui.promptText(a, Loc.str(a, if (k == Kind.START) R.string.start_free_title else R.string.duration_free_title),
                Loc.str(a, R.string.duration_free_hint), "", 6, numeric = true, onCancel = onCancel) { text ->
                val m = Flow.parseMinutes(text)
                if (m == null) { Ui.toast(a, Loc.str(a, R.string.duration_free_bad), true); onCancel() }
                else { Store.addRecent(a, key, m); onPick(m) }
            }
        })
        row(Dur.ASK_TIME, Loc.str(a, if (withAsk) (if (k == Kind.START) R.string.start_time_every else R.string.duration_time_every) else R.string.duration_time))
        row(Dur.ASK_DATETIME, Loc.str(a, if (withAsk) (if (k == Kind.START) R.string.start_datetime_every else R.string.duration_datetime) else R.string.duration_datetime_row))
    }

    /** The rows in a dialog. [footer] adds views under the rows (the "do not ask again" tick). */
    fun show(
        a: Activity, title: String, k: Kind, current: Int?, withAsk: Boolean,
        footer: ((LinearLayout) -> Unit)? = null, onCancel: () -> Unit = {}, onPick: (Int) -> Unit,
    ) {
        val box = Ui.vbox(a).apply { setPadding(a.dp(16), a.dp(8), a.dp(16), 0) }
        var dialog: AlertDialog? = null
        rows(a, box, k, current, withAsk, { dialog?.dismiss(); onCancel() }, { dialog?.dismiss(); show(a, title, k, current, withAsk, footer, onCancel, onPick) }) { code ->
            dialog?.dismiss(); onPick(code)
        }
        footer?.invoke(box)
        dialog = AlertDialog.Builder(a)
            .setTitle(title)
            .setView(android.widget.ScrollView(a).apply { addView(box) })
            .setOnCancelListener { onCancel() }
            .show()
    }
}
