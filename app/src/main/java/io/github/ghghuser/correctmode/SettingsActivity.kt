package io.github.ghghuser.correctmode

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout

/** Everything that is not a profile: permissions, language, Bluetooth, tab names, backup, terms, diagnostics. */
class SettingsActivity : Activity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Loc.wrap(newBase))

    private companion object {
        const val REQ_EXPORT = 20
        const val REQ_IMPORT = 21
    }

    private lateinit var content: LinearLayout

    private fun s(id: Int, vararg a: Any): String = getString(id, *a)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = Ui.vbox(this).apply { setPadding(dp(16), dp(4), dp(16), dp(24)) }
        setContentView(Ui.screen(this, s(R.string.settings_title), true, content))
    }

    override fun onResume() {
        super.onResume()
        rebuild() // the permission rows must show the new state after coming back from a system screen
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Engine.reconcile(this)
        rebuild()
    }

    private fun rebuild() {
        content.removeAllViews()
        addPermissions()
        addGeneral()
        addTabs()
        addBackup()
        addTerms()
        addDiagnostics()
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
            requestOrOpenAppSettings(Manifest.permission.POST_NOTIFICATIONS, 2)
        }
        permRow(card, s(R.string.perm_bt), Bt.hasConnectPermission(this), s(R.string.perm_bt_hint)) {
            requestOrOpenAppSettings(Manifest.permission.BLUETOOTH_CONNECT, 3)
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

    /** After two refusals Android no longer shows the question: then the app's own settings page is the way. */
    private fun requestOrOpenAppSettings(perm: String, code: Int) {
        if (shouldShowRequestPermissionRationale(perm) || getSharedPreferences("ui", MODE_PRIVATE).getBoolean("asked_$perm", false).not()) {
            getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("asked_$perm", true).apply()
            requestPermissions(arrayOf(perm), code)
        } else {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                Ui.toast(this, e.javaClass.simpleName + ": " + e.message, true)
            }
        }
    }

    // ---- language / bluetooth -------------------------------------------------------------

    private fun addGeneral() {
        val card = Ui.card(this)
        card.addView(Ui.text(this, s(R.string.settings_general), 16f, bold = true))
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
        content.addView(card)
    }

    private fun addTabs() {
        val card = Ui.card(this)
        card.addView(Ui.text(this, s(R.string.tabs_title), 16f, bold = true))
        for (i in 0..1) {
            card.addView(Ui.button(this, s(if (i == 0) R.string.tab_name_left else R.string.tab_name_right, Store.tabName(this, i))) {
                Ui.promptText(this, s(R.string.tab_rename_title), s(R.string.tab_rename_hint), Store.rawTabName(this, i) ?: "", 20) {
                    Store.setTabName(this, i, it); rebuild()
                }
            })
        }
        content.addView(card)
    }

    // ---- backup ---------------------------------------------------------------------------

    private fun addBackup() {
        val card = Ui.card(this)
        card.addView(Ui.text(this, s(R.string.backup_title), 16f, bold = true))
        card.addView(Ui.text(this, s(R.string.backup_hint), 12f, secondary = true))
        card.addView(Ui.button(this, s(R.string.backup_export)) {
            try {
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json").putExtra(Intent.EXTRA_TITLE, "CorrectMode-backup.json"),
                    REQ_EXPORT,
                )
            } catch (e: Exception) { Ui.toast(this, e.javaClass.simpleName + ": " + e.message, true) }
        })
        card.addView(Ui.button(this, s(R.string.backup_import)) {
            try {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT,
                )
            } catch (e: Exception) { Ui.toast(this, e.javaClass.simpleName + ": " + e.message, true) }
        })
        content.addView(card)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_EXPORT -> {
                val err = Backup.export(this, uri)
                Ui.toast(this, err ?: s(R.string.backup_done_export), true)
            }
            REQ_IMPORT -> AlertDialog.Builder(this)
                .setMessage(s(R.string.backup_import_confirm))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val err = Backup.import(this, uri)
                    Ui.toast(this, err ?: s(R.string.backup_done_import), true)
                    if (err == null) recreate()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
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
