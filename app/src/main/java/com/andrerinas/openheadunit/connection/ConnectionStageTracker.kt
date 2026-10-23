package com.andrerinas.openheadunit.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Where bring-up reports the step it is on, for MainActivity's status pill.
 *
 * A plain object rather than a field on AppComponent: reporters sit in WifiDirectManager,
 * NativeAaHandshakeManager and UsbLauncherManager, several on background threads, and App.provide()
 * is not Direct Boot safe. StateFlow.value is safe to set from any thread.
 */
object ConnectionStageTracker {

    private val _stage = MutableStateFlow<ConnectionStage?>(null)
    private val _network = MutableStateFlow<ConnectionNetworkDetail?>(null)

    /** Null means the stack is down and the pill is hidden. */
    val stage = _stage.asStateFlow()

    /** The group the pill's third line describes; null while there is none. */
    val network = _network.asStateFlow()

    /** A step of the attempt already running. Ignored if it would rewind the display. */
    fun report(candidate: ConnectionStage) {
        if (ConnectionStagePolicy.shouldApply(_stage.value, candidate)) _stage.value = candidate
    }

    /** A genuine restart: the floor drops so [opening] shows even though it ranks lower. The
     *  network line is the group owner's to clear, because a failed attempt leaves the group up. */
    fun beginAttempt(opening: ConnectionStage) {
        // A fresh arming is somebody asking for a connection, so the hold from the phone's own
        // exit is no longer about anything.
        phoneLeftAtMs = 0L
        _stage.value = opening
    }

    /** The session or attempt is over. The network line is left to whoever owns the group, which a
     *  session that ends without removing it still has. */
    fun endAttempt() {
        _stage.value = null
    }

    /** A step that ended with nothing to show: [from] gives way to [to], and any other stage stays. */
    fun retreat(from: ConnectionStage, to: ConnectionStage) {
        if (ConnectionStagePolicy.shouldRetreat(_stage.value, from)) _stage.value = to
    }

    /** A passive wireless carrier is ready; preserve USB, completed handoffs and a hidden pill. */
    fun reportWirelessWaiting() {
        _stage.update { current ->
            when (current) {
                ConnectionStage.PREPARING_NETWORK, ConnectionStage.CREATING_NETWORK,
                ConnectionStage.WAKING_PHONE -> ConnectionStage.WAITING_FOR_PHONE
                else -> current
            }
        }
    }

    /**
     * The owning wireless handoff has finished with no GAL connection or association left.
     * Unlike passive readiness, this releases its answered/credentials/joining display too.
     * Callers must first fence their attempt and check that no live handoff owns the outcome.
     * USB and GAL transport stages are never rewound here.
     */
    fun reportWirelessHandoffEnded() {
        _stage.update { current ->
            when (current) {
                ConnectionStage.PREPARING_NETWORK, ConnectionStage.CREATING_NETWORK,
                ConnectionStage.WAKING_PHONE, ConnectionStage.PHONE_ANSWERED,
                ConnectionStage.SENDING_CREDENTIALS, ConnectionStage.PHONE_JOINING ->
                    ConnectionStage.WAITING_FOR_PHONE
                else -> current
            }
        }
    }

    /** The group came up, changed, or (null) went down. Equal deliveries are conflated. */
    fun reportNetwork(detail: ConnectionNetworkDetail?) {
        _network.value = detail
    }

    /**
     * The phone ended the session itself, so the pill holds off for a moment: the listeners reopen
     * immediately behind a bye-bye and the stage they report reads as a reconnect nobody asked for.
     * `elapsedRealtime`, so a sleeping unit does not age the window.
     */
    @Volatile
    var phoneLeftAtMs = 0L
        private set

    fun notePhoneLeft(nowMs: Long) {
        phoneLeftAtMs = nowMs
    }

    /** The stack is down. */
    fun clear() {
        phoneLeftAtMs = 0L
        _stage.value = null
        _network.value = null
    }
}
