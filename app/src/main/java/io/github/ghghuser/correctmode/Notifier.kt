package io.github.ghghuser.correctmode

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import java.text.DateFormat
import java.util.Date

/** Expiry alarm. Exact when the user allowed it, otherwise a (slightly late) inexact one. */
object Scheduler {
    const val ACTION_EXPIRE = "io.github.ghghuser.correctmode.EXPIRE"

    private fun pi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 1,
        Intent(ctx, ExpireReceiver::class.java).setAction(ACTION_EXPIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun canExact(ctx: Context): Boolean = ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun schedule(ctx: Context, at: Long) {
        if (at <= 0) { cancel(ctx); return }
        val am = ctx.getSystemService(AlarmManager::class.java)
        try {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx))
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(ctx))
        }
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx))
    }
}

/**
 * Every channel here is SILENT (no sound, no vibration, low importance), so the app itself can never
 * make a noise while a quiet profile is on.
 */
object Notifier {
    private const val CH_ACTIVE = "active"
    private const val CH_INFO = "info"
    private const val ID_ACTIVE = 1
    private const val ID_INFO = 2
    const val ACTION_RELEASE = "io.github.ghghuser.correctmode.RELEASE"
    const val ACTION_REPOST = "io.github.ghghuser.correctmode.REPOST"

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun ensureChannels(ctx: Context) {
        val c = Loc.wrap(ctx)
        val active = NotificationChannel(CH_ACTIVE, c.getString(R.string.channel_active), NotificationManager.IMPORTANCE_MIN)
        val info = NotificationChannel(CH_INFO, c.getString(R.string.channel_info), NotificationManager.IMPORTANCE_LOW)
        for (ch in listOf(active, info)) {
            ch.setSound(null, null)
            ch.enableVibration(false)
            ch.enableLights(false)
            ch.setShowBadge(false)
        }
        nm(ctx).createNotificationChannels(listOf(active, info))
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 2, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun showActive(ctx: Context, p: Profile, st: State) {
        try {
            ensureChannels(ctx)
            val c = Loc.wrap(ctx)
            val release = PendingIntent.getBroadcast(
                ctx, 3, Intent(ctx, ActionReceiver::class.java).setAction(ACTION_RELEASE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val repost = PendingIntent.getBroadcast(
                ctx, 4, Intent(ctx, ActionReceiver::class.java).setAction(ACTION_REPOST),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val until = if (st.expiresAt > 0) {
                c.getString(R.string.until_time, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(st.expiresAt)))
            } else c.getString(R.string.no_limit)
            val title = (if (p.imageFile == null) p.emoji + " " else "") + p.name
            val b = Notification.Builder(ctx, CH_ACTIVE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(until + " · " + Describe.summary(ctx, p))
                .setStyle(Notification.BigTextStyle().bigText(until + "\n" + Describe.detail(ctx, p)))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(openApp(ctx))
                .setDeleteIntent(repost)
                .addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat), c.getString(R.string.release), release).build())
            if (st.expiresAt > 0) {
                b.setWhen(st.expiresAt).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            }
            nm(ctx).notify(ID_ACTIVE, b.build())
        } catch (e: Exception) {
            // no notification permission etc. - the profile itself still works
        }
    }

    fun cancelActive(ctx: Context) { nm(ctx).cancel(ID_ACTIVE) }

    fun info(ctx: Context, text: String) {
        try {
            ensureChannels(ctx)
            val c = Loc.wrap(ctx)
            val n = Notification.Builder(ctx, CH_INFO)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(c.getString(R.string.app_name))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(ctx))
                .build()
            nm(ctx).notify(ID_INFO, n)
        } catch (e: Exception) {
        }
    }
}
