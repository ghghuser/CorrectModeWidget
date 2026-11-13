package io.github.ghghuser.correctmode

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** Auto release alarm. Not exported: only our own PendingIntent can reach it. */
class ExpireReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Scheduler.ACTION_EXPIRE) return
        Engine.reconcile(context)
    }
}

/** Notification "Release" button. Not exported. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Notifier.ACTION_RELEASE) return
        Engine.release(context, Reason.USER)
    }
}

/**
 * Exported only because the system must be able to deliver BOOT_COMPLETED / MY_PACKAGE_REPLACED.
 * Both are protected broadcasts (apps cannot send them) and the action is checked again here.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Engine.reconcile(context)
        }
    }
}

/**
 * Bluetooth connect / disconnect. Only does anything while a profile is on. The broadcast is just a
 * trigger: the answer comes from the audio system (see Bt). A2DP appears a little after the ACL link,
 * so it looks again after 3 s and 7 s (the receiver is kept alive for that with goAsync).
 * ACL_CONNECTED / ACL_DISCONNECTED are protected broadcasts, so other apps cannot fake them.
 */
class BtReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action
        if (a != BluetoothDevice.ACTION_ACL_CONNECTED && a != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        if (Store.state(context).activeId == null) return
        val app = context.applicationContext
        val pending = goAsync()
        val h = Handler(Looper.getMainLooper())
        // On a disconnect, leave that device out right away (the audio list may still show it for a moment).
        val gone = if (a == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
            try {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)?.address
            } catch (e: Exception) { null }
        } else null
        try { Engine.onBtChanged(app, gone) } catch (e: Exception) { }
        h.postDelayed({ try { Engine.onBtChanged(app) } catch (e: Exception) { } }, 3000)
        h.postDelayed({
            try { Engine.onBtChanged(app) } catch (e: Exception) { }
            pending.finish()
        }, 7000)
    }
}
