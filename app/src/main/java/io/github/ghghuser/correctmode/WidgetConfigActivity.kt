package io.github.ghghuser.correctmode

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox

/**
 * Shown when a widget is placed (and from "reconfigure"). RESULT_OK is set first, so even if the user
 * backs out a working (placeholder) widget remains instead of a broken one.
 *
 * Both widget kinds take one or more profiles. Toggle: each tap goes to the next one, then off.
 * Switch: one button per profile. The two tabs are the same as on the main screen.
 */
class WidgetConfigActivity : Activity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Loc.wrap(newBase))

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var valid = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val mgr = AppWidgetManager.getInstance(this)
        val info = if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) mgr.getAppWidgetInfo(widgetId) else null
        // the activity is exported for the launcher: only accept ids that really belong to this app
        if (info == null || info.provider.packageName != packageName) { finish(); return }
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        valid = true

        val isSwitch = info.provider.className == SwitchWidgetProvider::class.java.name
        val screenTitle = Loc.str(this, if (isSwitch) R.string.widget_switch_title else R.string.widget_toggle_title)

        val profiles = Store.profiles(this)
        val current = Store.widgetProfiles(this, widgetId) ?: emptyList()
        val root = Ui.vbox(this).apply { setPadding(dp(16), dp(4), dp(16), dp(16)) }
        root.addView(Ui.text(this, Loc.str(this, if (isSwitch) R.string.widget_pick_many else R.string.widget_pick_one), 15f))
        root.addView(Ui.text(this, Loc.str(this, R.string.widget_pick_count, profiles.size), 12f, secondary = true))

        // one row per profile; the tab only decides which rows are visible, so ticks are kept across tabs
        val rows = ArrayList<Triple<Profile, CheckBox, android.view.View>>()
        val list = Ui.vbox(this)
        if (profiles.isEmpty()) {
            list.addView(Ui.text(this, Loc.str(this, R.string.no_profiles), 14f, secondary = true))
        }
        for (p in profiles) {
            val label = p.titlePrefix() + p.name + "\n" + Describe.summary(this, p)
            val cb = CheckBox(this).apply { text = label; isChecked = p.id in current }
            list.addView(cb)
            rows.add(Triple(p, cb, cb))
        }

        val firstTicked = profiles.firstOrNull { it.id in current }?.tab
        var tab = firstTicked
            ?: try { getSharedPreferences("ui", MODE_PRIVATE).getInt("tab", 0).coerceIn(0, 1) } catch (e: Exception) { 0 }
        val tabHolder = Ui.vbox(this)
        fun showTab() {
            tabHolder.removeAllViews()
            tabHolder.addView(Ui.tabBar(this, listOf(Store.tabName(this, 0), Store.tabName(this, 1)), tab, { i -> tab = i; showTab() }))
            rows.forEach { (p, _, v) -> v.visibility = if (p.tab == tab) android.view.View.VISIBLE else android.view.View.GONE }
        }
        showTab()
        root.addView(tabHolder)
        root.addView(list)

        root.addView(Ui.button(this, Loc.str(this, R.string.widget_open_app)) {
            startActivity(Intent(this, MainActivity::class.java))
        })
        val buttons = Ui.hbox(this)
        buttons.addView(Ui.button(this, getString(android.R.string.cancel)) { finish() }, Ui.lp(0, weight = 1f))
        buttons.addView(
            Ui.button(this, Loc.str(this, R.string.decide)) {
                val chosen = rows.filter { it.second.isChecked }.map { it.first.id }
                if (chosen.isEmpty()) {
                    Ui.toast(this, Loc.str(this, R.string.widget_pick_none))
                } else {
                    Store.setWidgetProfiles(this, widgetId, chosen)
                    Widgets.update(applicationContext, mgr, widgetId)
                    finish()
                }
            },
            Ui.lp(0, weight = 1f),
        )
        root.addView(buttons)

        setContentView(Ui.screen(this, screenTitle, true, root))
    }

    override fun onDestroy() {
        super.onDestroy()
        // draw the widget in any case (configured, or the tappable placeholder if the user backed out)
        if (valid) Widgets.update(applicationContext, AppWidgetManager.getInstance(this), widgetId)
    }
}
