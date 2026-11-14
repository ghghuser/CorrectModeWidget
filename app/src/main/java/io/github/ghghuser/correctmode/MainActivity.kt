package io.github.ghghuser.correctmode

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.LinearLayout
import java.text.DateFormat
import java.util.Date

/** Main screen = the profiles (two tabs). Everything else lives behind the gear icon (SettingsActivity). */
class MainActivity : Activity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Loc.wrap(newBase))

    private lateinit var content: LinearLayout
    private var tab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = try { getSharedPreferences("ui", MODE_PRIVATE).getInt("tab", 0).coerceIn(0, 1) } catch (e: Exception) { 0 }
        content = Ui.vbox(this).apply { setPadding(dp(16), dp(4), dp(16), dp(24)) }
        val gear = Ui.headerButton(this, "⚙", s(R.string.settings_title)) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        setContentView(Ui.screen(this, s(R.string.app_name), false, content, listOf(gear)))
        if (savedInstanceState == null) askFirstTime()
    }

    override fun onResume() {
        super.onResume()
        Engine.reconcile(this)
        rebuild()
    }

    private fun s(id: Int, vararg a: Any): String = getString(id, *a)

    // ---- first start: ask for the permissions that are still missing -----------------------

    private fun askFirstTime() {
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getBoolean("askedPerms", false)) return
        prefs.edit().putBoolean("askedPerms", true).apply()
        val missing = missingRuntime()
        if (missing.isEmpty() && Engine.hasAccess(this)) return
        AlertDialog.Builder(this)
            .setTitle(s(R.string.perm_first_title))
            .setMessage(s(R.string.perm_first_body))
            .setPositiveButton(s(R.string.perm_first_go)) { _, _ ->
                if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else openAccessIfMissing()
            }
            .setNegativeButton(s(R.string.perm_first_later), null)
            .show()
    }

    private fun missingRuntime(): List<String> =
        listOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH_CONNECT)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun openAccessIfMissing() { if (!Engine.hasAccess(this)) Ui.openAccessSettings(this) }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Engine.reconcile(this)
        rebuild()
        if (requestCode == 1) openAccessIfMissing() // next step of the first-start flow
    }

    // ---- screen ---------------------------------------------------------------------------

    private fun rebuild() {
        content.removeAllViews()
        addNoAccessBanner()
        addStatus()
        addProfiles()
    }

    private fun addNoAccessBanner() {
        if (Engine.hasAccess(this)) return
        val card = Ui.card(this)
        card.addView(Ui.text(this, "⚠️ " + s(R.string.perm_dnd_hint), 14f))
        card.addView(Ui.button(this, s(R.string.fix)) { Ui.openAccessSettings(this) })
        content.addView(card)
    }

    private fun addStatus() {
        val st = Store.state(this)
        val p = Store.profile(this, st.activeId)
        val card = Ui.card(this)
        card.addView(Ui.text(this, s(R.string.status_title), 13f, secondary = true))
        if (p == null) {
            card.addView(Ui.text(this, s(R.string.status_none), 18f, bold = true))
        } else {
            val head = Ui.hbox(this)
            head.addView(Widgets.preview(this, p, true, 48))
            val col = Ui.vbox(this).apply { setPadding(dp(12), 0, 0, 0) }
            col.addView(Ui.text(this, p.name, 18f, bold = true))
            val until = if (st.expiresAt > 0) s(R.string.until_time, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(st.expiresAt))) else s(R.string.no_limit)
            col.addView(Ui.text(this, until, 14f))
            head.addView(col)
            card.addView(head)
            card.addView(Ui.text(this, Describe.detail(this, p), 13f, secondary = true))
            if (st.btMedia && p.dnd == Dnd.PRIORITY && p.mediaOnBt) card.addView(Ui.text(this, s(R.string.status_bt_media), 13f))
            card.addView(Ui.button(this, s(R.string.release)) {
                val r = Engine.release(this, Reason.USER)
                if (!r.ok || r.message != null) Ui.toast(this, r.message ?: "", true)
                rebuild()
            })
        }
        content.addView(card)
    }

    private fun addProfiles() {
        val head = Ui.hbox(this)
        head.addView(Ui.text(this, s(R.string.profiles_title), 18f, bold = true), Ui.lp(0, weight = 1f))
        head.addView(Ui.button(this, s(R.string.add)) {
            startActivity(Intent(this, ProfileEditActivity::class.java).putExtra("tab", tab))
        })
        content.addView(head)
        content.addView(Ui.text(this, s(R.string.profiles_hint), 12f, secondary = true))

        content.addView(Ui.tabBar(this, listOf(Store.tabName(this, 0), Store.tabName(this, 1)), tab, { i ->
            tab = i
            try { getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("tab", i).apply() } catch (e: Exception) { }
            rebuild()
        }) { i ->
            Ui.promptText(this, s(R.string.tab_rename_title), s(R.string.tab_rename_hint), Store.rawTabName(this, i) ?: "", 20) { name ->
                Store.setTabName(this, i, name)
                rebuild()
            }
        })
        content.addView(Ui.text(this, s(R.string.tab_hint), 11f, secondary = true))

        val activeId = Store.state(this).activeId
        val list = Store.profiles(this).filter { it.tab == tab }
        if (list.isEmpty()) content.addView(Ui.text(this, s(R.string.tab_empty), 13f, secondary = true).apply { setPadding(0, dp(12), 0, dp(12)) })
        for (p in list) {
            val active = p.id == activeId
            val card = Ui.card(this)
            val top = Ui.hbox(this)
            top.addView(Widgets.preview(this, p, active, 52))
            val col = Ui.vbox(this).apply { setPadding(dp(12), 0, 0, 0) }
            col.addView(Ui.text(this, p.name, 16f, bold = true))
            col.addView(Ui.text(this, Describe.detail(this, p), 12f, secondary = true))
            col.addView(Ui.text(this, s(R.string.duration_label) + ": " + Ui.durationLabel(this, p.duration), 12f, secondary = true))
            top.addView(col, Ui.lp(0, weight = 1f))
            card.addView(top)

            val btns = Ui.hbox(this)
            // right tab: "⇐" to the LEFT of "turn on"; left tab: "⇒" to the right of "▼"
            if (tab == 1) btns.addView(Ui.button(this, "⇐") { moveTo(p, 0) }.apply { contentDescription = s(R.string.move_left) })
            btns.addView(Ui.button(this, s(if (active) R.string.release else R.string.turn_on)) {
                Flow.toggle(this, p) { rebuild() }
            }, Ui.lp(0, weight = 1f))
            btns.addView(Ui.button(this, s(R.string.edit)) {
                startActivity(Intent(this, ProfileEditActivity::class.java).putExtra("id", p.id))
            }, Ui.lp(0, weight = 1f))
            btns.addView(Ui.button(this, "▲") { Store.move(this, p.id, -1); Widgets.updateAll(this); rebuild() })
            btns.addView(Ui.button(this, "▼") { Store.move(this, p.id, +1); Widgets.updateAll(this); rebuild() })
            if (tab == 0) btns.addView(Ui.button(this, "⇒") { moveTo(p, 1) }.apply { contentDescription = s(R.string.move_right) })
            card.addView(btns)
            content.addView(card)
        }
        content.addView(Ui.text(this, s(R.string.widget_howto), 12f, secondary = true))
    }

    private fun moveTo(p: Profile, toTab: Int) {
        Store.setTab(this, p.id, toTab)
        Widgets.updateAll(this)
        rebuild()
    }
}
