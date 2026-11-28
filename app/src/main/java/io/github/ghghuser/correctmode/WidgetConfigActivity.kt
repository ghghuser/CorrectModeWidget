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
        // the order of the widget: the order of this list (new ticks go to the end; change it with the arrows below)
        val order = ArrayList<String>(current.filter { id -> profiles.any { it.id == id } })
        val orderBox = Ui.vbox(this)
        fun showOrder() {
            orderBox.removeAllViews()
            if (order.size < 2) return
            orderBox.addView(Ui.text(this, Loc.str(this, R.string.widget_order_title), 14f, bold = true).apply { setPadding(0, dp(12), 0, 0) })
            orderBox.addView(Ui.text(this, Loc.str(this, R.string.widget_order_hint), 12f, secondary = true))
            order.forEachIndexed { i, id ->
                val p = profiles.firstOrNull { it.id == id } ?: return@forEachIndexed
                val row = Ui.hbox(this)
                row.addView(Ui.text(this, "${i + 1}. " + p.titlePrefix() + p.name, 14f), Ui.lp(0, weight = 1f))
                row.addView(Ui.button(this, "▲") { if (i > 0) { order.add(i - 1, order.removeAt(i)); showOrder() } }.apply { minWidth = 0; minHeight = 0 }, Ui.lp(dp(48), dp(40)))
                row.addView(Ui.button(this, "▼") { if (i < order.size - 1) { order.add(i + 1, order.removeAt(i)); showOrder() } }.apply { minWidth = 0; minHeight = 0 }, Ui.lp(dp(48), dp(40)))
                orderBox.addView(row)
            }
        }
        for (p in profiles) {
            val cb = CheckBox(this).apply {
                isChecked = p.id in current
                contentDescription = p.name + ", " + Describe.summary(this@WidgetConfigActivity, p) // the text is a separate view: name the box
            }
            cb.setOnCheckedChangeListener { _, v ->
                if (v) { if (p.id !in order) order.add(p.id) } else order.remove(p.id)
                showOrder()
            }
            // tick, the profile's icon at text size, name + summary (a tap on the text ticks too)
            val row = Ui.hbox(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            row.addView(cb)
            row.addView(Ui.profileIcon(this, p, marginStartDp = 4))
            row.addView(Ui.text(this, p.name + "\n" + Describe.summary(this, p), 15f).apply { setOnClickListener { cb.toggle() } },
                Ui.lp(0, weight = 1f).apply { marginStart = dp(8) })
            list.addView(row)
            rows.add(Triple(p, cb, row))
        }

        val firstTicked = profiles.firstOrNull { it.id in current }?.tab
        var tab = firstTicked
            ?: 0 // a new widget starts on the left tab (own profiles)
        val tabHolder = Ui.vbox(this)
        fun showTab() {
            tabHolder.removeAllViews()
            tabHolder.addView(Ui.tabBar(this, listOf(Store.tabName(this, 0), Store.tabName(this, 1)), tab, { i -> tab = i; showTab() }))
            rows.forEach { (p, _, v) -> v.visibility = if (p.tab == tab) android.view.View.VISIBLE else android.view.View.GONE }
        }
        showTab()
        root.addView(tabHolder)
        root.addView(list)
        showOrder()
        root.addView(orderBox)

        // switch widget only: one background behind the whole set
        var look = Store.widgetLook(this, widgetId)
        if (isSwitch) {
            root.addView(Ui.text(this, getString(R.string.wl_title), 14f, bold = true).apply { setPadding(0, dp(12), 0, 0) })
            val strip = android.view.View(this)
            val alphaText = Ui.text(this, "", 12f, secondary = true)
            fun refreshLook() {
                alphaText.text = getString(R.string.wl_alpha, look.alpha)
                strip.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor((look.color or 0xFF000000.toInt()))
                    alpha = (look.alpha * 255 / 100)
                    setStroke(dp(1), 0x66808080)
                }
            }
            root.addView(strip, Ui.lp(h = dp(40)))
            root.addView(Ui.button(this, getString(R.string.wl_color)) {
                ColorPicker.show(this, look.color or 0xFF000000.toInt(), look.alpha) { c, al ->
                    look = Store.WidgetLook(c and 0xFFFFFF, al, look.items); refreshLook()
                }
            })
            root.addView(alphaText)
            root.addView(Ui.text(this, getString(R.string.wl_alpha_hint), 11f, secondary = true))
            root.addView(CheckBox(this).apply {
                text = getString(R.string.wl_items); isChecked = look.items
                setOnCheckedChangeListener { _, v -> look = Store.WidgetLook(look.color, look.alpha, v) }
            })
            root.addView(Ui.text(this, getString(R.string.wl_items_hint), 11f, secondary = true))
            refreshLook()
        }
        val look0 = Triple(look.color, look.alpha, look.items)
        fun changed(): Boolean = order.filter { id -> rows.any { it.first.id == id && it.second.isChecked } } != current.filter { id -> profiles.any { it.id == id } } ||
            Triple(look.color, look.alpha, look.items) != look0
        root.addView(Ui.button(this, Loc.str(this, R.string.widget_open_app)) {
            if (!changed()) startActivity(Intent(this, MainActivity::class.java))
            else android.app.AlertDialog.Builder(this)
                .setMessage(Loc.str(this, R.string.widget_open_unsaved))
                .setPositiveButton(Loc.str(this, R.string.open_app_anyway)) { _, _ -> startActivity(Intent(this, MainActivity::class.java)) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        })
        val buttons = Ui.hbox(this)
        buttons.addView(Ui.button(this, getString(android.R.string.cancel)) { finish() }, Ui.lp(0, weight = 1f))
        buttons.addView(
            Ui.button(this, Loc.str(this, R.string.decide)) {
                val chosen = order.filter { id -> rows.any { it.first.id == id && it.second.isChecked } }
                if (chosen.isEmpty()) {
                    Ui.toast(this, Loc.str(this, R.string.widget_pick_none))
                } else {
                    Store.setWidgetProfiles(this, widgetId, chosen)
                    if (isSwitch) Store.setWidgetLook(this, widgetId, look)
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
