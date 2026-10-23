package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

import com.andrerinas.openheadunit.utils.bluetooth.BluetoothPeerInventory

/** Decides whether one explicitly selected, manufacturer-paired phone may be woken. */
internal object HctWakePolicy {
    enum class Result {
        REQUESTED, NO_TARGET, NOT_PAIRED, UNKNOWN_STATE, HFP_ALREADY_CONNECTED,
        HFP_BUSY, STATE_CHANGED, SERVICE_UNAVAILABLE, CLOSED
    }

    data class Snapshot(val hfp: Int, val peer: Long)

    /**
     * AT-CC is not an idempotent wake: the inspected HCT daemon disconnects an existing HFP
     * connection before reconnecting, including the same phone. Require two disconnected
     * readings around pairing lookup; never replace unknown state with disconnected.
     * This reduces a status race but cannot make separate OEM Binder calls atomic.
     */
    fun decide(
        target: String?,
        paired: Boolean?,
        before: Snapshot?,
        after: Snapshot?,
        sameGeneration: Boolean
    ): Result {
        if (target == null || BluetoothPeerInventory.normalizeAddress(target) == null) return Result.NO_TARGET
        if (paired != true) return Result.NOT_PAIRED
        if (before == null || after == null ||
            before.hfp !in 0..5 || after.hfp !in 0..5 ||
            before.peer !in 0..0xffffffffffffL || after.peer !in 0..0xffffffffffffL) {
            return Result.UNKNOWN_STATE
        }
        if (before.hfp >= 2 || after.hfp >= 2) return Result.HFP_BUSY
        if (before.hfp == 1 || after.hfp == 1) return Result.HFP_ALREADY_CONNECTED
        if (!sameGeneration || before.peer != after.peer) return Result.STATE_CHANGED
        return Result.REQUESTED
    }
}
