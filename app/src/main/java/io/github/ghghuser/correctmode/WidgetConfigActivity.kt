package io.github.ghghuser.correctmode

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView

/**
 * Shown when a widget is placed (and from "reconfigure"). RESULT_OK is set first, so even if the user
 * backs out a working (placeholder) widget remains instead of a broken one.
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

        val multi = true // the toggle can hold several profiles too (each tap = next one)
        val isSwitch = info.provider.className == SwitchWidgetProvider::class.java.name
        title = Loc.str(this, if (isSwitch) R.string.widget_switch_title else R.string.widget_toggle_title)

        val profiles = Store.profiles(this)
        val current = Store.widgetProfiles(this, widgetId) ?: emptyList()
        val root = Ui.vbox(this).apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }
        root.addView(Ui.text(this, Loc.str(this, if (isSwitch) R.string.widget_pick_many else R.string.widget_pick_one), 15f))
        root.addView(Ui.text(this, Loc.str(this, R.string.widget_pick_count, Store.profiles(this).size), 12f, secondary = true))

        val checks = ArrayList<Pair<Profile, android.widget.CompoundButton>>()
        val group = RadioGroup(this)
        if (profiles.isEmpty()) {
            root.addView(Ui.text(this, Loc.str(this, R.string.no_profiles), 14f, secondary = true))
        }
        for (p in profiles) {
            val label = (if (p.imageFile == null) p.emoji + "  " else "") + p.name + "\n" + Describe.summary(this, p)
            if (multi) {
                val cb = CheckBox(this).apply { text = label; isChecked = p.id in current }
                root.addView(cb); checks.add(p to cb)
            } else {
                val rb = RadioButton(this).apply { text = label; id = android.view.View.generateViewId(); isChecked = current.firstOrNull() == p.id }
                group.addView(rb); checks.add(p to rb)
            }
        }
        if (!multi) root.addView(group)

        root.addView(Ui.button(this, Loc.str(this, R.string.widget_open_app)) {
            startActivity(Intent(this, MainActivity::class.java))
        })
        val buttons = Ui.hbox(this)
        buttons.addView(Ui.button(this, getString(android.R.string.cancel)) { finish() }, Ui.lp(0, weight = 1f))
        buttons.addView(
            Ui.button(this, Loc.str(this, R.string.save)) {
                val chosen = checks.filter { it.second.isChecked }.map { it.first.id }
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

        val scroll = ScrollView(this).apply { addView(root) }
        scroll.applyBottomInsets()
        setContentView(scroll)
    }

    override fun onDestroy() {
        super.onDestroy()
        // draw the widget in any case (configured, or the tappable placeholder if the user backed out)
        if (valid) Widgets.update(applicationContext, AppWidgetManager.getInstance(this), widgetId)
    }
}
