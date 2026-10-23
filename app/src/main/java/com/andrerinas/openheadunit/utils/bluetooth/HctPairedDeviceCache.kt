package com.andrerinas.openheadunit.utils.bluetooth

import com.andrerinas.openheadunit.utils.bluetooth.BluetoothPeerInventory.Entry
import com.andrerinas.openheadunit.utils.bluetooth.BluetoothPeerInventory.Reading
import com.andrerinas.openheadunit.utils.bluetooth.BluetoothPeerInventory.Source
import com.andrerinas.openheadunit.utils.bluetooth.BluetoothPeerInventory.Status

/** Decoder for the manufacturer's stored pairing list; never reads the scan-results deviceList. */
internal object HctPairedDeviceCache {
    const val SETTING = "MTC.BT.matchList"
    private const val MAX_CHARS = 65_536
    private const val MAX_ROWS = 256

    fun read(getSetting: () -> String?): Reading = try {
        parse(getSetting())
    } catch (_: Exception) {
        // Permission denial/provider failure means unreadable, not "the user unpaired everything".
        Reading(Source.HCT_PAIR_CACHE, Status.UNAVAILABLE)
    }

    /**
     * HCTBlueToothManager's BlueToothService.saveList joins records with '\n'; the FSC
     * _reportMatchList/scanFound code compares substring(1, 13) for the peer MAC.
     *
     *     module prefix (1 char) | address (12 hex chars) | display name
     *
     * Layout traced in related HCT QCM6125 Android 13 firmware. The prefix is
     * module metadata, not a live HFP/AA state. Unknown layouts stay unsupported instead of
     * guessing an address or trying Binder transaction numbers from a different firmware.
     */
    fun parse(raw: String?): Reading {
        if (raw == null) return Reading(Source.HCT_PAIR_CACHE, Status.ABSENT)
        if (raw.length > MAX_CHARS) return Reading(Source.HCT_PAIR_CACHE, Status.MALFORMED)
        val rows = raw.lineSequence().filter { it.isNotEmpty() }.take(MAX_ROWS + 1).toList()
        if (rows.size > MAX_ROWS) return Reading(Source.HCT_PAIR_CACHE, Status.MALFORMED)
        var rejected = 0
        val entries = linkedMapOf<String, Entry>()
        for (row in rows) {
            val address = if (row.length >= 13 && row[0] in ' '..'~') {
                BluetoothPeerInventory.normalizeAddress(row.substring(1, 13))
            } else null
            if (address == null) {
                rejected++
            } else {
                if (address !in entries) entries[address] = Entry(address, row.substring(13).ifBlank { null })
            }
        }
        return Reading(
            Source.HCT_PAIR_CACHE,
            if (rows.isNotEmpty() && entries.isEmpty()) Status.MALFORMED else Status.READABLE,
            entries.values.toList(), rejected
        )
    }
}
