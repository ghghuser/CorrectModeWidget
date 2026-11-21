package io.github.ghghuser.correctmode

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

/** All persistent data lives here (SharedPreferences, no network, no external storage). */
object Store {
    private const val FILE = "correctmode"

    private fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- settings -------------------------------------------------------------------------

    fun language(ctx: Context): String = sp(ctx).getString("language", "system") ?: "system"
    fun setLanguage(ctx: Context, v: String) { sp(ctx).edit().putString("language", v).commit() }


    // ---- tab names (null = the default name) ----------------------------------------------

    fun rawTabName(ctx: Context, i: Int): String? = sp(ctx).getString("tab$i", null)?.takeIf { it.isNotBlank() }
    fun tabName(ctx: Context, i: Int): String =
        rawTabName(ctx, i) ?: Loc.str(ctx, if (i == 0) R.string.tab_default_0 else R.string.tab_default_1)
    fun setTabName(ctx: Context, i: Int, name: String?) {
        val n = name?.trim()?.take(20)
        sp(ctx).edit().apply { if (n.isNullOrEmpty()) remove("tab$i") else putString("tab$i", n) }.commit()
    }

    /** How many times in a row a release could not restore the settings (see Engine.release). */
    fun releaseFailures(ctx: Context): Int = sp(ctx).getInt("relFail", 0)
    fun setReleaseFailures(ctx: Context, n: Int) { sp(ctx).edit().putInt("relFail", n).commit() }

    /** Diagnostics: how long the last widget tap took inside the app (ms), see TapActivity. */
    fun lastTapMs(ctx: Context): Long = sp(ctx).getLong("tapMs", -1L)

    /** Until this time (wall clock) the widget buttons are drawn greyed: a tap is being handled / ignored. */
    fun busyUntil(ctx: Context): Long = sp(ctx).getLong("busyUntil", 0L)
    fun setBusyUntil(ctx: Context, t: Long) { sp(ctx).edit().putLong("busyUntil", t).apply() }
    fun setLastTapMs(ctx: Context, ms: Long) { sp(ctx).edit().putLong("tapMs", ms).apply() }

    fun lastError(ctx: Context): String? = sp(ctx).getString("lastError", null)
    fun setLastError(ctx: Context, msg: String?) {
        val had = lastError(ctx) != null
        sp(ctx).edit().apply { if (msg == null) remove("lastError") else putString("lastError", msg) }.commit()
        // the warning mark on the widgets follows this value
        if (had != (msg != null)) try { Widgets.updateAll(ctx) } catch (e: Exception) { }
    }

    // ---- state ----------------------------------------------------------------------------

    fun state(ctx: Context): State {
        val s = sp(ctx).getString("state", null) ?: return State()
        return try { State.fromJson(JSONObject(s)) } catch (e: Exception) { State() }
    }

    /** commit() on purpose: the state must survive the process being killed right after. */
    fun saveState(ctx: Context, st: State) {
        sp(ctx).edit().putString("state", st.toJson().toString()).commit()
    }

    fun clearState(ctx: Context) { sp(ctx).edit().remove("state").commit() }

    // ---- profiles -------------------------------------------------------------------------

