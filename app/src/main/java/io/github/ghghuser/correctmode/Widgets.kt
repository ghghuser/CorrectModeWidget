package io.github.ghghuser.correctmode

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import kotlin.math.roundToInt

/** Draws the widgets with RemoteViews (no Glance: it updated 1-2 minutes late in earlier projects). */
object Widgets {
    private const val TAG = "CorrectModeWidgets"

    // light / dark defaults. A custom colour is used for both themes.
    private val BG_OFF = 0xFFE3E6EA.toInt() to 0xFF30343A.toInt()
    private val BG_ON = 0xFF1F6FEB.toInt() to 0xFF8AB4FF.toInt()
    private val FG_OFF = 0xFF30343A.toInt() to 0xFFE3E6EA.toInt()
    private val FG_ON = 0xFFFFFFFF.toInt() to 0xFF0B2A5B.toInt()

    private fun pair(custom: Int?, def: Pair<Int, Int>): Pair<Int, Int> =
        if (custom != null) (custom or 0xFF000000.toInt()).let { it to it } else def

    fun updateAll(ctx: Context) {
        val app = ctx.applicationContext
        try {
            val mgr = AppWidgetManager.getInstance(app)
            for (cls in listOf(ToggleWidgetProvider::class.java, SwitchWidgetProvider::class.java)) {
                for (id in mgr.getAppWidgetIds(ComponentName(app, cls))) update(app, mgr, id)
            }
        } catch (e: Exception) {
            Log.w(TAG, "updateAll", e)
        }
    }

    fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
        try {
            val cls = mgr.getAppWidgetInfo(id)?.provider?.className
            val rv = if (cls == SwitchWidgetProvider::class.java.name) buildSwitch(ctx, mgr, id) else buildToggle(ctx, id)
            mgr.updateAppWidget(id, rv)
        } catch (e: Exception) {
            Log.w(TAG, "update $id", e)
        }
    }

    private fun shapeRes(s: Int): Int = when (s) {
        Shape.PILL -> R.drawable.shape_pill
        Shape.ROUND -> R.drawable.shape_round
        Shape.SQUARE -> R.drawable.shape_square
        else -> R.drawable.shape_round_l
    }

    fun tapIntent(ctx: Context, profileId: String): PendingIntent {
        val i = Intent(ctx, TapActivity::class.java)
            .setData(Uri.parse("correctmode://tap/$profileId"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun configIntent(ctx: Context, widgetId: Int): PendingIntent {
        val i = Intent(ctx, WidgetConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .setData(Uri.parse("correctmode://config/$widgetId"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, widgetId, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Shared by the real widgets and the preview in the editor. */
    fun bindItem(ctx: Context, rv: RemoteViews, p: Profile, active: Boolean, tap: PendingIntent?, dim: Boolean = false) {
        val dp = ctx.resources.displayMetrics.density
        val bg = if (active) pair(p.bgOn, BG_ON) else pair(p.bgOff, BG_OFF)
        val fg = if (active) pair(p.fgOn, FG_ON) else pair(p.fgOff, FG_OFF)
        rv.setImageViewResource(R.id.item_bg, shapeRes(p.shape))
        rv.setColorInt(R.id.item_bg, "setColorFilter", bg.first, bg.second)

        val bmp = p.imageFile?.let { BitmapFactory.decodeFile(Store.imageFile(ctx, it).path) }
        val iconRes = FlatIcons.res(p.iconKey)
        // priority: own picture, then built-in flat icon, then emoji
        rv.setViewVisibility(R.id.item_image, if (bmp != null) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.item_icon, if (bmp == null && iconRes != null) View.VISIBLE else View.GONE)
        rv.setViewVisibility(R.id.item_emoji, if (bmp == null && iconRes == null) View.VISIBLE else View.GONE)
        if (bmp != null) {
            rv.setImageViewBitmap(R.id.item_image, bmp)
        } else if (iconRes != null) {
            rv.setImageViewResource(R.id.item_icon, iconRes)
            rv.setColorInt(R.id.item_icon, "setColorFilter", fg.first, fg.second)
        } else {
            rv.setTextViewText(R.id.item_emoji, p.emoji)
            rv.setColorInt(R.id.item_emoji, "setTextColor", fg.first, fg.second)
        }
        rv.setViewVisibility(R.id.item_label, if (p.showLabel) View.VISIBLE else View.GONE)
        rv.setTextViewText(R.id.item_label, p.name)
        rv.setColorInt(R.id.item_label, "setTextColor", fg.first, fg.second)

        val padDp = when (p.iconSize) { IconSize.SMALL -> 20; IconSize.LARGE -> 2; else -> 10 }
        val pad = (padDp * dp).roundToInt()
        rv.setViewPadding(R.id.item_content, pad, pad, pad, pad)

        val state = Loc.str(ctx, if (active) R.string.state_on else R.string.state_off)
        rv.setFloat(R.id.item_root, "setAlpha", if (dim) 0.35f else 1f)
        rv.setContentDescription(R.id.item_root, p.name + ": " + state)
        if (tap != null) rv.setOnClickPendingIntent(R.id.item_root, tap)
    }

    /** Toggle: one or more profiles. Each tap goes to the next one; after the last one it turns off. */
    private fun buildToggle(ctx: Context, id: Int): RemoteViews {
        val list = Store.widgetProfiles(ctx, id)?.mapNotNull { Store.profile(ctx, it) }
        if (list.isNullOrEmpty()) return placeholder(ctx, id)
        val activeId = Store.state(ctx).activeId
        val on = list.firstOrNull { it.id == activeId }
        val rv = RemoteViews(ctx.packageName, R.layout.widget_toggle)
        bindItem(ctx, rv, on ?: list.first(), on != null, cycleIntent(ctx, id))
        return rv
    }

    fun cycleIntent(ctx: Context, widgetId: Int): PendingIntent {
        val i = Intent(ctx, TapActivity::class.java)
            .setData(Uri.parse("correctmode://cycle/$widgetId"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun buildSwitch(ctx: Context, mgr: AppWidgetManager, id: Int): RemoteViews {
        val list = Store.widgetProfiles(ctx, id)?.mapNotNull { Store.profile(ctx, it) }
        if (list.isNullOrEmpty()) return placeholder(ctx, id)
        val o = mgr.getAppWidgetOptions(id)
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val vertical = h > w * 1.3
        val rv = RemoteViews(ctx.packageName, if (vertical) R.layout.widget_switch_v else R.layout.widget_switch_h)
        val activeId = Store.state(ctx).activeId
        val oneIsOn = list.any { it.id == activeId }
        // A RemoteViews update is applied on top of the view that is already on screen: without this the
        // buttons are appended again on every update (they multiplied until the launcher redrew).
        rv.removeAllViews(R.id.switch_container)
        for (p in list) {
            val item = RemoteViews(ctx.packageName, if (vertical) R.layout.widget_item_v else R.layout.widget_item_h)
            // while one of this widget's profiles is on, all the others are greyed out
            bindItem(ctx, item, p, activeId == p.id, tapIntent(ctx, p.id), dim = oneIsOn && activeId != p.id)
            rv.addView(R.id.switch_container, item)
        }
        return rv
    }

    /** Not configured yet (or its profiles were deleted): a visible, tappable box, never a blank one. */
    private fun placeholder(ctx: Context, id: Int): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_toggle)
        rv.setImageViewResource(R.id.item_bg, R.drawable.shape_round_l)
        rv.setColorInt(R.id.item_bg, "setColorFilter", BG_OFF.first, BG_OFF.second)
        rv.setTextViewText(R.id.item_emoji, "⚙")
        rv.setColorInt(R.id.item_emoji, "setTextColor", FG_OFF.first, FG_OFF.second)
        rv.setViewVisibility(R.id.item_image, View.GONE)
        rv.setViewVisibility(R.id.item_icon, View.GONE)
        rv.setViewVisibility(R.id.item_emoji, View.VISIBLE)
        rv.setViewVisibility(R.id.item_label, View.GONE)
        rv.setContentDescription(R.id.item_root, Loc.str(ctx, R.string.widget_setup))
        rv.setOnClickPendingIntent(R.id.item_root, configIntent(ctx, id))
        return rv
    }

    /** Preview for the editor: the same RemoteViews code path as the real widget. */
    fun preview(ctx: Context, p: Profile, active: Boolean, sizeDp: Int): View {
        val holder = FrameLayout(ctx)
        val rv = RemoteViews(ctx.packageName, R.layout.widget_toggle)
        bindItem(ctx, rv, p, active, null)
        val px = (sizeDp * ctx.resources.displayMetrics.density).roundToInt()
        holder.addView(rv.apply(ctx, holder), FrameLayout.LayoutParams(px, px))
        return holder
    }
}

open class BaseWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) Widgets.update(context, appWidgetManager, id)
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        Widgets.update(context, mgr, id)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // The providers are exported (the launcher needs that), so another app could send a fake "deleted".
        // A real deletion has already removed the id from the system, so ids that still exist are ignored.
        val alive = try {
            val mgr = AppWidgetManager.getInstance(context)
            (mgr.getAppWidgetIds(ComponentName(context, ToggleWidgetProvider::class.java)) +
                mgr.getAppWidgetIds(ComponentName(context, SwitchWidgetProvider::class.java))).toSet()
        } catch (e: Exception) { emptySet() }
        Store.removeWidgets(context, appWidgetIds.filter { it !in alive }.toIntArray())
    }
}

/** 1x1, resizable: tap = turn this profile on / off. */
class ToggleWidgetProvider : BaseWidgetProvider()

/** 1xN, resizable: one button per chosen profile; tap = switch to it, tap the active one = release. */
class SwitchWidgetProvider : BaseWidgetProvider()
