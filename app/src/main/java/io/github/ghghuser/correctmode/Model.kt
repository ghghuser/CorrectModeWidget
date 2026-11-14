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
    const val ALARMS = 2
    const val NONE = 3
}

object Dur {
    const val ASK = -1
    const val NONE = 0
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
    var mediaOnBt: Boolean = true,
    /** Dur.ASK, Dur.NONE or minutes */
    var duration: Int = Dur.ASK,
    var emoji: String = "🔕",
    var imageFile: String? = null,
    var bgOn: Int? = null,
    var bgOff: Int? = null,
    var fgOn: Int? = null,
    var fgOff: Int? = null,
    var shape: Int = Shape.ROUND_L,
    var iconSize: Int = IconSize.MEDIUM,
    var showLabel: Boolean = false,
    /** 0 = left tab, 1 = right tab (the user can name both) */
    var tab: Int = 0,
    /** key of a built-in flat icon (see FlatIcons); null = emoji */
    var iconKey: String? = null,
    /** what to do with the volumes when this profile is released (VolMode) */
    var volMode: Int = VolMode.OFF,
    /** percent 0..100 for ring, notification, media, alarm (used with VolMode.FIXED) */
    var volFixed: List<Int> = listOf(50, 50, 50, 50),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name)
        put("ringer", ringer); put("dnd", dnd)
        put("calls", calls); put("messages", messages)
        put("media", media); put("alarms", alarms); put("system", system)
        put("mediaOnBt", mediaOnBt); put("duration", duration)
        put("emoji", emoji)
        if (imageFile != null) put("imageFile", imageFile)
        bgOn?.let { put("bgOn", it) }; bgOff?.let { put("bgOff", it) }
        fgOn?.let { put("fgOn", it) }; fgOff?.let { put("fgOff", it) }
        put("shape", shape); put("iconSize", iconSize); put("showLabel", showLabel)
        put("tab", tab)
        iconKey?.let { put("icon", it) }
        put("vm", volMode); put("vf", JSONArray(volFixed))
    }

    companion object {
        fun fromJson(o: JSONObject): Profile = Profile(
            id = o.getString("id"),
            name = o.optString("name", ""),
            ringer = o.optInt("ringer", Ringer.VIBRATE).coerceIn(0, 2),
            dnd = o.optInt("dnd", Dnd.PRIORITY).coerceIn(0, 3),
            calls = o.optInt("calls", 1).coerceIn(0, 3),
            messages = o.optInt("messages", 1).coerceIn(0, 3),
            media = o.optBoolean("media", false),
            alarms = o.optBoolean("alarms", false),
            system = o.optBoolean("system", false),
            mediaOnBt = o.optBoolean("mediaOnBt", true),
            duration = o.optInt("duration", Dur.ASK),
            emoji = o.optString("emoji", "🔕"),
            imageFile = if (o.has("imageFile")) o.optString("imageFile") else null,
            bgOn = if (o.has("bgOn")) o.optInt("bgOn") else null,
            bgOff = if (o.has("bgOff")) o.optInt("bgOff") else null,
            fgOn = if (o.has("fgOn")) o.optInt("fgOn") else null,
            fgOff = if (o.has("fgOff")) o.optInt("fgOff") else null,
            shape = o.optInt("shape", Shape.ROUND_L).coerceIn(0, 3),
            iconSize = o.optInt("iconSize", IconSize.MEDIUM).coerceIn(0, 2),
            showLabel = o.optBoolean("showLabel", false),
            tab = o.optInt("tab", 0).coerceIn(0, 1),
            iconKey = if (o.has("icon")) o.optString("icon").takeIf { FlatIcons.has(it) } else null,
            volMode = o.optInt("vm", VolMode.OFF).coerceIn(0, 2),
            volFixed = (0 until 4).map { i -> o.optJSONArray("vf")?.optInt(i, 50)?.coerceIn(0, 100) ?: 50 },
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
fun Profile.titlePrefix(): String = if (imageFile == null && iconKey == null) "$emoji " else ""
