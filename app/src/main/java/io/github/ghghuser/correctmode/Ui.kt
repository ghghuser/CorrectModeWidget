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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import kotlin.math.roundToInt

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

fun Context.themeColor(attr: Int, fallback: Int): Int {
    val tv = TypedValue()
    return if (theme.resolveAttribute(attr, tv, true) && tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) tv.data else fallback
}

/**
 * Android 15+ draws apps edge-to-edge, so the content must keep clear of the status bar, the cut-out, the
 * navigation bar and the keyboard itself. (The old version only handled the bottom and relied on an action
 * bar, which is how the widget setup screen ended up with its first entries hidden under the title.)
 */
fun View.applySystemInsets() {
    setOnApplyWindowInsetsListener { v, ins ->
        val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
        v.setPadding(b.left, b.top, b.right, b.bottom)
        WindowInsets.CONSUMED
    }
}

object Ui {
    /**
     * A whole screen: own header (back arrow, title, action buttons on the right) above the scrolling body.
     * No system action bar is used, so nothing can overlap the content.
     */
    fun screen(a: Activity, title: String, back: Boolean, body: View, actions: List<View> = emptyList()): View {
        a.title = title
        val root = vbox(a)
        val header = hbox(a).apply { setPadding(a.dp(4), a.dp(4), a.dp(8), a.dp(4)) }
        if (back) {
            header.addView(text(a, "←", 24f).apply {
                setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8))
                contentDescription = Loc.str(a, R.string.back)
                setOnClickListener { a.finish() }
            })
        } else header.setPadding(a.dp(16), a.dp(4), a.dp(8), a.dp(4))
        header.addView(text(a, title, 20f, bold = true), lp(0, weight = 1f))
        actions.forEach { header.addView(it) }
        root.addView(header)
        root.addView(ScrollView(a).apply { addView(body); clipToPadding = false }, lp(h = 0, weight = 1f))
        root.applySystemInsets()
        return root
    }

    /** A big tappable symbol for the header (settings gear, save check). */
    fun headerButton(ctx: Context, symbol: String, description: String, onClick: () -> Unit): TextView =
        text(ctx, symbol, 26f, bold = true).apply {
            setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(6))
            contentDescription = description
            setOnClickListener { onClick() }
        }

    /** Two (or more) tabs. Long press = rename (when onLong is given). */
    fun tabBar(ctx: Context, names: List<String>, selected: Int, onSelect: (Int) -> Unit, onLong: ((Int) -> Unit)? = null): LinearLayout {
        val row = hbox(ctx)
        names.forEachIndexed { i, n ->
            val on = i == selected
            row.addView(text(ctx, n, 15f, bold = on).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(ctx.dp(8), ctx.dp(10), ctx.dp(8), ctx.dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = ctx.dp(12).toFloat()
                    if (on) { setColor(0x331F6FEB); setStroke(ctx.dp(2), 0xFF1F6FEB.toInt()) } else setStroke(ctx.dp(1), 0x55808080)
                }
                setOnClickListener { onSelect(i) }
                if (onLong != null) setOnLongClickListener { onLong(i); true }
            }, lp(0, weight = 1f).apply { setMargins(ctx.dp(3), ctx.dp(6), ctx.dp(3), ctx.dp(6)) })
        }
        return row
    }

    fun promptText(a: Activity, title: String, hint: String, initial: String, maxLen: Int, cb: (String) -> Unit) {
        val et = android.widget.EditText(a).apply {
            setText(initial); setSelection(initial.length); setSingleLine()
            filters = arrayOf(android.text.InputFilter.LengthFilter(maxLen))
            this.hint = hint
        }
        AlertDialog.Builder(a).setTitle(title).setView(et)
            .setPositiveButton(android.R.string.ok) { _, _ -> cb(et.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

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
            .setTitle(p.titlePrefix() + p.name)
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
        if (r.ok) nagPermissions(a, p)
        onDone()
    }

    /**
     * The profile works without these, but part of it does not (no "on" notification / no Bluetooth media
     * switching). Point to the permission screen - at most once a day, so it is not a nuisance.
     */
    private fun nagPermissions(a: Activity, p: Profile) {
        val needNotif = a.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        val needBt = p.dnd == Dnd.PRIORITY && p.mediaOnBt && !p.media && !Bt.hasConnectPermission(a)
        if (!needNotif && !needBt) return
        val prefs = a.getSharedPreferences("ui", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("permNag", 0L) < 24 * 3600_000L) return
        prefs.edit().putLong("permNag", now).apply()
        Ui.toast(a, Loc.str(a, R.string.perm_nag), true)
        try {
            a.startActivity(Intent(a, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { /* the toast was shown; nothing else to do */ }
    }
}
