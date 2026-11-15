package io.github.ghghuser.correctmode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Export / import of all settings as one JSON file (profiles incl. their pictures, tab names, language,
 * Bluetooth target). NOT included: the widget placement (widget ids belong to this phone's home screen)
 * and the current on/off state (a backup must never switch anything on by itself).
 *
 * The import treats the file as untrusted input: size limits, every value is clamped, picture bytes are
 * decoded and re-encoded (the file's own names are never used), and nothing is replaced until everything
 * has been checked.
 */
object Backup {
    private const val APP = "CorrectMode"
    private const val FORMAT = 1
    private const val MAX_BYTES = 8_000_000
    private const val MAX_PROFILES = 100
    private const val MAX_IMAGE_BYTES = 600_000
    private val ID_OK = Regex("^[A-Za-z0-9]{1,32}$")

    class Failure(val msg: String) : Exception(msg)

    /** @return null = done, otherwise the reason. */
    fun export(ctx: Context, uri: Uri): String? = try {
        val root = JSONObject()
        root.put("app", APP); root.put("format", FORMAT); root.put("exportedAt", System.currentTimeMillis())
        root.put("settings", JSONObject().apply {
            put("language", Store.language(ctx)); put("btMode", Store.btMode(ctx))
            Store.rawTabName(ctx, 0)?.let { put("tab0", it) }
            Store.rawTabName(ctx, 1)?.let { put("tab1", it) }
        })
        val arr = JSONArray()
        for (p in Store.profiles(ctx)) {
            val o = p.toJson()
            val f = p.imageFile?.let { Store.imageFile(ctx, it) }
            if (f != null && f.isFile && f.length() in 1..MAX_IMAGE_BYTES.toLong()) {
                o.put("imageData", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
            }
            arr.put(o)
        }
        root.put("profiles", arr)
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw Failure("cannot open the file")
        out.use { it.write(root.toString(2).toByteArray(Charsets.UTF_8)) }
        null
    } catch (e: Exception) {
        e.javaClass.simpleName + ": " + (e.message ?: "")
    }

    /** @return null = done, otherwise the reason (nothing was changed in that case). */
    fun import(ctx: Context, uri: Uri): String? {
        val app = ctx.applicationContext
        val written = ArrayList<String>()
        try {
            if (Store.state(app).activeId != null) return Loc.str(app, R.string.backup_err_active)
            val bytes = readLimited(app, uri)
            val root = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (e: Exception) {
                return Loc.str(app, R.string.backup_err_format, "JSON")
            }
            if (root.optString("app") != APP) return Loc.str(app, R.string.backup_err_format, "app")
            if (root.optInt("format", -1) != FORMAT) return Loc.str(app, R.string.backup_err_format, "format")
            val arr = root.optJSONArray("profiles") ?: return Loc.str(app, R.string.backup_err_format, "profiles")
            if (arr.length() == 0 || arr.length() > MAX_PROFILES) return Loc.str(app, R.string.backup_err_format, "profiles")

            val seen = HashSet<String>()
            val list = ArrayList<Profile>()
            val stamp = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = try { Profile.fromJson(o) } catch (e: Exception) { continue }
                val id = if (ID_OK.matches(p.id) && seen.add(p.id)) p.id else Store.newId().also { seen.add(it) }
                val clean = p.copy(
                    id = id,
                    name = p.name.trim().take(30).ifEmpty { "?" },
                    emoji = p.emoji.take(8).ifEmpty { "🔕" },
                    duration = if (p.duration == Dur.ASK || p.duration == Dur.NONE) p.duration else p.duration.coerceIn(1, 1440),
                    imageFile = null,
                )
                val data = o.optString("imageData", "")
                if (data.isNotEmpty()) {
                    val name = "${id}_${stamp}_$i.png"
                    if (writeImage(app, data, name)) { written.add(name); clean.imageFile = name }
                }
                list.add(clean)
            }
            if (list.isEmpty()) return Loc.str(app, R.string.backup_err_format, "profiles")

            // everything checked: now replace
            Store.replaceProfiles(app, list)
            root.optJSONObject("settings")?.let { st ->
                st.optString("language").takeIf { it in listOf("system", "ja", "en") }?.let { Store.setLanguage(app, it) }
                if (st.has("btMode")) Store.setBtMode(app, st.optInt("btMode").coerceIn(0, 1))
                Store.setTabName(app, 0, st.optString("tab0", ""))
                Store.setTabName(app, 1, st.optString("tab1", ""))
            }
            val keep = list.mapNotNull { it.imageFile }.toSet()
            Store.imageDir(app).listFiles()?.forEach { if (it.name !in keep) it.delete() }
            Widgets.updateAll(app)
            Schedules.reschedule(app)
            return null
        } catch (e: Failure) {
            written.forEach { Store.imageFile(app, it).delete() }
            return e.msg
        } catch (e: Exception) {
            written.forEach { Store.imageFile(app, it).delete() }
            return e.javaClass.simpleName + ": " + (e.message ?: "")
        }
    }

    private fun readLimited(ctx: Context, uri: Uri): ByteArray {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw Failure("cannot open the file")
        input.use {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BYTES) throw Failure(Loc.str(ctx, R.string.backup_err_big))
            }
            return out.toByteArray()
        }
    }

    /** Decodes, checks the size and writes a clean small PNG under OUR file name. false = unusable picture. */
    private fun writeImage(ctx: Context, b64: String, name: String): Boolean {
        return try {
            if (b64.length > MAX_IMAGE_BYTES * 4 / 3 + 16) return false
            val raw = Base64.decode(b64, Base64.DEFAULT)
            if (raw.isEmpty() || raw.size > MAX_IMAGE_BYTES) return false
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            if (maxOf(bounds.outWidth, bounds.outHeight) > 1024 || bounds.outWidth.toLong() * bounds.outHeight > 4_000_000L) return false
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return false
            val scale = 256f / maxOf(bmp.width, bmp.height)
            val out = if (scale < 1f) Bitmap.createScaledBitmap(
                bmp, (bmp.width * scale).roundToInt().coerceAtLeast(1), (bmp.height * scale).roundToInt().coerceAtLeast(1), true,
            ) else bmp
            val f = Store.imageFile(ctx, name)
            FileOutputStream(f).use { if (!out.compress(Bitmap.CompressFormat.PNG, 100, it)) return false }
            if (BitmapFactory.decodeFile(f.path) == null) { f.delete(); return false }
            true
        } catch (e: Exception) {
            false
        }
    }
}
