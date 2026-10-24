package com.andrerinas.openheadunit.connection.usb

import com.andrerinas.openheadunit.aap.AapTransport.HandshakeFailure
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy
import com.andrerinas.openheadunit.connection.ConnectionAdmission

/**
 * Bounds attempts to recover an accessory after a failed handshake: re-switch, then USB reset.
 * Neither command proves that the peer has discarded its GAL/TLS session. In particular, a
 * USB-to-Wi-Fi relay can reopen its gadget while retaining the phone-side TCP connection.
 */
object StaleAccessoryRecoveryPolicy {
    enum class Step { NONE, RESWITCH, USB_RESET, GIVE_UP }

    enum class Observation { WAITING, REENUMERATED, NO_CHANGE, LEFT_THE_BUS }

    enum class FollowUp { RECONNECT_ON_ATTACH, RETRY_HANDSHAKE, STOP }

    const val DETACH_WAIT_MS = 5_000L
    const val REATTACH_WAIT_MS = 10_000L
    const val MAX_STEPS = 2

    /**
     * A failed control transfer/reset does not prove that the device ignored it. Observe the
     * bus after either result before opening another connection. Once removal was seen, a
     * reappearance at the same bus address is a new attachment, not an unchanged connection.
     */
    class BusObservation : ConnectionAdmission {
        private var sawDetach = false
        val hasDetached: Boolean get() = synchronized(this) { sawDetach }

        // Main can receive a detach/attach pair while IO is inside a control transfer or
        // between polls. Keep that evidence even if the next device list looks unchanged.
        @Synchronized fun noteDetach() { sawDetach = true }

        // Only short state publication runs here. USB I/O and teardown stay outside this
        // monitor so the detach listener can invalidate an in-progress open immediately.
        @Synchronized override fun run(action: () -> Unit): Boolean {
            if (sawDetach) return false
            action()
            return true
        }

        fun update(originalPresent: Boolean, candidatePresent: Boolean, elapsedMs: Long): Observation {
            if (!originalPresent) noteDetach()
            return observe(hasDetached, candidatePresent, elapsedMs)
        }
    }

    /**
     * Steps that still count. A half-done ladder older than the USB budget starts over, but a
     * finished one holds until the device it gave up on leaves the bus, so a stale phone is reset once.
     */
    fun stepsInEpisode(
        stepsTaken: Int,
        msSinceLastStep: Long,
        gaveUpOn: String?,
        accessoryNow: String?,
    ): Int = when {
        gaveUpOn != null -> if (gaveUpOn == accessoryNow) stepsTaken else 0
        msSinceLastStep >= ConnectionPriorityPolicy.USB_EPISODE_BUDGET_MS -> 0
        else -> stepsTaken
    }

    fun decide(
        failure: HandshakeFailure,
        inAccessoryMode: Boolean,
        cancelledByUser: Boolean,
        stepsInEpisode: Int,
    ): Step {
        val stale = failure == HandshakeFailure.TRANSPORT_ERROR || failure == HandshakeFailure.SSL
        if (!stale || !inAccessoryMode || cancelledByUser) return Step.NONE
        return when (stepsInEpisode) {
            0 -> Step.RESWITCH
            1 -> Step.USB_RESET
            else -> Step.GIVE_UP
        }
    }

    fun observe(detached: Boolean, reattached: Boolean, elapsedMs: Long): Observation = when {
        detached && reattached -> Observation.REENUMERATED
        detached ->
            if (elapsedMs >= DETACH_WAIT_MS + REATTACH_WAIT_MS) Observation.LEFT_THE_BUS else Observation.WAITING
        elapsedMs >= DETACH_WAIT_MS -> Observation.NO_CHANGE
        else -> Observation.WAITING
    }

    fun afterObservation(observation: Observation): FollowUp = when (observation) {
        Observation.REENUMERATED -> FollowUp.RECONNECT_ON_ATTACH
        Observation.NO_CHANGE -> FollowUp.RETRY_HANDSHAKE
        else -> FollowUp.STOP
    }
}