    fun profiles(ctx: Context): List<Profile> {
        val s = sp(ctx).getString("profiles", null) ?: return seed(ctx)
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                try { Profile.fromJson(arr.getJSONObject(i)) } catch (e: Exception) { null }
            }
        } catch (e: Exception) { emptyList() }
    }

    fun profile(ctx: Context, id: String?): Profile? = if (id == null) null else profiles(ctx).firstOrNull { it.id == id }

    private fun save(ctx: Context, list: List<Profile>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp(ctx).edit().putString("profiles", arr.toString()).commit()
    }

    fun upsert(ctx: Context, p: Profile) {
        val list = profiles(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list.add(p)
        save(ctx, list)
    }

    fun delete(ctx: Context, id: String) {
        val list = profiles(ctx)
        list.firstOrNull { it.id == id }?.imageFiles()?.forEach { imageFile(ctx, it).delete() }
        save(ctx, list.filter { it.id != id })
        // drop it from every widget
        val w = widgets(ctx)
        w.keys().asSequence().toList().forEach { k ->
            val arr = w.optJSONArray(k) ?: return@forEach
            val keep = JSONArray()
            for (i in 0 until arr.length()) if (arr.optString(i) != id) keep.put(arr.optString(i))
            w.put(k, keep)
        }
        sp(ctx).edit().putString("widgets", w.toString()).commit()
    }

    /** Moves up / down among the profiles of the SAME tab. */
    fun move(ctx: Context, id: String, delta: Int) {
        val list = profiles(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        var j = i + delta
        while (j in list.indices && list[j].tab != list[i].tab) j += delta
        if (j !in list.indices) return
        val t = list[i]; list[i] = list[j]; list[j] = t
        save(ctx, list)
    }

    /** Moves a profile to the other tab (it goes to the end of that tab). */
    fun setTab(ctx: Context, id: String, tab: Int) {
        val list = profiles(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        val p = list.removeAt(i)
        p.tab = tab.coerceIn(0, 1)
        list.add(p)
        save(ctx, list)
    }

    /** Replaces the whole list (import). */
    fun replaceProfiles(ctx: Context, list: List<Profile>) {
        save(ctx, list)
    }

    // ---- ready-made profiles ----------------------------------------------------------------

    /** The 8 behaviours that really differ, ready-made, for the right tab. */
    private fun patternPresets(ctx: Context): List<Profile> {
        val c = Loc.wrap(ctx)
        return Patterns.LIST.indices.map { i -> freshPreset(c, i, newId(), 1, i) }
    }

    /** The factory version of pattern [i], keeping [id] (used by "reset" and by "pick from the ready-made patterns"). */
    fun freshPreset(ctx: Context, i: Int, id: String, tab: Int, preset: Int): Profile {
        val c = Loc.wrap(ctx)
        val t = Patterns.LIST[i]
        val p = Profile(id, c.getString(t.nameRes), t.ringer, t.dnd, iconKey = t.icon, tab = tab, preset = preset)
        p.mediaOnBt = t.bt != Patterns.NO_BT
        if (p.mediaOnBt) p.btScope = t.bt
        if (t.btOnly) { p.calls = 0; p.messages = 0 }
        if (i == Patterns.ALARMS_ONLY) { p.alarms = true; p.media = true; p.calls = 0; p.messages = 0 } // alarms only
        return p
    }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    // ---- images ---------------------------------------------------------------------------

    fun imageDir(ctx: Context): File = File(ctx.filesDir, "images").apply { mkdirs() }

    /** Only plain file names are accepted (no path traversal). */
    fun imageFile(ctx: Context, name: String): File = File(imageDir(ctx), File(name).name)

    // ---- widgets --------------------------------------------------------------------------

    private fun widgets(ctx: Context): JSONObject =
        try { JSONObject(sp(ctx).getString("widgets", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    /** null = widget has not been configured yet. */
    fun widgetProfiles(ctx: Context, widgetId: Int): List<String>? {
        val arr = widgets(ctx).optJSONArray(widgetId.toString()) ?: return null
        return (0 until arr.length()).map { arr.optString(it) }
    }

    fun setWidgetProfiles(ctx: Context, widgetId: Int, ids: List<String>) {
        val w = widgets(ctx)
        w.put(widgetId.toString(), JSONArray(ids))
        sp(ctx).edit().putString("widgets", w.toString()).commit()
    }

    fun removeWidgets(ctx: Context, ids: IntArray) {
        val w = widgets(ctx)
        ids.forEach { w.remove(it.toString()) }
        val l = looks(ctx)
        ids.forEach { l.remove(it.toString()) }
        sp(ctx).edit().putString("widgets", w.toString()).putString("wlook", l.toString()).commit()
    }

    /** Whole-widget background of a switch widget. alpha 0 = none. [items]: the buttons keep their own backgrounds on top. */
    class WidgetLook(val color: Int = 0x1F2933, val alpha: Int = 100, val items: Boolean = true)

    private fun looks(ctx: Context): JSONObject =
        try { JSONObject(sp(ctx).getString("wlook", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun widgetLook(ctx: Context, widgetId: Int): WidgetLook {
        val o = looks(ctx).optJSONObject(widgetId.toString()) ?: return WidgetLook()
        return WidgetLook(o.optInt("c", 0x1F2933) and 0xFFFFFF, o.optInt("a", 100).coerceIn(0, 100), o.optBoolean("i", true))
    }

    fun setWidgetLook(ctx: Context, widgetId: Int, look: WidgetLook) {
        val l = looks(ctx)
        l.put(widgetId.toString(), JSONObject().put("c", look.color and 0xFFFFFF).put("a", look.alpha.coerceIn(0, 100)).put("i", look.items))
        sp(ctx).edit().putString("wlook", l.toString()).commit()
    }

    // ---- first start ----------------------------------------------------------------------

    private fun seed(ctx: Context): List<Profile> {
        val c = Loc.wrap(ctx)
        // two profiles of your own to start with
        val list = listOf(
            Profile(newId(), c.getString(R.string.sample_vib_bt), Ringer.VIBRATE, Dnd.PRIORITY, iconKey = "vibrate",
                calls = 1, messages = 0, mediaOnBt = true),
            Profile(newId(), c.getString(R.string.sample_mute_all), Ringer.MUTE, Dnd.NONE, iconKey = "volume_off", mediaOnBt = false),
        )
        val all = list + patternPresets(ctx)
        save(ctx, all)
        return all
    }

    fun systemLocaleTag(): String = Locale.getDefault().toLanguageTag()
}
