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

    fun btMode(ctx: Context): Int = sp(ctx).getInt("btMode", BtMode.ALL_AUDIO)
    fun setBtMode(ctx: Context, v: Int) { sp(ctx).edit().putInt("btMode", v).commit() }

    fun volMode(ctx: Context): Int = sp(ctx).getInt("volMode", VolMode.OFF)
    fun setVolMode(ctx: Context, v: Int) { sp(ctx).edit().putInt("volMode", v).commit() }

    /** index: 0 ring, 1 notification, 2 media, 3 alarm; percent 0..100 */
    fun volFixed(ctx: Context, index: Int): Int = sp(ctx).getInt("volFixed$index", 50).coerceIn(0, 100)
    fun setVolFixed(ctx: Context, index: Int, pct: Int) {
        sp(ctx).edit().putInt("volFixed$index", pct.coerceIn(0, 100)).commit()
    }

    /** How many times in a row a release could not restore the settings (see Engine.release). */
    fun releaseFailures(ctx: Context): Int = sp(ctx).getInt("relFail", 0)
    fun setReleaseFailures(ctx: Context, n: Int) { sp(ctx).edit().putInt("relFail", n).commit() }

    fun lastError(ctx: Context): String? = sp(ctx).getString("lastError", null)
    fun setLastError(ctx: Context, msg: String?) {
        sp(ctx).edit().apply { if (msg == null) remove("lastError") else putString("lastError", msg) }.commit()
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
        list.firstOrNull { it.id == id }?.imageFile?.let { imageFile(ctx, it).delete() }
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

    fun move(ctx: Context, id: String, delta: Int) {
        val list = profiles(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j < 0 || j >= list.size) return
        val t = list[i]; list[i] = list[j]; list[j] = t
        save(ctx, list)
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
        sp(ctx).edit().putString("widgets", w.toString()).commit()
    }

    // ---- first start ----------------------------------------------------------------------

    private fun seed(ctx: Context): List<Profile> {
        val c = Loc.wrap(ctx)
        val list = listOf(
            Profile(newId(), c.getString(R.string.preset_vib_dnd), Ringer.VIBRATE, Dnd.PRIORITY, emoji = "🤫"),
            Profile(newId(), c.getString(R.string.preset_vib), Ringer.VIBRATE, Dnd.OFF, emoji = "📳"),
            Profile(newId(), c.getString(R.string.preset_mute), Ringer.MUTE, Dnd.OFF, emoji = "🔕"),
            Profile(newId(), c.getString(R.string.preset_dnd), Ringer.NORMAL, Dnd.PRIORITY, emoji = "🌙"),
            Profile(newId(), c.getString(R.string.preset_normal), Ringer.NORMAL, Dnd.OFF, emoji = "🔔"),
        )
        save(ctx, list)
        return list
    }

    fun systemLocaleTag(): String = Locale.getDefault().toLanguageTag()
}
