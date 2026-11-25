package io.github.ghghuser.correctmode

import org.json.JSONArray
import org.json.JSONObject

/** Ringer = what the volume key / "vibrate / mute" setting does. NOT the same thing as DND. */
object Ringer {
    const val NORMAL = 0
    const val VIBRATE = 1
    const val MUTE = 2
}

/** DND = Do Not Disturb (interruption filter). */
object Dnd {
    const val OFF = 0
    const val PRIORITY = 1
    const val NONE = 3 // 2 is not used (it was a separate "alarms only" level; that is now a notification filter with alarms and media)
}

object Dur {
    const val ASK = -1
    const val NONE = 0
    /** at turn-on, ask for the date and time of the auto release (not saved) */
    const val ASK_DATETIME = -2
    /** at turn-on, ask for the time of day of the auto release (today, or tomorrow if it has passed; not saved) */
    const val ASK_TIME = -3
}

object Shape {
    const val PILL = 0     // circle when the widget is square, pill when it is not
    const val ROUND = 1
    const val ROUND_L = 2
    const val SQUARE = 3
}

object IconSize {
    const val SMALL = 0
    const val MEDIUM = 1
    const val LARGE = 2
}

/**
 * The ready-made profiles (templates), also the names the wizard gives to the profiles it creates.
 * Ringer (3) x DND: off / notification filter (details are not part of the name) / block all, and for the filter and
 * block-all a Bluetooth part: none / all Bluetooth audio devices / headsets only.
 * With "block all" Android overrides the ringer, so the ringer is not part of those names.
 * The last one, "alarms only", is a filter with alarms + media allowed (not made by the wizard).
 */
object Patterns {
    const val NO_BT = -1

    /** [bt]: NO_BT, BtMode.ALL_AUDIO or BtMode.EARPHONE_ONLY. [btOnly]: nothing but media while Bluetooth audio is connected. */
    class Pat(val ringer: Int, val dnd: Int, val bt: Int, val nameRes: Int, val icon: String, val btOnly: Boolean = false)

    val LIST: List<Pat> = listOf(
        Pat(Ringer.NORMAL, Dnd.OFF, NO_BT, R.string.pat_1, "bell"),
        Pat(Ringer.VIBRATE, Dnd.OFF, NO_BT, R.string.pat_2, "vibrate"),
        Pat(Ringer.MUTE, Dnd.OFF, NO_BT, R.string.pat_3, "volume_off"),
        Pat(Ringer.NORMAL, Dnd.PRIORITY, NO_BT, R.string.pat_4, "moon"),
        Pat(Ringer.NORMAL, Dnd.PRIORITY, BtMode.ALL_AUDIO, R.string.pat_5, "headphones"),
        Pat(Ringer.NORMAL, Dnd.PRIORITY, BtMode.EARPHONE_ONLY, R.string.pat_6, "music"),
        Pat(Ringer.VIBRATE, Dnd.PRIORITY, NO_BT, R.string.pat_7, "bell_off"),
        Pat(Ringer.VIBRATE, Dnd.PRIORITY, BtMode.ALL_AUDIO, R.string.pat_8, "headphones"),
        Pat(Ringer.VIBRATE, Dnd.PRIORITY, BtMode.EARPHONE_ONLY, R.string.pat_9, "music"),
        Pat(Ringer.MUTE, Dnd.PRIORITY, NO_BT, R.string.pat_10, "dnd"),
        Pat(Ringer.MUTE, Dnd.PRIORITY, BtMode.ALL_AUDIO, R.string.pat_11, "headphones"),
        Pat(Ringer.MUTE, Dnd.PRIORITY, BtMode.EARPHONE_ONLY, R.string.pat_12, "music"),
        Pat(Ringer.MUTE, Dnd.NONE, NO_BT, R.string.pat_13, "flight"),
        Pat(Ringer.MUTE, Dnd.PRIORITY, BtMode.ALL_AUDIO, R.string.pat_14, "car", btOnly = true),
        Pat(Ringer.MUTE, Dnd.PRIORITY, BtMode.EARPHONE_ONLY, R.string.pat_15, "headphones", btOnly = true),
        Pat(Ringer.VIBRATE, Dnd.PRIORITY, NO_BT, R.string.pat_16, "bed"),
    )
    const val ALARMS_ONLY = 15

