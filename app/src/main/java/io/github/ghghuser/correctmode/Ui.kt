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
                setOnClickListener { (a as? BackGuard)?.leave() ?: a.finish() }
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

    fun promptText(
        a: Activity, title: String, hint: String, initial: String, maxLen: Int,
        numeric: Boolean = false, onCancel: (() -> Unit)? = null, cb: (String) -> Unit,
    ) {
        val et = android.widget.EditText(a).apply {
            setText(initial); setSelection(initial.length); setSingleLine()
            if (numeric) inputType = android.text.InputType.TYPE_CLASS_DATETIME or android.text.InputType.TYPE_DATETIME_VARIATION_TIME
            filters = arrayOf(android.text.InputFilter.LengthFilter(maxLen))
            this.hint = hint
        }
        AlertDialog.Builder(a).setTitle(title).setView(et)
            .setPositiveButton(android.R.string.ok) { _, _ -> cb(et.text.toString()) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel?.invoke() }
            .setOnCancelListener { onCancel?.invoke() }
            .show()
    }

    fun text(ctx: Context, s: CharSequence, sp: Float = 14f, bold: Boolean = false, secondary: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            // dark theme: slightly brighter text than the system default, for readability on the blue
            if (ctx.isNight()) setTextColor(0xFFF3F6FB.toInt())
            if (secondary) alpha = if (ctx.isNight()) 0.82f else 0.75f
        }

    /**
     * "Apps that may interrupt" is a setting of the system, shared by all profiles (this app cannot change it).
     * The target screen is not verified on every device, so there are fallbacks.
     */
    fun openPrioritySettings(a: Activity) {
        for (action in listOf("android.settings.ZEN_MODE_PRIORITY_SETTINGS", android.provider.Settings.ACTION_SOUND_SETTINGS)) {
            try { a.startActivity(Intent(action)); return } catch (e: Exception) { /* try the next one */ }
        }
        toast(a, a.getString(R.string.apps_open_fail), true)
    }

    fun clock(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

    /** "⏰ 22:00–07:00 Mon Tue" or null when the profile has no timer. */
    fun timerLabel(ctx: Context, p: Profile): String? {
        if (!p.schedOn) return null
        val days = if (p.schedDays == 127) Loc.str(ctx, R.string.sched_everyday)
        else Loc.str(ctx, R.string.sched_day_names).split(',').filterIndexed { i, _ -> (p.schedDays shr i) and 1 != 0 }.joinToString(" ")
        return "⏰ " + clock(p.schedStart) + (if (p.schedEnd >= 0) "–" + clock(p.schedEnd) else "") + " " + days
    }

    fun button(ctx: Context, s: CharSequence, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = s
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    /**
     * "What sounds" at a glance: one small column per source, the function (📞) above and its state (📳) below,
     * with a thin gap (less than a half-width space) between the columns.
     */
    fun chipsView(ctx: Context, p: Profile, centered: Boolean = false): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            if (centered) gravity = Gravity.CENTER_HORIZONTAL
        }
        for (r in Describe.matrix(p)) {
            val col = vbox(ctx).apply { gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(text(ctx, r.emoji, 15f).apply { gravity = Gravity.CENTER })
            col.addView(text(ctx, r.cell.mark, 15f).apply { gravity = Gravity.CENTER })
            row.addView(col, lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ctx.dp(8) })
        }
        return row
    }

    /** Boxes side by side (emoji above the label); the chosen one is outlined. */
    fun boxChoice(ctx: Context, items: List<Pair<String, String>>, current: Int, onPick: (Int) -> Unit): LinearLayout {
        val row = hbox(ctx)
        items.forEachIndexed { i, (emoji, label) ->
            val on = i == current
            val box = vbox(ctx).apply {
                gravity = Gravity.CENTER
                setPadding(ctx.dp(4), ctx.dp(10), ctx.dp(4), ctx.dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = ctx.dp(12).toFloat()
                    if (on) { setColor(0x331F6FEB); setStroke(ctx.dp(2), 0xFF1F6FEB.toInt()) } else setStroke(ctx.dp(1), 0x55808080)
                }
                isClickable = true
                isSelected = on
                contentDescription = label
                stateDescription = if (on) Loc.str(ctx, R.string.selected_state) else null // read out by screen readers
                setOnClickListener { onPick(i) }
            }
            box.addView(text(ctx, emoji, 22f).apply { gravity = Gravity.CENTER })
            box.addView(text(ctx, label, 13f, bold = on).apply { gravity = Gravity.CENTER })
            row.addView(box, lp(0, weight = 1f).apply { if (i < items.lastIndex) marginEnd = ctx.dp(6) })
        }
        return row
    }

    /** "What makes a sound": the emojis sit in a fixed-width column, so every line starts at the same place. */
    fun matrixView(ctx: Context, p: Profile): LinearLayout {
        val box = vbox(ctx)
        box.addView(text(ctx, Loc.str(ctx, R.string.matrix_title), 13f))
        for (r in Describe.matrix(p)) {
            val row = hbox(ctx).apply { gravity = Gravity.TOP }
            row.addView(text(ctx, r.emoji, 13f).apply { gravity = Gravity.CENTER }, lp(ctx.dp(28)))
            row.addView(text(ctx, Loc.str(ctx, r.labelRes) + ": " + r.cell.mark + " " + Loc.str(ctx, r.cell.textRes), 13f), lp(0, weight = 1f))
            box.addView(row)
        }
        return box
    }

    fun vbox(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun hbox(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** Light card on the blue background, with a clearly visible edge; the active profile gets a blue edge. */
    fun card(ctx: Context, active: Boolean = false): LinearLayout = vbox(ctx).apply {
        val pad = ctx.dp(12)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(14).toFloat()
            setColor(ctx.getColor(R.color.card_bg))
            if (active) setStroke(ctx.dp(2), 0xFF1F6FEB.toInt()) else setStroke((ctx.resources.displayMetrics.density * 1.5f).toInt(), ctx.getColor(R.color.card_stroke))
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
/** A screen that asks before it is left with unsaved changes: the header arrow calls [leave]. */
interface BackGuard { fun leave() }

object Flow {
    /** Set by TapActivity while it handles a widget tap: a successful switch then gives the profile's vibration. */
    @Volatile var fromWidget = false

    fun toggle(a: Activity, p: Profile, onDone: () -> Unit) {
        if (Store.state(a).activeId == p.id) {
            val r = Engine.release(a, Reason.USER)
            if (!r.ok || r.message != null) Ui.toast(a, r.message ?: "", true)
            if (r.ok && fromWidget && p.tapVibe) Haptics.tap(a, false)
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

    /** "5", "90" = minutes, "1:30" = 1 h 30 min. null = not a usable number (1 minute .. 24 hours). */
    fun parseMinutes(text: String): Int? {
        val t = text.trim()
        val m = if (':' in t) {
            val parts = t.split(':')
            val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val mi = parts.getOrNull(1)?.toIntOrNull() ?: return null
            if (parts.size != 2) return null
            h * 60 + mi
        } else t.toIntOrNull() ?: return null
        return if (m in 1..1440) m else null
    }

    private fun ask(a: Activity, p: Profile, onDone: () -> Unit) {
        val box = Ui.vbox(a).apply { setPadding(a.dp(16), a.dp(8), a.dp(16), 0) }
        var dialog: AlertDialog? = null
        val remember = android.widget.CheckBox(a).apply { text = Loc.str(a, R.string.ask_dont_again) }

        // a fixed length (minutes, 0 = no limit): with the tick, it is kept for next time after one more confirmation
        fun pickDuration(minutes: Int) {
            dialog?.dismiss()
            if (!remember.isChecked) { go(a, p, if (minutes == 0) 0L else System.currentTimeMillis() + minutes * 60_000L, onDone); return }
            val label = if (minutes == 0) Ui.durationLabel(a, Dur.NONE) else Ui.durationLabel(a, minutes)
            AlertDialog.Builder(a)
                .setMessage(Loc.str(a, R.string.ask_dont_again_confirm, label))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    Store.upsert(a, p.copy(duration = if (minutes == 0) Dur.NONE else minutes))
                    Widgets.updateAll(a)
                    go(a, p, if (minutes == 0) 0L else System.currentTimeMillis() + minutes * 60_000L, onDone)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> ask(a, p, onDone) } // back to the question
                .setOnCancelListener { ask(a, p, onDone) }
                .show()
        }

        fun row(label: String, onClick: () -> Unit) {
            box.addView(Ui.button(a, label) { onClick() })
        }
        row(Ui.durationLabel(a, 30)) { pickDuration(30) }
        row(Ui.durationLabel(a, 60)) { pickDuration(60) }
        row(Ui.durationLabel(a, 120)) { pickDuration(120) }
        row(Loc.str(a, R.string.duration_free)) {
            dialog?.dismiss()
            Ui.promptText(a, Loc.str(a, R.string.duration_free_title), Loc.str(a, R.string.duration_free_hint), "", 6, numeric = true, onCancel = { onDone() }) { text ->
                val m = parseMinutes(text)
                if (m == null) { Ui.toast(a, Loc.str(a, R.string.duration_free_bad), true); onDone() }
                else if (remember.isChecked) pickDuration(m) else go(a, p, System.currentTimeMillis() + m * 60_000L, onDone)
            }
        }
        row(Loc.str(a, R.string.duration_time)) {
            if (remember.isChecked) Ui.toast(a, Loc.str(a, R.string.ask_time_not_saved), true)
            dialog?.dismiss(); pickTime(a, p, onDone)
        }
        row(Ui.durationLabel(a, Dur.NONE)) { pickDuration(0) }
        // bottom right: "do not ask again"
        box.addView(remember, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END; topMargin = a.dp(8)
        })
        dialog = AlertDialog.Builder(a)
            .setTitle(p.titlePrefix() + p.name)
            .setView(android.widget.ScrollView(a).apply { addView(box) })
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
        // The tick for "on" is given AFTER the quiet state is applied, like the one for "off" (which is reported to work).
        // Unverified guess for why the old tick before the change never came: the system cancels a vibration that is
        // running while the ringer mode / DND changes.
        val r = Engine.activate(a, p, expiresAt)
        if (r.ok && fromWidget && p.tapVibe) Haptics.tap(a, true)
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

fun Context.isNight(): Boolean =
    (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
