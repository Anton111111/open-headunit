package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * Whether a wake stood down by [BluetoothWakePolicy.WakeReason.TARGET_CONNECTED] has waited long
 * enough to go out anyway.
 *
 * Android Auto starts wireless setup from a Bluetooth *event*, not from a connected state:
 * `WifiBluetoothReceiver` filters `ACL_CONNECTED` and the HFP and A2DP
 * `CONNECTION_STATE_CHANGED`, and its own tokens are `WIRELESS_SETUP_SHARED_HFP_CONNECTING` and
 * siblings. A radio that auto-connects hands-free and holds it across a session and past its end
 * raises no further event, so the phone may not re-trigger and the stand-down can be permanent.
 *
 * The poke is what produces that event, at the measured cost of the phone's hands-free slot, so it
 * is budgeted rather than freed: only for a pairing that has already run Android Auto on this unit,
 * only [MAX_ESCALATED_WAKES] times per arming, and only while [NativeAaWakeDamagePolicy] has not
 * measured this unit losing the link for good.
 */
object HandsFreeWakeEscalationPolicy {

    /** Escalated wakes allowed between one [NativeAaHandshakeManager] start and the next. */
    const val MAX_ESCALATED_WAKES = 2

    /** Minimum gap between them, so a phone that is simply slow is not poked twice over. */
    const val ESCALATION_COOLDOWN_MS = 60_000L

    /**
     * How long the stand-down runs before the first escalation. Longer than the ~45 s the phone
     * takes to re-trigger on its own where anything is going to, so an ordinary reconnect is never
     * disturbed.
     */
    const val ESCALATE_AFTER_MS = 90_000L

    /**
     * Whether to wake [device] despite the hands-free link.
     *
     * @param unitAllowsWake what [NativeAaWakeDamagePolicy] made of this unit's own link last time.
     * @param reason why [BluetoothWakePolicy.wakeDecision] refused. Only TARGET_CONNECTED escalates:
     *   TARGET_UNREADABLE refused because it could not tell whose link it is, and a guess is not a
     *   reason to drop somebody's call.
     * @param phoneEverOpenedAaChannel whether this pairing has ever run Android Auto on this unit. A
     *   phone paired only for calls is never disturbed.
     * @param sessionInProgress a session up, handshaking or settling; nothing touches the radio then.
     * @param standDownSinceMs when this device's stand-down began, or zero if it has not.
     * @param lastEscalationMs when the last escalated wake went out, or zero if none has.
     */
    fun shouldEscalate(
        unitAllowsWake: Boolean,
        reason: BluetoothWakePolicy.WakeReason,
        phoneEverOpenedAaChannel: Boolean,
        sessionInProgress: Boolean,
        standDownSinceMs: Long,
        lastEscalationMs: Long,
        escalationsUsed: Int,
        now: Long,
    ): Boolean {
        if (!unitAllowsWake) return false
        if (reason != BluetoothWakePolicy.WakeReason.TARGET_CONNECTED) return false
        if (!phoneEverOpenedAaChannel) return false
        if (sessionInProgress) return false
        if (escalationsUsed >= MAX_ESCALATED_WAKES) return false
        if (standDownSinceMs <= 0L || now - standDownSinceMs < ESCALATE_AFTER_MS) return false
        if (lastEscalationMs > 0L && now - lastEscalationMs < ESCALATION_COOLDOWN_MS) return false
        return true
    }

    /**
     * Whether this pairing has run Android Auto on this unit, in this process or an earlier one.
     * [acceptedThisProcess] is lost on every restart, and a radio that auto-connects hands-free at
     * boot stands the wake down before anything can set it, so a rebooted unit could never escalate.
     * The stored MAC is the durable half, written when a handshake completes.
     */
    fun pairingHasRunAaHere(
        acceptedThisProcess: Boolean,
        targetMac: String?,
        lastConnectedNativeMac: String,
    ): Boolean {
        if (acceptedThisProcess) return true
        val target = targetMac?.trim().orEmpty()
        if (target.isEmpty() || lastConnectedNativeMac.isEmpty()) return false
        return target.equals(lastConnectedNativeMac.trim(), ignoreCase = true)
    }

    /** Unanswered pokes allowed after an escalated wake before the loop leaves the device alone. */
    const val POKES_AFTER_ESCALATION = 4

    /**
     * Whether an escalated wake has cost the hands-free link for nothing. Taking the slot stops the
     * guard refusing, so the ordinary loop re-takes it every pass and the link never comes back:
     * measured at over five minutes down on a phone that never opened the channel.
     */
    fun escalationSpent(escalationsUsed: Int, pokesSinceLastAccept: Int): Boolean =
        escalationsUsed > 0 && pokesSinceLastAccept >= POKES_AFTER_ESCALATION
}
