package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

import com.andrerinas.openheadunit.utils.bluetooth.BluetoothPeerInventory

/** One carrier attempt per fresh manufacturer AA event, never per cached UUID snapshot. */
internal class HctAdmission {
    data class Ticket(val generation: Long, val reportedAt: Long)

    private var generation = 0L
    private var ticket: Ticket? = null

    /**
     * FSCBW123Model publishes the daemon's `AA` event as a standalone phone_uuids extra.
     * Its general status report can repeat that cached list alongside HFP/address fields,
     * even after the AA link has gone. Only the standalone event can admit a new attempt.
     * SPC1/2/3 all become spp_state=2; that field alone never proves an AA connection.
     */
    @Synchronized
    fun report(
        uuids: List<String>?,
        connection: Int?,
        spp: Int?,
        now: Long,
        onlyUuidEvent: Boolean
    ): Ticket? {
        if ((connection != null && connection !in 1..5) ||
            (spp != null && spp != 2) ||
            (uuids != null && AA_MARKER !in uuids)) {
            invalidate()
            return null
        }
        if (onlyUuidEvent && uuids != null && AA_MARKER in uuids && ticket == null) {
            return Ticket(generation, now).also { ticket = it }
        }
        return null
    }

    @Synchronized
    fun invalidate() {
        generation++
        ticket = null
    }

    @Synchronized
    fun epoch(): Long = generation

    @Synchronized
    fun current(value: Ticket): Boolean = ticket == value

    fun fresh(value: Ticket, now: Long): Boolean =
        current(value) && now - value.reportedAt in 0..FRESH_REPORT_MS

    companion object {
        const val AA_MARKER = "WIRELESS-ANDROID-AUTO-AA"
        const val FRESH_REPORT_MS = 3_000L

        /** Binder supplies a numeric 48-bit peer address, not an Android adapter placeholder. */
        fun address(value: Long): String? = if (value in 1 until 0xffffffffffffL) {
            BluetoothPeerInventory.normalizeAddress(value.toString(16).padStart(12, '0'))
        } else null

        /**
         * JH uses hex12, whereas settingsaccess=3 uses a decimal long string. Compare both
         * interpretations only against an independently obtained Binder address. Never infer
         * a new peer from an ambiguous all-digit broadcast value.
         */
        fun matchesAddress(value: String?, canonical: String): Boolean {
            if (value == null) return false
            return BluetoothPeerInventory.normalizeAddress(value) == canonical ||
                value.toLongOrNull()?.let { address(it) == canonical } == true
        }
    }
}
