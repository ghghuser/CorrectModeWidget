package io.github.ghghuser.correctmode

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Loc.wrap(newBase))

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = Loc.str(this, R.string.app_name)
        content = Ui.vbox(this).apply { setPadding(dp(16), dp(8), dp(16), dp(24)) }
        val scroll = ScrollView(this).apply { addView(content) }
        scroll.applyBottomInsets()
        setContentView(scroll)
        if (savedInstanceState == null) askRuntimePermissionsOnce()
    }

    override fun onResume() {
        super.onResume()
        Engine.reconcile(this)
        rebuild()
    }

    private fun askRuntimePermissionsOnce() {
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getBoolean("askedPerms", false)) return
        prefs.edit().putBoolean("askedPerms", true).apply()
        val missing = listOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.BLUETOOTH_CONNECT)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Engine.reconcile(this)
        rebuild()
    }

    private fun s(id: Int, vararg a: Any): String = getString(id, *a)

    private fun rebuild() {
        content.removeAllViews()
        addStatus()
        addPermissions()
        addProfiles()
        addSettings()
        addTerms()
        addDiagnostics()
    }

    // ---- status ---------------------------------------------------------------------------

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

    // ---- permissions ----------------------------------------------------------------------

    private fun permRow(card: LinearLayout, label: String, ok: Boolean, hint: String, fix: (() -> Unit)?) {
        val row = Ui.hbox(this)
        row.addView(Ui.text(this, (if (ok) "✅ " else "⚠️ ") + label, 14f), Ui.lp(0, weight = 1f))
        if (!ok && fix != null) row.addView(Ui.button(this, s(R.string.fix)) { fix() })
        card.addView(row)
        if (!ok) card.addView(Ui.text(this, hint, 12f, secondary = true))
    }

    private fun addPermissions() {
        val card = Ui.card(this)
        card.addView(Ui.text(this, s(R.string.perm_title), 16f, bold = true))
        permRow(card, s(R.string.perm_dnd), Engine.hasAccess(this), s(R.string.perm_dnd_hint)) { Ui.openAccessSettings(this) }
        permRow(card, s(R.string.perm_notif), checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED, s(R.string.perm_notif_hint)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        permRow(card, s(R.string.perm_bt), Bt.hasConnectPermission(this), s(R.string.perm_bt_hint)) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 3)
        }
        permRow(card, s(R.string.perm_alarm), Scheduler.canExact(this), s(R.string.perm_alarm_hint)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                Ui.toast(this, e.javaClass.simpleName + ": " + e.message, true)
            }
        }
        content.addView(card)
    }

    // ---- profiles -------------------------------------------------------------------------

    private fun addProfiles() {
        val head = Ui.hbox(this)
        head.addView(Ui.text(this, s(R.string.profiles_title), 18f, bold = true), Ui.lp(0, weight = 1f))
        head.addView(Ui.button(this, s(R.string.add)) {
            startActivity(Intent(this, ProfileEditActivity::class.java))
        })
        content.addView(head)
        content.addView(Ui.text(this, s(R.string.profiles_hint), 12f, secondary = true))

        val activeId = Store.state(this).activeId
        for (p in Store.profiles(this)) {
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
            btns.addView(Ui.button(this, s(if (active) R.string.release else R.string.turn_on)) {
                Flow.toggle(this, p) { rebuild() }
            }, Ui.lp(0, weight = 1f))
            btns.addView(Ui.button(this, s(R.string.edit)) {
                startActivity(Intent(this, ProfileEditActivity::class.java).putExtra("id", p.id))
            }, Ui.lp(0, weight = 1f))
            btns.addView(Ui.button(this, "▲") { Store.move(this, p.id, -1); Widgets.updateAll(this); rebuild() })
            btns.addView(Ui.button(this, "▼") { Store.move(this, p.id, +1); Widgets.updateAll(this); rebuild() })
            card.addView(btns)
            content.addView(card)
        }
        content.addView(Ui.text(this, s(R.string.widget_howto), 12f, secondary = true))
    }

    // ---- settings -------------------------------------------------------------------------

    private fun addSettings() {
        content.addView(Ui.text(this, s(R.string.settings_title), 18f, bold = true).apply { setPadding(0, dp(12), 0, 0) })
        val card = Ui.card(this)

        val langs = listOf("system", "ja", "en")
        val langLabels = listOf(s(R.string.lang_system), "日本語", "English")
        val langNow = langs.indexOf(Store.language(this)).coerceAtLeast(0)
        card.addView(Ui.button(this, s(R.string.language) + ": " + langLabels[langNow]) {
            Ui.choose(this, s(R.string.language), langLabels, langNow) { i ->
                Store.setLanguage(this, langs[i])
                Widgets.updateAll(this)
                recreate()
            }
        })

        val btLabels = listOf(s(R.string.bt_all), s(R.string.bt_earphone))
        val btNow = Store.btMode(this)
        card.addView(Ui.button(this, s(R.string.bt_target) + ": " + btLabels[btNow]) {
            Ui.choose(this, s(R.string.bt_target), btLabels, btNow) { i ->
                Store.setBtMode(this, i)
                Engine.onBtChanged(this)
                rebuild()
            }
        })
        card.addView(Ui.text(this, s(R.string.bt_target_hint), 12f, secondary = true))

        val volLabels = listOf(s(R.string.vol_off), s(R.string.vol_saved), s(R.string.vol_fixed))
        val volNow = Store.volMode(this)
        card.addView(Ui.button(this, s(R.string.vol_restore) + ": " + volLabels[volNow]) {
            Ui.choose(this, s(R.string.vol_restore), volLabels, volNow) { i ->
                Store.setVolMode(this, i)
                rebuild()
            }
        })
        card.addView(Ui.text(this, s(R.string.vol_hint), 12f, secondary = true))
        if (volNow == VolMode.FIXED) {
            val names = listOf(R.string.vol_ring, R.string.vol_notification, R.string.vol_media, R.string.vol_alarm)
            names.forEachIndexed { idx, nameRes ->
                val label = Ui.text(this, s(nameRes) + ": " + Store.volFixed(this, idx) + "%", 13f)
                val bar = SeekBar(this).apply {
                    max = 100
                    progress = Store.volFixed(this@MainActivity, idx)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                            label.text = s(nameRes) + ": " + value + "%"
                        }
                        override fun onStartTrackingTouch(sb: SeekBar) {}
                        override fun onStopTrackingTouch(sb: SeekBar) { Store.setVolFixed(this@MainActivity, idx, sb.progress) }
                    })
                }
                card.addView(label)
                card.addView(bar)
            }
        }
        content.addView(card)
    }

    // ---- terms / diagnostics --------------------------------------------------------------

    private fun addTerms() {
        val card = Ui.card(this)
        card.addView(Ui.text(this, s(R.string.terms_title), 16f, bold = true))
        card.addView(Ui.text(this, s(R.string.terms_body), 13f))
        content.addView(card)
    }

    private fun addDiagnostics() {
        val card = Ui.card(this)
        card.addView(Ui.text(this, s(R.string.diag_title), 16f, bold = true))
        card.addView(Ui.text(this, s(R.string.diag_version, BuildConfig.VERSION_NAME, BuildConfig.BUILD_NUMBER), 13f))
        val err = Store.lastError(this)
        card.addView(Ui.text(this, if (err == null) s(R.string.diag_no_error) else s(R.string.diag_last_error) + "\n" + err, 13f))
        if (err != null) card.addView(Ui.button(this, s(R.string.diag_clear)) { Store.setLastError(this, null); rebuild() })
        content.addView(card)
    }
}