    val COUNT: Int get() = LIST.size
    val NAMES: List<Int> = LIST.map { it.nameRes }

    /** The pattern of a wizard result (never the "alarms only" one); -1 = none fits. */
    fun find(ringer: Int, dnd: Int, bt: Int, btOnly: Boolean): Int =
        LIST.indices.firstOrNull { i ->
            val t = LIST[i]
            i != ALARMS_ONLY && t.ringer == ringer && t.dnd == dnd && t.bt == bt && t.btOnly == btOnly
        } ?: -1
}

/** Stored colour value 0 = "no background" (a real colour always has the alpha bits set). */
object ColorMark {
    const val TRANSPARENT = 0
}

object BtMode {
    const val ALL_AUDIO = 0
    const val EARPHONE_ONLY = 1
}

object VolMode {
    const val OFF = 0
    const val RESTORE_SAVED = 1
    const val FIXED = 2
}

/** One combination of Ringer + DND (+ look of its widget button). */
data class Profile(
    val id: String,
    var name: String,
    var ringer: Int = Ringer.VIBRATE,
    var dnd: Int = Dnd.PRIORITY,
    /** 0 = nobody, 1 = anyone, 2 = contacts, 3 = starred */
    var calls: Int = 1,
    var messages: Int = 1,
    var media: Boolean = false,
    var alarms: Boolean = false,
    var system: Boolean = false,
    var reminders: Boolean = false,
    var events: Boolean = false,
    /** index in Patterns for the ready-made profiles of the right tab (they can be reset); -1 = a normal profile */
    var preset: Int = -1,
    var mediaOnBt: Boolean = true,
    /** which Bluetooth devices count for [mediaOnBt]: BtMode.ALL_AUDIO or BtMode.EARPHONE_ONLY */
    var btScope: Int = BtMode.ALL_AUDIO,
    /** Dur.ASK, Dur.NONE, Dur.ASK_TIME, Dur.ASK_DATETIME or minutes */
    var duration: Int = Dur.ASK,
    var emoji: String = "🔕",
    var imageFile: String? = null,
    /** own picture for the "on" look (only used when onSep is set) */
    var imageFileOn: String? = null,
    var bgOn: Int? = null,
    var bgOff: Int? = null,
    var fgOn: Int? = null,
    var fgOff: Int? = null,
    var shape: Int = Shape.ROUND_L,
    var iconSize: Int = IconSize.MEDIUM,
    var showLabel: Boolean = false,
    /** opacity of the background colour in percent (0 = invisible .. 100) */
    var bgOffA: Int = 100,
    var bgOnA: Int = 100,
    /** keep circle / square perfectly round / square, whatever the grid cell of the launcher is */
    var keepRatio: Boolean = true,
    /** space between the widget edge and the button, in dp (left, top, right, bottom) */
    var mL: Int = 2,
    var mT: Int = 2,
    var mR: Int = 2,
    var mB: Int = 2,
    /** a different icon while this profile is on (built-in icon or emoji) */
    var onSep: Boolean = false,
    var iconKeyOn: String? = null,
    var emojiOn: String = "🔔",
    /** 0 = left tab, 1 = right tab (the user can name both) */
    var tab: Int = 0,
    /** key of a built-in flat icon (see FlatIcons); null = emoji */
    var iconKey: String? = null,
    /** what to do with the volumes when this profile is released (VolMode) */
    var volMode: Int = VolMode.OFF,
    /** percent 0..100 for ring, notification, media, alarm (used with VolMode.FIXED) */
    var volFixed: List<Int> = listOf(50, 50, 50, 50),
    /** short vibration when this profile is switched from a widget */
    var tapVibe: Boolean = true,
    /** on release: false = put back what was there before; true = everything off (ringer normal, our DND off) */
    var releaseAll: Boolean = false,
    /** timer that turns this profile ON by itself (see Schedules) */
    var schedOn: Boolean = false,
    /** minutes since midnight */
    var schedStart: Int = 22 * 60,
    /** minutes since midnight; -1 = no end time (then the profile's own auto release applies) */
    var schedEnd: Int = -1,
    /** bit 0 = Monday ... bit 6 = Sunday */
    var schedDays: Int = 127,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name)
        put("ringer", ringer); put("dnd", dnd)
        put("calls", calls); put("messages", messages)
        put("media", media); put("alarms", alarms); put("system", system); put("rem", reminders); put("evt", events)
        put("mediaOnBt", mediaOnBt); put("bts", btScope); put("duration", duration)
        put("emoji", emoji)
        if (imageFile != null) put("imageFile", imageFile)
        if (imageFileOn != null) put("imageFileOn", imageFileOn)
        bgOn?.let { put("bgOn", it) }; bgOff?.let { put("bgOff", it) }
        fgOn?.let { put("fgOn", it) }; fgOff?.let { put("fgOff", it) }
        put("shape", shape); put("iconSize", iconSize); put("showLabel", showLabel)
        put("boa", bgOffA); put("bna", bgOnA); put("kr", keepRatio)
        put("ml", mL); put("mt", mT); put("mr", mR); put("mb", mB)
        put("osep", onSep); iconKeyOn?.let { put("ikon", it) }; put("emon", emojiOn)
        put("tab", tab)
        if (preset >= 0) put("pre", preset)
        iconKey?.let { put("icon", it) }
        put("vm", volMode); put("vf", JSONArray(volFixed))
        put("vib", tapVibe); put("ra", releaseAll)
        put("son", schedOn); put("ss", schedStart); put("se", schedEnd); put("sd", schedDays)
    }

    companion object {
        fun fromJson(o: JSONObject): Profile = Profile(
            id = o.getString("id"),
            name = o.optString("name", ""),
            ringer = o.optInt("ringer", Ringer.VIBRATE).coerceIn(0, 2),
            dnd = o.optInt("dnd", Dnd.PRIORITY).also { require(it != 2) { "unsupported dnd level" } }.coerceIn(0, 3), // 2 = the removed "alarms only" level: such a profile is skipped, not guessed
            calls = o.optInt("calls", 1).coerceIn(0, 3),
            messages = o.optInt("messages", 1).coerceIn(0, 3),
            media = o.optBoolean("media", false),
            alarms = o.optBoolean("alarms", false),
            system = o.optBoolean("system", false),
            reminders = o.optBoolean("rem", false),
            events = o.optBoolean("evt", false),
            preset = o.optInt("pre", -1).coerceIn(-1, Patterns.COUNT - 1),
            mediaOnBt = o.optBoolean("mediaOnBt", true),
            btScope = o.optInt("bts", BtMode.ALL_AUDIO).coerceIn(BtMode.ALL_AUDIO, BtMode.EARPHONE_ONLY),
            duration = o.optInt("duration", Dur.ASK),
            emoji = o.optString("emoji", "🔕"),
            imageFile = if (o.has("imageFile")) o.optString("imageFile") else null,
            imageFileOn = if (o.has("imageFileOn")) o.optString("imageFileOn") else null,
            bgOn = if (o.has("bgOn")) o.optInt("bgOn") else null,
            bgOff = if (o.has("bgOff")) o.optInt("bgOff") else null,
            fgOn = if (o.has("fgOn")) o.optInt("fgOn") else null,
            fgOff = if (o.has("fgOff")) o.optInt("fgOff") else null,
            shape = o.optInt("shape", Shape.ROUND_L).coerceIn(0, 3),
            iconSize = o.optInt("iconSize", IconSize.MEDIUM).coerceIn(0, 2),
            showLabel = o.optBoolean("showLabel", false),
            bgOffA = o.optInt("boa", 100).coerceIn(0, 100),
            bgOnA = o.optInt("bna", 100).coerceIn(0, 100),
            keepRatio = o.optBoolean("kr", true),
            mL = o.optInt("ml", 2).coerceIn(0, 24),
            mT = o.optInt("mt", 2).coerceIn(0, 24),
            mR = o.optInt("mr", 2).coerceIn(0, 24),
            mB = o.optInt("mb", 2).coerceIn(0, 24),
            onSep = o.optBoolean("osep", false),
            iconKeyOn = if (o.has("ikon")) o.optString("ikon").takeIf { FlatIcons.has(it) } else null,
            emojiOn = o.optString("emon", "🔔").take(8),
            tab = o.optInt("tab", 0).coerceIn(0, 1),
            iconKey = if (o.has("icon")) o.optString("icon").takeIf { FlatIcons.has(it) } else null,
            volMode = o.optInt("vm", VolMode.OFF).coerceIn(0, 2),
            volFixed = (0 until 4).map { i -> o.optJSONArray("vf")?.optInt(i, 50)?.coerceIn(0, 100) ?: 50 },
            tapVibe = o.optBoolean("vib", true),
            releaseAll = o.optBoolean("ra", false),
            schedOn = o.optBoolean("son", false),
            schedStart = o.optInt("ss", 22 * 60).coerceIn(0, 1439),
            schedEnd = o.optInt("se", -1).coerceIn(-1, 1439),
            schedDays = o.optInt("sd", 127).and(127).let { if (it == 0) 127 else it },
        )
    }
}

