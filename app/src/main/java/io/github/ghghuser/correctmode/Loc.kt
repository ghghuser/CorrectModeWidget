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
        ctx, when (d) { Dnd.OFF -> R.string.dnd_off; Dnd.PRIORITY -> R.string.dnd_priority; Dnd.ALARMS -> R.string.dnd_alarms; else -> R.string.dnd_none },
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
        val tail = if (allowed.isEmpty()) Loc.str(ctx, R.string.allow_nothing) else allowed.joinToString(", ")
        return summary(ctx, p) + "\n" + Loc.str(ctx, R.string.allowed_prefix) + " " + tail
    }
}
