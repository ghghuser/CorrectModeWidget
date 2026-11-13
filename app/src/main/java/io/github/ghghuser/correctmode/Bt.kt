package io.github.ghghuser.correctmode

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * "Is Bluetooth audio connected?" is answered from the audio system's output devices (not from raw ACL
 * events), so a smart watch, keyboard or mouse connecting can never switch media on by accident.
 * The ACL broadcast is only the trigger to look again.
 */
object Bt {
    fun hasConnectPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * @param excludeAddress a device that has JUST disconnected. The audio system can still list it for a
     *   moment, which would keep "media allowed" on after the earphones are gone (media would then play
     *   from the phone speaker). Leaving it out makes the answer correct immediately.
     */
    fun audioConnected(ctx: Context, excludeAddress: String? = null): Boolean {
        // Without BLUETOOTH_CONNECT Android does not deliver the connect / disconnect broadcasts to this app.
        // "Media allowed" could then never be switched off again when the earphones are removed (the music
        // would play from the speaker). So without the permission the answer is always "not connected".
        if (!hasConnectPermission(ctx)) return false
        return try {
            val am = ctx.getSystemService(AudioManager::class.java)
            val earphoneOnly = Store.btMode(ctx) == BtMode.EARPHONE_ONLY
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { d ->
                isBtAudio(d.type) &&
                    !(excludeAddress != null && d.address.equals(excludeAddress, ignoreCase = true)) &&
                    (!earphoneOnly || isEarphone(ctx, d))
            }
        } catch (e: Exception) {
            false // unknown = "not connected": the quiet answer
        }
    }

    private fun isBtAudio(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID -> true
        else -> false
    }

    /** Headset / headphones only. Car kits, speakers and unknown devices are NOT earphones. */
    private fun isEarphone(ctx: Context, d: AudioDeviceInfo): Boolean {
        if (d.type == AudioDeviceInfo.TYPE_HEARING_AID || d.type == AudioDeviceInfo.TYPE_BLE_HEADSET) return true
        if (d.type == AudioDeviceInfo.TYPE_BLE_SPEAKER) return false
        if (!hasConnectPermission(ctx)) return false
        return try {
            val adapter: BluetoothAdapter = ctx.getSystemService(BluetoothManager::class.java).adapter ?: return false
            val dev = adapter.getRemoteDevice(d.address)
            when (dev.bluetoothClass?.deviceClass) {
                BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
                BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES -> true
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }
}
