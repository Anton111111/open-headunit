package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.connection.ConnectionStage

/**
 * Which connection the home screen's main button stands for, what it says about it, and which
 * connections are left for the driver bar.
 *
 * The main button replaced a grid of equal buttons, so getting this wrong hides the one the user
 * needs behind the bar. Pure, so every combination is a unit test rather than a head unit.
 */
object HomePrimaryPolicy {

    /** In the order the bar lists them. */
    enum class Connection { USB, WIFI, SELF }

    enum class Status {
        /** A projection is running: the button only has to take the user back to it. */
        ACTIVE_SESSION,
        /** A connection is on its way: the card names the step and offers to stop it. */
        CONNECTING,
        /** The auto-connect delay is counting down towards [Decision.primary]. */
        RECONNECTING,
        SEARCHING,
        /** The stack is listening for the phone on [Decision.primary]; nothing is on its way yet. */
        WAITING,
        PHONE_DETECTED,
        READY,
    }

    /** What a connection's tile on the bar says about it. */
    enum class TileState { IDLE, CONNECTING, ACTIVE }

    data class Input(
        /** The user's connection modes; empty means none chosen yet, so all of them. */
        val chosen: Set<Connection>,
        val lastConnection: Connection?,
        val usbPhoneDetected: Boolean,
        val sessionActive: Boolean,
        /** Set while the auto-connect delay runs, to the connection it is about to try. */
        val reconnectTarget: Connection?,
        val wifiScanning: Boolean,
        /** What the running session goes over; null when there is none or it is not known. */
        val sessionConnection: Connection? = null,
        /** The bring-up step the status pill used to float over the home; null when the stack is down. */
        val stage: ConnectionStage? = null,
        /**
         * What a connection still coming up goes over, once it has one. The late steps (connecting,
         * securing) are the same words for every transport, so only this tells USB from WiFi.
         */
        val attemptConnection: Connection? = null,
    )

    data class Decision(
        val primary: Connection,
        val status: Status,
        val barConnections: List<Connection>,
        /** The step the card names, only while it is [Status.CONNECTING] or [Status.WAITING]. */
        val stage: ConnectionStage? = null,
        val tiles: Map<Connection, TileState> = emptyMap(),
        /**
         * The countdown is about to try the connection whose listening step is running, so the card
         * already stands for that bring-up even though it shows the countdown instead of the step.
         */
        val countdownOverStage: Boolean = false,
    )

    /**
     * Steps where the stack is only listening. They can last all day on a unit that keeps its
     * wireless side up, so they must not turn the card into a progress screen.
     */
    private val listeningStages = setOf(
        ConnectionStage.ARMED,
        ConnectionStage.WAITING_FOR_PHONE,
        ConnectionStage.SEARCHING,
        // Any USB device reports it, a flash drive included, so it is no promise of a phone.
        ConnectionStage.USB_ATTACHED,
    )

    private val usbStages = setOf(ConnectionStage.USB_ATTACHED, ConnectionStage.USB_SWITCHING)

    /** The connection a bring-up step belongs to. Self Mode reports no steps of its own. */
    fun connectionOf(stage: ConnectionStage): Connection =
        if (stage in usbStages) Connection.USB else Connection.WIFI

    fun decide(input: Input): Decision {
        val available = Connection.values().filter { input.chosen.isEmpty() || it in input.chosen }
        val primary = primaryOf(input, available)
        val status = statusOf(input, primary)
        val stage = input.stage.takeIf { status == Status.CONNECTING || status == Status.WAITING }
        val tiles = Connection.values().associateWith { tileState(input, status, primary, it) }
        val countdownOverStage = status == Status.RECONNECTING && stageOn(input, primary)
        return Decision(primary, status, available - primary, stage, tiles, countdownOverStage)
    }

    private fun connecting(input: Input): Boolean =
        !input.sessionActive && input.stage != null && input.stage !in listeningStages

    private fun primaryOf(input: Input, available: List<Connection>): Connection = when {
        // The session the button leads back to, even for a mode the user later unchose.
        input.sessionActive && input.sessionConnection != null -> input.sessionConnection
        // The card is where the step is told, so it stands for the connection on its way.
        connecting(input) -> input.attemptConnection ?: connectionOf(input.stage!!)
        // The countdown names what is about to happen, even for a mode the user later unchose.
        input.reconnectTarget != null -> input.reconnectTarget
        // A phone on the cable is a stronger hint than the history: it was plugged in to be used.
        input.usbPhoneDetected && Connection.USB in available -> Connection.USB
        input.lastConnection != null && input.lastConnection in available -> input.lastConnection
        else -> available.first()
    }

    private fun statusOf(input: Input, primary: Connection): Status = when {
        input.sessionActive -> Status.ACTIVE_SESSION
        connecting(input) -> Status.CONNECTING
        input.reconnectTarget != null -> Status.RECONNECTING
        // The stack's own search scans too. Without this the card flipped between its step with
        // Cancel and "Search again" every time a scan started or ended within one search.
        primary == Connection.WIFI && input.wifiScanning && !stageOn(input, primary) -> Status.SEARCHING
        // Naming the phone on the cable says more than "USB device connected".
        primary == Connection.USB && input.usbPhoneDetected -> Status.PHONE_DETECTED
        stageOn(input, primary) -> Status.WAITING
        else -> Status.READY
    }

    private fun stageOn(input: Input, connection: Connection): Boolean =
        input.stage != null && connectionOf(input.stage) == connection

    /**
     * The connections the driver bar carries. With [carriesEveryConnection] (more than one page,
     * so the card is not always in view) the card's own connection joins the bar too, as long as
     * the user chose it. An empty [chosen] means every mode is allowed, so it counts as chosen:
     * without that, a unit left on the defaults had no button for the card's connection on a
     * page without the card.
     */
    fun barConnections(decision: Decision, chosen: Set<Connection>, carriesEveryConnection: Boolean): List<Connection> {
        val primaryChosen = chosen.isEmpty() || decision.primary in chosen
        return if (carriesEveryConnection && primaryChosen && decision.primary !in decision.barConnections) {
            decision.barConnections + decision.primary
        } else {
            decision.barConnections
        }
    }

    /**
     * Whether the home tells the bring-up itself, so the status pill can stay down. Only the card
     * on screen does, with the step and Cancel on it: a step that belongs to another connection,
     * or a page without the card, would leave the driver with no way to stop the bring-up.
     *
     * The countdown towards the connection whose listening step is running tells it too, so the
     * pill does not float over the card saying the same thing twice. Nothing is lost: the card's
     * Cancel ends the countdown, the card then drops to [Status.WAITING] with the step and its own
     * Cancel, and that one stops the bring-up. One tap stopping both was left out on purpose: the
     * listening is often the stack's always-on wireless side, which the driver did not ask to end
     * when they only skipped the timed attempt. A countdown towards another connection leaves the
     * step to the pill, the same as a card that stands for another connection.
     */
    fun homeTellsBringUp(decision: Decision?, cardOnScreen: Boolean): Boolean =
        cardOnScreen && decision != null && (decision.stage != null || decision.countdownOverStage)

    private fun tileState(input: Input, status: Status, primary: Connection, connection: Connection): TileState = when {
        input.sessionActive && input.sessionConnection == connection -> TileState.ACTIVE
        status == Status.CONNECTING && primary == connection -> TileState.CONNECTING
        else -> TileState.IDLE
    }
}
