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
        if (custom != null && custom != ColorMark.TRANSPARENT) (custom or 0xFF000000.toInt()).let { it to it } else def

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
            val rv = if (cls == SwitchWidgetProvider::class.java.name) buildSwitch(ctx, mgr, id) else buildToggle(ctx, mgr, id)
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

    /** Which sides of a button are the OUTER sides of its widget (only those get the outer margin in a joined set). */
    class Edges(val left: Boolean, val top: Boolean, val right: Boolean, val bottom: Boolean)

    private fun withAlpha(color: Int, percent: Int): Int =
        if (percent >= 100) color else (((color ushr 24) * percent / 100) shl 24) or (color and 0xFFFFFF)

    /** Size of the widget area in dp for the current orientation (launchers report a range). null = unknown. */
    private fun boxDp(ctx: Context, mgr: AppWidgetManager, id: Int): Pair<Float, Float>? {
        val o = mgr.getAppWidgetOptions(id)
        val land = ctx.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val w = o.getInt(if (land) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val h = o.getInt(if (land) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        return if (w > 0 && h > 0) w.toFloat() to h.toFloat() else null
    }

    /** Shared by the real widgets and the preview in the editor. */
    /**
     * [joinShape]: set for the buttons of a switch widget. The widget is one set, so the backgrounds touch
     * (no gap) and only the two outer ends are rounded.
     * [boxDp]: size of the widget area; with [Profile.keepRatio] the button becomes a perfect square / circle in it.
     */
    fun bindItem(
        ctx: Context, rv: RemoteViews, p: Profile, active: Boolean, tap: PendingIntent?, dim: Boolean = false,
        joinShape: Int? = null, noBg: Boolean = false, boxDp: Pair<Float, Float>? = null, edges: Edges? = null,
    ) {
        val dp = ctx.resources.displayMetrics.density
        val bg0 = if (active) pair(p.bgOn, BG_ON) else pair(p.bgOff, BG_OFF)
        val a = if (active) p.bgOnA else p.bgOffA
        val bg = withAlpha(bg0.first, a) to withAlpha(bg0.second, a)
        val fg = if (active) pair(p.fgOn, FG_ON) else pair(p.fgOff, FG_OFF)
        // "no background": the button area stays tappable, only the coloured shape is not drawn
        rv.setViewVisibility(R.id.item_bg, if (noBg || a == 0 || (if (active) p.bgOn else p.bgOff) == ColorMark.TRANSPARENT) View.GONE else View.VISIBLE)
        rv.setImageViewResource(R.id.item_bg, joinShape ?: shapeRes(p.shape))
        rv.setColorInt(R.id.item_bg, "setColorFilter", bg.first, bg.second)

        // margins around the button; with "keep ratio" the rest of the room is shared out so the shape stays square
        fun px(v: Float) = (v * dp).roundToInt()
        if (joinShape != null) {
            val e = edges ?: Edges(true, true, true, true)
            rv.setViewPadding(R.id.item_root, if (e.left) px(p.mL.toFloat()) else 0, if (e.top) px(p.mT.toFloat()) else 0,
                if (e.right) px(p.mR.toFloat()) else 0, if (e.bottom) px(p.mB.toFloat()) else 0)
        } else {
            var l = p.mL.toFloat(); var t = p.mT.toFloat(); var r = p.mR.toFloat(); var b = p.mB.toFloat()
            if (p.keepRatio && boxDp != null) {
                val aw = boxDp.first - l - r
                val ah = boxDp.second - t - b
                val side = minOf(aw, ah).coerceAtLeast(16f)
                val ex = ((aw - side) / 2f).coerceAtLeast(0f)
                val ey = ((ah - side) / 2f).coerceAtLeast(0f)
                l += ex; r += ex; t += ey; b += ey
            }
            rv.setViewPadding(R.id.item_root, px(l), px(t), px(r), px(b))
        }

        // an own icon for the "on" state (built-in icon or emoji); the picture is shared
        val useOn = active && p.onSep
        val bmp = if (useOn) null else p.imageFile?.let { BitmapFactory.decodeFile(Store.imageFile(ctx, it).path) }
        val iconRes = FlatIcons.res(if (useOn) p.iconKeyOn else p.iconKey)
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
            rv.setTextViewText(R.id.item_emoji, if (useOn) p.emojiOn else p.emoji)
            rv.setColorInt(R.id.item_emoji, "setTextColor", fg.first, fg.second)
        }
        rv.setViewVisibility(R.id.item_label, if (p.showLabel) View.VISIBLE else View.GONE)
        rv.setTextViewText(R.id.item_label, p.name)
        rv.setColorInt(R.id.item_label, "setTextColor", fg.first, fg.second)

        val padDp = when (p.iconSize) { IconSize.SMALL -> 20; IconSize.LARGE -> 6; else -> 10 }
        val pad = (padDp * dp).roundToInt()
        rv.setViewPadding(R.id.item_content, pad, pad, pad, pad)

        // greyed while the app is busy with the last tap (taps are ignored for a moment, see TapActivity)
        val busy = tap != null && System.currentTimeMillis() < Store.busyUntil(ctx)
        val state = Loc.str(ctx, if (active) R.string.state_on else R.string.state_off)
        rv.setFloat(R.id.item_root, "setAlpha", if (dim || busy) 0.35f else 1f)
        rv.setContentDescription(R.id.item_root, p.name + ": " + state)
        if (tap != null) rv.setOnClickPendingIntent(R.id.item_root, tap)
    }

    /** Toggle: one or more profiles. Each tap goes to the next one; after the last one it turns off. */
    private fun buildToggle(ctx: Context, mgr: AppWidgetManager, id: Int): RemoteViews {
        val list = Store.widgetProfiles(ctx, id)?.mapNotNull { Store.profile(ctx, it) }
        if (list.isNullOrEmpty()) return placeholder(ctx, id)
        val activeId = Store.state(ctx).activeId
        val on = list.firstOrNull { it.id == activeId }
        val rv = RemoteViews(ctx.packageName, R.layout.widget_toggle)
        bindItem(ctx, rv, on ?: list.first(), on != null, cycleIntent(ctx, id), boxDp = boxDp(ctx, mgr, id))
        return rv
    }

    fun cycleIntent(ctx: Context, widgetId: Int): PendingIntent {
        val i = Intent(ctx, TapActivity::class.java)
            .setData(Uri.parse("correctmode://cycle/$widgetId"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** With this much height (dp) and at least 3 buttons the switch widget wraps into two rows. */
    private const val TWO_ROW_MIN_DP = 105f

    private fun buildSwitch(ctx: Context, mgr: AppWidgetManager, id: Int): RemoteViews {
        val list = Store.widgetProfiles(ctx, id)?.mapNotNull { Store.profile(ctx, it) }
        if (list.isNullOrEmpty()) return placeholder(ctx, id)
        val o = mgr.getAppWidgetOptions(id)
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        val vertical = h > w * 1.3
        val box = boxDp(ctx, mgr, id)
        // room in height: wrap into two rows, the upper one gets the extra button, the lower row is centred
        val twoRows = !vertical && list.size >= 3 && (box?.second ?: h.toFloat()) >= TWO_ROW_MIN_DP
        val rows: List<List<Profile>> = if (twoRows) { val top = (list.size + 1) / 2; listOf(list.take(top), list.drop(top)) } else listOf(list)
        val rv = RemoteViews(ctx.packageName, when { vertical -> R.layout.widget_switch_v; twoRows -> R.layout.widget_switch_rows; else -> R.layout.widget_switch_h })
        val activeId = Store.state(ctx).activeId
        val oneIsOn = list.any { it.id == activeId }
        // A RemoteViews update is applied on top of the view that is already on screen: without this the
        // buttons are appended again on every update (they multiplied until the launcher redrew).
        rv.removeAllViews(R.id.switch_container)
        // one background behind the whole set (colour + opacity); 0% = none. Set every time: an update is applied on top of the old view.
        val look = Store.widgetLook(ctx, id)
        if (look.alpha > 0) {
            val c = look.color or 0xFF000000.toInt()
            rv.setViewVisibility(R.id.switch_bg, View.VISIBLE)
            rv.setColorInt(R.id.switch_bg, "setColorFilter", c, c)
            rv.setInt(R.id.switch_bg, "setImageAlpha", (look.alpha * 255 / 100).coerceIn(0, 255))
            val pad = (4 * ctx.resources.displayMetrics.density).roundToInt()
            rv.setViewPadding(R.id.switch_container, pad, pad, pad, pad)
        } else {
            rv.setViewVisibility(R.id.switch_bg, View.GONE)
            rv.setViewPadding(R.id.switch_container, 0, 0, 0, 0)
        }
        for (row in rows) {
            val holder: RemoteViews
            val holderId: Int
            if (twoRows) {
                holder = RemoteViews(ctx.packageName, R.layout.widget_switch_row)
                holderId = R.id.switch_row
            } else { holder = rv; holderId = R.id.switch_container }
            // a shorter row is centred: spacers of half / whole button width on both sides
            val gap = rows[0].size - row.size
            fun spacers() {
                repeat(gap / 2) { holder.addView(holderId, RemoteViews(ctx.packageName, R.layout.widget_spacer)) }
                if (gap % 2 == 1) holder.addView(holderId, RemoteViews(ctx.packageName, R.layout.widget_spacer_half))
            }
            if (twoRows && gap > 0) spacers()
            for ((i, p) in row.withIndex()) {
                val item = RemoteViews(ctx.packageName, if (vertical) R.layout.widget_item_v else R.layout.widget_item_h)
                val join = when {
                    row.size == 1 -> null
                    i == 0 -> if (vertical) R.drawable.shape_join_v_start else R.drawable.shape_join_h_start
                    i == row.size - 1 -> if (vertical) R.drawable.shape_join_v_end else R.drawable.shape_join_h_end
                    else -> R.drawable.shape_join_h_mid
                }
                val edges = if (vertical) Edges(true, i == 0, true, i == row.size - 1) else Edges(i == 0, true, i == row.size - 1, true)
                // while one of this widget's profiles is on, all the others are greyed out
                bindItem(ctx, item, p, activeId == p.id, tapIntent(ctx, p.id), dim = oneIsOn && activeId != p.id,
                    joinShape = join, noBg = !look.items, boxDp = if (row.size == 1 && !twoRows) box else null, edges = edges)
                holder.addView(holderId, item)
            }
            if (twoRows && gap > 0) spacers()
            if (twoRows) rv.addView(R.id.switch_container, holder)
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
