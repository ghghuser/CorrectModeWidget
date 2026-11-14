package io.github.ghghuser.correctmode

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import kotlin.math.roundToInt

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

fun Context.themeColor(attr: Int, fallback: Int): Int {
    val tv = TypedValue()
    return if (theme.resolveAttribute(attr, tv, true) && tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) tv.data else fallback
}

/** Left/right/bottom system bars only: the action bar already takes care of the top. */
fun View.applyBottomInsets() {
    setOnApplyWindowInsetsListener { v, ins ->
        val b = ins.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())
        v.setPadding(b.left, 0, b.right, b.bottom)
        ins
    }
}

object Ui {
    fun text(ctx: Context, s: CharSequence, sp: Float = 14f, bold: Boolean = false, secondary: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            if (secondary) alpha = 0.75f
        }

    fun button(ctx: Context, s: CharSequence, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = s
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    fun vbox(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun hbox(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun card(ctx: Context): LinearLayout = vbox(ctx).apply {
        val pad = ctx.dp(12)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(14).toFloat()
            setColor(ctx.themeColor(android.R.attr.colorBackgroundFloating, Color.WHITE))
            setStroke(ctx.dp(1), 0x33808080)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, ctx.dp(6), 0, ctx.dp(6)) }
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)

    fun toast(ctx: Context, s: String, long: Boolean = false) {
        Toast.makeText(ctx, s, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    fun choose(a: Activity, title: String, labels: List<String>, current: Int, cb: (Int) -> Unit) {
        AlertDialog.Builder(a)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { d, which -> cb(which); d.dismiss() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun openAccessSettings(a: Activity) {
        try {
            // NEW_TASK: the widget tap screen removes its own task when it finishes; the settings screen
            // must not be part of that task (it used to disappear right after opening).
            a.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast(a, e.javaClass.simpleName + ": " + e.message, true)
        }
    }

    fun durationLabel(ctx: Context, d: Int): String = when (d) {
        Dur.ASK -> Loc.str(ctx, R.string.duration_ask)
        Dur.NONE -> Loc.str(ctx, R.string.duration_none)
        30 -> Loc.str(ctx, R.string.duration_30)
        60 -> Loc.str(ctx, R.string.duration_60)
        120 -> Loc.str(ctx, R.string.duration_120)
        else -> Loc.str(ctx, R.string.duration_minutes, d)
    }
}

/** Turning a profile on / off from the widget, the main screen or the notification. */
object Flow {
    fun toggle(a: Activity, p: Profile, onDone: () -> Unit) {
        if (Store.state(a).activeId == p.id) {
            val r = Engine.release(a, Reason.USER)
            if (!r.ok || r.message != null) Ui.toast(a, r.message ?: "", true)
            onDone()
        } else start(a, p, onDone)
    }

    fun start(a: Activity, p: Profile, onDone: () -> Unit) {
        if (!Engine.hasAccess(a)) {
            Ui.toast(a, Loc.str(a, R.string.err_no_access), true)
            Ui.openAccessSettings(a)
            onDone()
            return
        }
        when (p.duration) {
            Dur.ASK -> ask(a, p, onDone)
            Dur.NONE -> go(a, p, 0L, onDone)
            else -> go(a, p, System.currentTimeMillis() + p.duration * 60_000L, onDone)
        }
    }

    private fun ask(a: Activity, p: Profile, onDone: () -> Unit) {
        val labels = listOf(
            Ui.durationLabel(a, 30), Ui.durationLabel(a, 60), Ui.durationLabel(a, 120),
            Loc.str(a, R.string.duration_time), Ui.durationLabel(a, Dur.NONE),
        )
        AlertDialog.Builder(a)
            .setTitle((if (p.imageFile == null) p.emoji + " " else "") + p.name)
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> go(a, p, System.currentTimeMillis() + 30 * 60_000L, onDone)
                    1 -> go(a, p, System.currentTimeMillis() + 60 * 60_000L, onDone)
                    2 -> go(a, p, System.currentTimeMillis() + 120 * 60_000L, onDone)
                    3 -> pickTime(a, p, onDone)
                    else -> go(a, p, 0L, onDone)
                }
            }
            .setOnCancelListener { onDone() }
            .show()
    }

    private fun pickTime(a: Activity, p: Profile, onDone: () -> Unit) {
        val now = Calendar.getInstance()
        var chosen = false
        val dlg = TimePickerDialog(
            a,
            { _, h, m ->
                chosen = true
                val c = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                if (c.timeInMillis <= System.currentTimeMillis() + 1000) c.add(Calendar.DAY_OF_YEAR, 1)
                go(a, p, c.timeInMillis, onDone)
            },
            (now.get(Calendar.HOUR_OF_DAY) + 1) % 24, 0,
            android.text.format.DateFormat.is24HourFormat(a),
        )
        dlg.setOnDismissListener { if (!chosen) onDone() }
        dlg.show()
    }

    private fun go(a: Activity, p: Profile, expiresAt: Long, onDone: () -> Unit) {
        val r = Engine.activate(a, p, expiresAt)
        if (!r.ok || r.message != null) Ui.toast(a, r.message ?: "", true)
        if (!r.ok && r.needAccess) Ui.openAccessSettings(a)
        onDone()
    }
}
