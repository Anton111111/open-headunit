package com.andrerinas.openheadunit.utils.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.provider.Settings
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.utils.bluetooth.BluetoothPeerInventory.Entry
import com.andrerinas.openheadunit.utils.bluetooth.BluetoothPeerInventory.Reading
import com.andrerinas.openheadunit.utils.bluetooth.BluetoothPeerInventory.Source
import com.andrerinas.openheadunit.utils.bluetooth.BluetoothPeerInventory.Status

/** Read-only discovery shared by driver selection, settings and the Native AA wake loop. */
object BluetoothPeerDiscovery {
    data class Snapshot(val androidDevices: List<BluetoothDevice>, val inventory: BluetoothPeerInventory)

    /**
     * No model-name allowlist, radio initialization, socket probe or background polling.
     * The key's actual availability is the capability check. A missing custom setting is normal
     * on Android; vendor provider restrictions are isolated from the standard Bluetooth result.
     * The inspected HCT Android 13 SettingsProvider allows reads of this undeclared custom key;
     * reading it does not require WRITE_SETTINGS. Other firmware can still refuse the read.
     *
     * Read again when the user opens the picker or a wake round starts: this is stored pairing
     * history with no source timestamp, so neither a retained cache nor read time proves presence.
     */
    @SuppressLint("MissingPermission")
    fun read(context: Context): Snapshot {
        var status = Status.UNAVAILABLE
        val devices = try {
            val adapter = BluetoothHelper.getBluetoothAdapter(context)
            if (adapter?.isEnabled == true) {
                val bonded = adapter.bondedDevices
                if (bonded != null) status = Status.READABLE
                bonded?.toList() ?: emptyList()
            } else emptyList()
        } catch (_: Exception) { emptyList() }
        val entries = devices.mapNotNull { device ->
            try {
                Entry(device.address, try { device.name } catch (_: Exception) { null })
            } catch (_: Exception) { null }
        }
        return Snapshot(devices, BluetoothPeerInventory(listOf(
            Reading(Source.ANDROID, status, entries),
            manufacturerReading(context)
        )))
    }

    fun manufacturerInventory(context: Context): BluetoothPeerInventory =
        BluetoothPeerInventory(listOf(manufacturerReading(context)))

    private fun manufacturerReading(context: Context): Reading =
        HctPairedDeviceCache.read { Settings.System.getString(context.contentResolver, HctPairedDeviceCache.SETTING) }
}