/** What the device looked like before the first profile was applied. Restored on release. */
data class Snapshot(
    val ringer: Int,
    val filter: Int,
    val categories: Int,
    val callSenders: Int,
    val messageSenders: Int,
    val effects: Int,
    val convSenders: Int,
    val volumes: Map<Int, Int>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ringer", ringer); put("filter", filter); put("cats", categories)
        put("calls", callSenders); put("msgs", messageSenders); put("fx", effects)
        put("conv", convSenders)
        put("vols", JSONObject().also { v -> volumes.forEach { (k, x) -> v.put(k.toString(), x) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Snapshot {
            val vo = o.optJSONObject("vols") ?: JSONObject()
            val vols = HashMap<Int, Int>()
            vo.keys().forEach { k -> k.toIntOrNull()?.let { vols[it] = vo.optInt(k) } }
            return Snapshot(
                o.optInt("ringer"), o.optInt("filter", 1), o.optInt("cats"),
                o.optInt("calls"), o.optInt("msgs"), o.optInt("fx"), o.optInt("conv", 3), vols,
            )
        }
    }
}

data class State(
    val activeId: String? = null,
    val startedAt: Long = 0L,
    /** 0 = no limit */
    val expiresAt: Long = 0L,
    val snapshot: Snapshot? = null,
    val btMedia: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        if (activeId != null) put("active", activeId)
        put("startedAt", startedAt); put("expiresAt", expiresAt)
        if (snapshot != null) put("snap", snapshot.toJson())
        put("bt", btMedia)
    }

    companion object {
        fun fromJson(o: JSONObject): State = State(
            activeId = if (o.has("active")) o.optString("active") else null,
            startedAt = o.optLong("startedAt"),
            expiresAt = o.optLong("expiresAt"),
            snapshot = o.optJSONObject("snap")?.let { Snapshot.fromJson(it) },
            btMedia = o.optBoolean("bt"),
        )
    }
}

/** Text put in front of the profile name where no picture is shown (notification, dialogs). */
fun Profile.imageFiles(): List<String> = listOfNotNull(imageFile, imageFileOn)

fun Profile.titlePrefix(): String = if (imageFile == null && iconKey == null) "$emoji " else ""
