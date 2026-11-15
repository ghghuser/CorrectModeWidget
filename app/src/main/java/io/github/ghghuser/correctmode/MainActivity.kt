package io.github.ghghuser.correctmode

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
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
        tab = try { getSharedPreferences("ui", MODE_PRIVATE).getInt("tab", tab).coerceIn(0, 1) } catch (e: Exception) { tab }
        Engine.reconcile(this)
        Schedules.reschedule(this) // safety net: keeps the timer alarm in step with the profiles
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
        head.addView(Ui.button(this, s(R.string.add)) { chooseHowToAdd() })
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
        content.addView(Ui.text(this, s(if (tab == 1) R.string.tab_hint_presets else R.string.tab_hint), 11f, secondary = true))
        content.addView(Ui.text(this, s(R.string.main_hint), 11f, secondary = true))
        content.addView(Ui.text(this, s(R.string.legend), 11f, secondary = true).apply { setPadding(0, dp(4), 0, dp(2)) })

        val activeId = Store.state(this).activeId
        val list = Store.profiles(this).filter { it.tab == tab }
        if (list.isEmpty()) content.addView(Ui.text(this, s(R.string.tab_empty), 13f, secondary = true).apply { setPadding(0, dp(12), 0, dp(12)) })
        for (p in list) content.addView(profileCard(p, p.id == activeId))
        content.addView(Ui.text(this, s(R.string.widget_howto), 12f, secondary = true))
    }

    /** Icon = turn on / off. Text area = edit (long press: menu). Right column = move up / down (inside the tab). */
    private fun profileCard(p: Profile, active: Boolean): View {
        val card = Ui.card(this, active)
        val row = Ui.hbox(this)
        row.gravity = Gravity.TOP

        val icon = Widgets.preview(this, p, active, 60)
        icon.contentDescription = p.name + ": " + s(if (active) R.string.release else R.string.turn_on)
        icon.setOnClickListener { Flow.toggle(this, p) { rebuild() } }
        row.addView(icon, Ui.lp(dp(60), dp(60)))

        val col = Ui.vbox(this).apply { setPadding(dp(12), 0, dp(8), 0) }
        col.addView(Ui.text(this, p.name, 16f, bold = true))
        col.addView(Ui.text(this, Describe.chips(p), 14f))
        col.addView(Ui.text(this, Describe.summary(this, p), 12f, secondary = true))
        val extra = listOfNotNull(s(R.string.duration_label) + ": " + Ui.durationLabel(this, p.duration), Ui.timerLabel(this, p)).joinToString("  ")
        col.addView(Ui.text(this, extra, 12f, secondary = true))
        col.setOnClickListener { edit(p) }
        col.setOnLongClickListener { showMenu(p); true }
        row.addView(col, Ui.lp(0, weight = 1f))

        val arrows = Ui.vbox(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        fun arrow(symbol: String, desc: String, topGap: Int, onClick: () -> Unit) {
            arrows.addView(Ui.button(this, symbol, onClick).apply {
                minWidth = 0; minHeight = 0; setPadding(0, 0, 0, 0); contentDescription = desc
            }, Ui.lp(dp(48), dp(40)).apply { topMargin = dp(topGap) })
        }
        arrow("▲", s(R.string.move_up), 0) { Store.move(this, p.id, -1); Widgets.updateAll(this); rebuild() }
        arrow("▼", s(R.string.move_down), 4) { Store.move(this, p.id, +1); Widgets.updateAll(this); rebuild() }
        row.addView(arrows)
        card.addView(row)
        return card
    }

    private fun edit(p: Profile) {
        startActivity(Intent(this, ProfileEditActivity::class.java).putExtra("id", p.id))
    }

    private fun showMenu(p: Profile) {
        val items = arrayOf(s(R.string.menu_edit), s(R.string.menu_copy), s(R.string.delete))
        AlertDialog.Builder(this).setTitle(p.titlePrefix() + p.name).setItems(items) { _, which ->
            when (which) {
                0 -> edit(p)
                1 -> { ProfileOps.copy(this, p); tab = 0; rebuild() }
                else -> AlertDialog.Builder(this)
                    .setMessage(s(R.string.delete_confirm, p.name))
                    .setPositiveButton(s(R.string.delete)) { _, _ -> ProfileOps.delete(this, p); rebuild() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }.show()
    }

    private fun chooseHowToAdd() {
        // a new profile always goes to the left tab (your own)
        val items = arrayOf(s(R.string.add_wizard), s(R.string.add_manual), s(R.string.add_pattern))
        AlertDialog.Builder(this).setTitle(s(R.string.add_choose_title)).setItems(items) { _, which ->
            tab = 0
            try { getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("tab", 0).apply() } catch (e: Exception) { }
            when (which) {
                0 -> startActivity(Intent(this, WizardActivity::class.java))
                1 -> startActivity(Intent(this, ProfileEditActivity::class.java).putExtra("tab", 0))
                else -> choosePattern()
            }
        }.show()
    }

    private fun choosePattern() {
        val names = Patterns.NAMES.map { s(it) }.toTypedArray()
        AlertDialog.Builder(this).setTitle(s(R.string.add_pattern_title)).setItems(names) { _, i ->
            startActivity(Intent(this, ProfileEditActivity::class.java).putExtra("pattern", i))
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

}
