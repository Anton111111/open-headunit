package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

/**
 * Process-local record of OEM work already submitted. Closing a launcher, a Binder proxy
 * or goc_rfcom does not cancel native SDP/HFP work. Keep that uncertainty across carrier
 * recreation; do not turn a local restart into another settingsaccess=5 command.
 *
 * This holds no Android objects and is deliberately not persisted: after process death
 * there is no live operation identity to reconcile with subsequent callbacks.
 */
internal class HctPendingReconnect {
    data class Pending(val ticket: HctWakeAttempts.Ticket, val submittedAt: Long)
    private var serial = 0L
    private var pending: Pending? = null

    @Synchronized fun ticket(target: String) = HctWakeAttempts.Ticket(++serial, target)
    @Synchronized fun current(): Pending? = pending

    /**
     * Only a new explicit selection may replace an unconfirmed request after its observation
     * window. Expiry is a local retry limit, not proof that the manufacturer's connector ended.
     * Keep the old record until the replacement passes all status checks and is claimed for IPC.
     */
    @Synchronized fun submit(
        ticket: HctWakeAttempts.Ticket,
        now: Long,
        replacing: Pending? = null
    ): Boolean {
        if (pending != replacing || (replacing != null && !retryAllowed(replacing, now))) return false
        pending = Pending(ticket, now)
        return true
    }

    @Synchronized fun accepted(expected: Pending?, target: String) {
        if (expected != null && pending == expected && expected.ticket.target == target) pending = null
    }

    companion object {
        const val OBSERVATION_MS = 30_000L
        fun retryAllowed(pending: Pending, now: Long): Boolean =
            now >= pending.submittedAt && now - pending.submittedAt >= OBSERVATION_MS

        val process = HctPendingReconnect()
    }
}
