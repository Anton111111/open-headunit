package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

/**
 * Coordinates explicit OEM reconnect requests with the incoming AA handoff for one carrier.
 * settingsaccess=5 has no completion reply. In the inspected HCT FSC implementation AT-CC
 * can stop a connector or disconnect HFP before scheduling another connection. Public HFP=0
 * therefore does not prove that the native connector is idle. Neither a local timeout nor
 * another button press may replace a request already admitted for IPC.
 *
 * In the related QCM6125 A13 fscsdkbw123, hfp_sink_set_state() can schedule reconnects
 * after entering standby. FSCBW123Model exposes S1 as disconnected, without that pending
 * work. Its S3/connected report is not AA readiness either: aap_set_state() separately
 * establishes the AA profile and local socket. Only matching WPP clears our submitted
 * attempt; HFP reports and elapsed time do not establish the end of the native operation.
 */
internal class HctWakeAttempts(private val reconnect: HctPendingReconnect = HctPendingReconnect()) {
    data class Ticket(val id: Long, val target: String)
    enum class Preparation { READY, SUPERSEDED, NOT_READY }
    enum class Hold { CLOSED, OPENING, AWAITING_OEM_RESULT, FRESH_AA, DUPLICATE_SELECTION, QUEUE_CLOSED }
    private var queued: Ticket? = null
    private var openingReconnect: HctPendingReconnect.Pending? = null
    private val submitted: Ticket? get() = reconnect.current()?.ticket
    private var freshUntil = Long.MIN_VALUE
    private var opening = false
    private var closed = false

    @Synchronized
    fun select(target: String, now: Long): Ticket? {
        if (hold(target, now) != null) return null
        return reconnect.ticket(target).also { queued = it }
    }

    private fun hold(target: String, now: Long): Hold? = when {
        closed -> Hold.CLOSED
        opening -> Hold.OPENING
        submitted != null -> Hold.AWAITING_OEM_RESULT
        now <= freshUntil -> Hold.FRESH_AA
        queued?.target == target -> Hold.DUPLICATE_SELECTION
        else -> null
    }

    /** Selection and nonblocking channel publication must share the same ordering. */
    @Synchronized
    fun enqueue(target: String, now: Long, publish: (Ticket) -> Boolean): Hold? {
        hold(target, now)?.let { return it }
        val ticket = select(target, now)!!
        if (!publish(ticket)) {
            finishUnsent(ticket)
            return Hold.QUEUE_CLOSED
        }
        return null
    }

    /**
     * Commit the driver's target under the same lock as wake admission. In particular, a
     * submitted reconnect for A must not leave the receive gate targeting B. A fresh AA
     * event needs no reconnect, but a selection may still release the phone picker gate.
     * The callbacks only update local fields or publish to a nonblocking channel.
     */
    @Synchronized
    fun selectAndEnqueue(
        target: String,
        now: Long,
        selected: () -> Unit,
        publish: (Ticket) -> Boolean
    ): Hold? {
        val held = hold(target, now)
        when (held) {
            null -> {
                val result = enqueue(target, now, publish)
                if (result == null) selected()
                return result
            }
            Hold.FRESH_AA, Hold.DUPLICATE_SELECTION -> selected()
            Hold.AWAITING_OEM_RESULT -> if (submitted?.target == target) selected()
            else -> Unit
        }
        return held
    }

    /** Wait for the producer's selection commit before consulting the prompt/receive gate. */
    @Synchronized
    fun prepare(ticket: Ticket, mayServe: () -> Boolean): Preparation = when {
        closed || queued != ticket -> Preparation.SUPERSEDED
        !mayServe() -> Preparation.NOT_READY
        else -> Preparation.READY
    }

    /** Preserve the reason if local state changes during the preceding Binder reads. */
    @Synchronized
    fun claim(ticket: Ticket, now: Long, mayServe: () -> Boolean): HctWakePolicy.Result =
        when (prepare(ticket, mayServe)) {
            Preparation.SUPERSEDED -> HctWakePolicy.Result.STATE_CHANGED
            Preparation.NOT_READY -> HctWakePolicy.Result.LOCAL_NOT_READY
            Preparation.READY -> if (claim(ticket, now)) HctWakePolicy.Result.REQUESTED
                else HctWakePolicy.Result.STATE_CHANGED
        }

    @Synchronized
    fun pendingAge(now: Long): Long? = reconnect.current()?.let { now - it.submittedAt }

    /** Final local admission, after bounded status reads and immediately before the OEM IPC. */
    @Synchronized
    fun claim(ticket: Ticket, now: Long): Boolean {
        if (closed || opening || submitted != null || queued != ticket || now <= freshUntil) return false
        if (!reconnect.submit(ticket, now)) return false
        queued = null
        return true
    }

    @Synchronized
    fun finishUnsent(ticket: Ticket) {
        if (queued == ticket) queued = null
        // UNKNOWN_STATE may follow a successful sendBroadcast followed by a deadline check.
        // A submitted ticket remains outstanding even when its caller cannot obtain a result.
    }

    @Synchronized
    fun reported(now: Long) {
        queued = null
        freshUntil = now + HctAdmission.FRESH_REPORT_MS
    }

    @Synchronized
    fun beginOpening(): Boolean {
        if (closed || opening) return false
        queued = null
        opening = true
        openingReconnect = reconnect.current()
        return true
    }

    @Synchronized
    fun accepted(target: String) {
        if (!closed && opening) reconnect.accepted(openingReconnect, target)
    }

    @Synchronized
    fun endOpening() { opening = false; openingReconnect = null }

    @Synchronized
    fun outstanding(ticket: Ticket): Boolean = submitted == ticket

    @Synchronized
    fun waitingForResult(ticket: Ticket): Boolean = submitted == ticket && !opening && !closed

    @Synchronized
    fun close() { closed = true; queued = null }
}
