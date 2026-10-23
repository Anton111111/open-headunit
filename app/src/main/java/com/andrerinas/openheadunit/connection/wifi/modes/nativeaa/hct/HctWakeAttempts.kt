package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

/**
 * Coordinates explicit OEM reconnect requests with the incoming AA handoff for one carrier.
 * settingsaccess=5 has no completion reply. In the inspected HCT FSC implementation AT-CC
 * can stop a connector or disconnect HFP before scheduling another connection. Public HFP=0
 * therefore does not prove that the native connector is idle. A new explicit selection may
 * replace an unconfirmed request after 30 seconds, subject to fresh paired-phone/HFP/call
 * checks. The observation timer itself never resends a command.
 *
 * In the related QCM6125 A13 fscsdkbw123, hfp_sink_set_state() can schedule reconnects
 * after entering standby. FSCBW123Model exposes S1 as disconnected, without that pending
 * work. Its S3/connected report is not AA readiness either: aap_set_state() separately
 * establishes the AA profile and local socket. Matching WPP acknowledges a submitted
 * attempt; explicit replacement only releases its local selection lock, not native work.
 */
internal class HctWakeAttempts(private val reconnect: HctPendingReconnect = HctPendingReconnect()) {
    data class Ticket(val id: Long, val target: String)
    enum class Preparation { READY, SUPERSEDED, NOT_READY }
    enum class Hold { CLOSED, OPENING, AWAITING_OEM_RESULT, FRESH_AA, DUPLICATE_SELECTION, QUEUE_CLOSED }
    private var queued: Ticket? = null
    private var noticeGeneration = 0L
    private var lastSelection: Ticket? = null
    private var queuedAgainst: HctPendingReconnect.Pending? = null
    private var openingReconnect: HctPendingReconnect.Pending? = null
    private val submitted: Ticket? get() = reconnect.current()?.ticket
    private var freshUntil = Long.MIN_VALUE
    private var opening = false
    private var closed = false

    @Synchronized
    fun select(target: String, now: Long, allowReplacePending: Boolean = false): Ticket? {
        val pending = reconnect.current()
        if (hold(target, now, allowReplacePending, pending) != null) return null
        return reserve(target, pending)
    }

    private fun reserve(target: String, pending: HctPendingReconnect.Pending?): Ticket =
        reconnect.ticket(target).also {
            queued = it; queuedAgainst = pending; lastSelection = it; noticeGeneration++
        }

    private fun hold(
        target: String, now: Long, allowReplacePending: Boolean,
        pending: HctPendingReconnect.Pending? = reconnect.current()
    ): Hold? = when {
        closed -> Hold.CLOSED
        opening -> Hold.OPENING
        pending?.let { !allowReplacePending || !HctPendingReconnect.retryAllowed(it, now) } == true -> Hold.AWAITING_OEM_RESULT
        now <= freshUntil -> Hold.FRESH_AA
        queued?.target == target -> Hold.DUPLICATE_SELECTION
        else -> null
    }

    /** Selection and nonblocking channel publication must share the same ordering. */
    @Synchronized
    fun enqueue(target: String, now: Long, allowReplacePending: Boolean = false, publish: (Ticket) -> Boolean): Hold? {
        // Authorize and remember the same process-wide snapshot. Another carrier may
        // publish meanwhile; final claim must reject that change, not inherit its ticket.
        val pending = reconnect.current()
        hold(target, now, allowReplacePending, pending)?.let { return it }
        val previousSelection = lastSelection
        val previousGeneration = noticeGeneration
        val ticket = reserve(target, pending)
        if (!publish(ticket)) {
            finishUnsent(ticket)
            lastSelection = previousSelection
            noticeGeneration = previousGeneration
            return Hold.QUEUE_CLOSED
        }
        return null
    }

    /**
     * Commit the driver's target under the same lock as wake admission. In particular, a
     * still-protected reconnect for A must not leave the receive gate targeting B. After its
     * observation window a new explicit selection may change both together. A fresh AA
     * event needs no reconnect, but a selection may still release the phone picker gate.
     * The callbacks only update local fields or publish to a nonblocking channel.
     */
    @Synchronized
    fun selectAndEnqueue(
        target: String,
        now: Long,
        selected: () -> Unit,
        allowReplacePending: Boolean = false,
        publish: (Ticket) -> Boolean
    ): Hold? {
        val held = hold(target, now, allowReplacePending)
        when (held) {
            null -> {
                val result = enqueue(target, now, allowReplacePending, publish)
                if (result == null) selected()
                return result
            }
            Hold.FRESH_AA -> { noticeGeneration++; selected() }
            // Coalescing the same queued ticket is not a replacement. Its sole failure
            // notification must survive, including one already dispatched to Main.
            Hold.DUPLICATE_SELECTION -> selected()
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

    /** Final local admission, after bounded status reads and immediately before the OEM IPC. */
    @Synchronized
    fun claim(ticket: Ticket, now: Long): Boolean {
        if (closed || opening || queued != ticket || now <= freshUntil) return false
        if (!reconnect.submit(ticket, now, queuedAgainst)) return false
        queued = null
        queuedAgainst = null
        return true
    }

    @Synchronized
    fun finishUnsent(ticket: Ticket) {
        if (queued == ticket) { queued = null; queuedAgainst = null }
        // UNKNOWN_STATE may follow a successful sendBroadcast followed by a deadline check.
        // A submitted ticket remains outstanding even when its caller cannot obtain a result.
    }

    @Synchronized
    fun reported(now: Long) {
        noticeGeneration++
        lastSelection = null
        queued = null
        queuedAgainst = null
        freshUntil = now + HctAdmission.FRESH_REPORT_MS
    }

    @Synchronized
    fun beginOpening(): Boolean {
        if (closed || opening) return false
        queued = null
        queuedAgainst = null
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
    fun waitingForResult(ticket: Ticket): Boolean = submitted == ticket && queued == null && !opening && !closed

    @Synchronized
    fun close() { closed = true; queued = null; queuedAgainst = null; noticeGeneration++; lastSelection = null }

    /**
     * Capture before starting Binder/socket work, then verify that the original AA event
     * still exists. Capturing in catch would let A's late failure borrow B's generation.
     * Do not require admission.current at delivery: handoff retirement also removes a
     * genuinely failed pre-connect event before its failure reaches Main.
     */
    @Synchronized
    fun openingFailure(isCurrent: () -> Boolean): (() -> Boolean)? {
        val current = failureIsCurrent()
        return current.takeIf { isCurrent() }
    }

    /** Capture at the event source, then recheck after dispatch to Main. */
    @Synchronized
    fun failureIsCurrent(ticket: Ticket? = null): () -> Boolean {
        val generation = noticeGeneration
        return { synchronized(this) {
            !closed && noticeGeneration == generation && (ticket == null || lastSelection == ticket)
        } }
    }
}
