package io.github.ghghuser.correctmode

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** In-app language: "system" (default), "ja" or "en". Used for activities, widgets and notifications. */
object Loc {
    fun wrap(base: Context): Context {
        val lang = Store.language(base)
        if (lang == "system") return base
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(Locale.forLanguageTag(lang))
        return base.createConfigurationContext(cfg)
    }

    fun str(ctx: Context, id: Int, vararg args: Any): String = wrap(ctx).getString(id, *args)
}

/** Short, unambiguous wording shared by the UI, the notification and the widget descriptions. */
object Describe {
    fun ringer(ctx: Context, r: Int): String = Loc.str(
        ctx, when (r) { Ringer.NORMAL -> R.string.ringer_normal; Ringer.VIBRATE -> R.string.ringer_vibrate; else -> R.string.ringer_mute },
    )

    fun dnd(ctx: Context, d: Int): String = Loc.str(
        ctx, when (d) { Dnd.OFF -> R.string.dnd_off; Dnd.PRIORITY -> R.string.dnd_priority; else -> R.string.dnd_none },
    )

    fun senders(ctx: Context, s: Int): String = Loc.str(
        ctx, when (s) { 0 -> R.string.senders_none; 1 -> R.string.senders_any; 2 -> R.string.senders_contacts; else -> R.string.senders_starred },
    )

    /** e.g. "Ringer: Vibrate / DND: Priority only" */
    fun summary(ctx: Context, p: Profile): String {
        val sb = StringBuilder()
        sb.append(Loc.str(ctx, R.string.ringer_label)).append(": ").append(ringer(ctx, p.ringer))
        sb.append(" / ").append(Loc.str(ctx, R.string.dnd_label)).append(": ").append(dnd(ctx, p.dnd))
        return sb.toString()
    }

    /** Longer description of what is allowed through while this profile is on. */
    fun detail(ctx: Context, p: Profile): String {
        if (p.dnd != Dnd.PRIORITY) return summary(ctx, p)
        val allowed = ArrayList<String>()
        if (p.calls > 0) allowed.add(Loc.str(ctx, R.string.allow_calls) + "(" + senders(ctx, p.calls) + ")")
        if (p.messages > 0) allowed.add(Loc.str(ctx, R.string.allow_messages) + "(" + senders(ctx, p.messages) + ")")
        if (p.media) allowed.add(Loc.str(ctx, R.string.allow_media))
        else if (p.mediaOnBt) allowed.add(Loc.str(ctx, R.string.allow_media_bt_short))
        if (p.alarms) allowed.add(Loc.str(ctx, R.string.allow_alarms))
        if (p.system) allowed.add(Loc.str(ctx, R.string.allow_system))
        if (p.reminders) allowed.add(Loc.str(ctx, R.string.allow_reminders))
        if (p.events) allowed.add(Loc.str(ctx, R.string.allow_events))
        val tail = if (allowed.isEmpty()) Loc.str(ctx, R.string.allow_nothing) else allowed.joinToString(", ")
        return summary(ctx, p) + "\n" + Loc.str(ctx, R.string.allowed_prefix) + " " + tail
    }

    // ---- "what makes a sound?" at a glance ------------------------------------------------

    enum class Cell(val mark: String, val textRes: Int) {
        ON("✅", R.string.val_on), VIB("📳", R.string.val_vib), NONE("✖", R.string.val_none),
        BT("🎧", R.string.val_bt),
    }

    class Row(val emoji: String, val labelRes: Int, val cell: Cell)

    /**
     * Which of the usual sources can make a sound / vibrate while the profile is ON.
     * Calls and messages follow the ringer unless DND blocks them; other notifications are blocked by every
     * DND mode; media / alarms / touch sounds are decided by the DND mode and its "allow" ticks.
     */
    fun matrix(p: Profile): List<Row> {
        val byRinger = when (p.ringer) { Ringer.NORMAL -> Cell.ON; Ringer.VIBRATE -> Cell.VIB; else -> Cell.NONE }
        val off = p.dnd == Dnd.OFF
        val pri = p.dnd == Dnd.PRIORITY
        val calls = if (off || (pri && p.calls > 0)) byRinger else Cell.NONE
        val msgs = if (off || (pri && p.messages > 0)) byRinger else Cell.NONE
        val notif = if (off) byRinger else Cell.NONE
        val media = when {
            off -> Cell.ON
            pri -> if (p.media) Cell.ON else if (p.mediaOnBt) Cell.BT else Cell.NONE
            else -> Cell.NONE
        }
        val alarm = when {
            off -> Cell.ON
            pri -> if (p.alarms) Cell.ON else Cell.NONE
            else -> Cell.NONE
        }
        val system = when {
            off -> if (p.ringer == Ringer.NORMAL) Cell.ON else Cell.NONE
            pri -> if (p.system) Cell.ON else Cell.NONE
            else -> Cell.NONE
        }
        return listOf(
            Row("📞", R.string.label_calls, calls), Row("💬", R.string.label_messages, msgs),
            Row("📣", R.string.label_notif, notif), Row("🎵", R.string.label_media, media),
            Row("⏰", R.string.label_alarm, alarm), Row("🔧", R.string.label_system, system),
        )
    }

    /** One compact line for the profile card, e.g. "📞📳 💬📳 📣✖ 🎵✖ ⏰✖ 🔧✖". */
    fun chips(p: Profile): String = matrix(p).joinToString("  ") { it.emoji + it.cell.mark }

    /** Full lines for the editor: "📞 Phone calls: 📳 Vibrate only". */
    fun matrixLines(ctx: Context, p: Profile): String = matrix(p).joinToString("\n") {
        it.emoji + " " + Loc.str(ctx, it.labelRes) + ": " + it.cell.mark + " " + Loc.str(ctx, it.cell.textRes)
    }
}
