package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.connection.ConnectionStage
import com.andrerinas.openheadunit.main.HomePrimaryPolicy.Connection
import com.andrerinas.openheadunit.main.HomePrimaryPolicy.Input
import com.andrerinas.openheadunit.main.HomePrimaryPolicy.Status
import com.andrerinas.openheadunit.main.HomePrimaryPolicy.TileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomePrimaryPolicyTest {

    private fun input(
        chosen: Set<Connection> = emptySet(),
        lastConnection: Connection? = null,
        usbPhoneDetected: Boolean = false,
        sessionActive: Boolean = false,
        reconnectTarget: Connection? = null,
        wifiScanning: Boolean = false,
        sessionConnection: Connection? = null,
        stage: ConnectionStage? = null,
        attemptConnection: Connection? = null,
    ) = Input(
        chosen, lastConnection, usbPhoneDetected, sessionActive, reconnectTarget, wifiScanning, sessionConnection, stage,
        attemptConnection
    )

    @Test
    fun `nothing chosen and no history starts with USB and keeps the rest on the bar`() {
        val decision = HomePrimaryPolicy.decide(input())
        assertEquals(Connection.USB, decision.primary)
        assertEquals(Status.READY, decision.status)
        assertEquals(listOf(Connection.WIFI, Connection.SELF), decision.barConnections)
    }

    @Test
    fun `the last connection is the main button when nothing is plugged in`() {
        val decision = HomePrimaryPolicy.decide(input(lastConnection = Connection.WIFI))
        assertEquals(Connection.WIFI, decision.primary)
        assertEquals(listOf(Connection.USB, Connection.SELF), decision.barConnections)
    }

    @Test
    fun `a phone on the cable wins over a wireless history`() {
        val decision = HomePrimaryPolicy.decide(
            input(lastConnection = Connection.WIFI, usbPhoneDetected = true)
        )
        assertEquals(Connection.USB, decision.primary)
        assertEquals(Status.PHONE_DETECTED, decision.status)
    }

    @Test
    fun `a phone on the cable is ignored when USB is not a chosen mode`() {
        val decision = HomePrimaryPolicy.decide(
            input(chosen = setOf(Connection.WIFI), usbPhoneDetected = true)
        )
        assertEquals(Connection.WIFI, decision.primary)
        assertEquals(Status.READY, decision.status)
    }

    @Test
    fun `connections the user did not choose never reach the bar`() {
        val decision = HomePrimaryPolicy.decide(input(chosen = setOf(Connection.USB)))
        assertEquals(Connection.USB, decision.primary)
        assertEquals(emptyList<Connection>(), decision.barConnections)
    }

    @Test
    fun `a last connection that is no longer chosen falls back to the first chosen one`() {
        val decision = HomePrimaryPolicy.decide(
            input(chosen = setOf(Connection.WIFI, Connection.SELF), lastConnection = Connection.USB)
        )
        assertEquals(Connection.WIFI, decision.primary)
        assertEquals(listOf(Connection.SELF), decision.barConnections)
    }

    @Test
    fun `Self Mode alone is the main button`() {
        val decision = HomePrimaryPolicy.decide(input(chosen = setOf(Connection.SELF)))
        assertEquals(Connection.SELF, decision.primary)
        assertEquals(Status.READY, decision.status)
    }

    @Test
    fun `an active session always offers the way back to Android Auto`() {
        for (primary in Connection.values()) {
            val decision = HomePrimaryPolicy.decide(
                input(chosen = setOf(primary), sessionActive = true, usbPhoneDetected = true, wifiScanning = true)
            )
            assertEquals(Status.ACTIVE_SESSION, decision.status)
        }
    }

    @Test
    fun `the countdown names the connection it is about to try`() {
        val decision = HomePrimaryPolicy.decide(
            input(lastConnection = Connection.USB, usbPhoneDetected = true, reconnectTarget = Connection.WIFI)
        )
        assertEquals(Connection.WIFI, decision.primary)
        assertEquals(Status.RECONNECTING, decision.status)
    }

    @Test
    fun `a countdown towards a mode that is not chosen leaves every chosen one on the bar`() {
        val decision = HomePrimaryPolicy.decide(
            input(chosen = setOf(Connection.USB), reconnectTarget = Connection.SELF)
        )
        assertEquals(Connection.SELF, decision.primary)
        assertEquals(listOf(Connection.USB), decision.barConnections)
    }

    @Test
    fun `searching only shows on the wireless button`() {
        assertEquals(
            Status.SEARCHING,
            HomePrimaryPolicy.decide(input(lastConnection = Connection.WIFI, wifiScanning = true)).status
        )
        assertEquals(
            Status.READY,
            HomePrimaryPolicy.decide(input(lastConnection = Connection.USB, wifiScanning = true)).status
        )
    }

    @Test
    fun `a wireless bring-up takes the card and names its step`() {
        val decision = HomePrimaryPolicy.decide(
            input(lastConnection = Connection.USB, stage = ConnectionStage.SECURING)
        )
        assertEquals(Connection.WIFI, decision.primary)
        assertEquals(Status.CONNECTING, decision.status)
        assertEquals(ConnectionStage.SECURING, decision.stage)
        assertEquals(TileState.CONNECTING, decision.tiles[Connection.WIFI])
        assertEquals(TileState.IDLE, decision.tiles[Connection.USB])
    }

    @Test
    fun `switching a phone over the cable is a USB bring-up`() {
        val decision = HomePrimaryPolicy.decide(
            input(lastConnection = Connection.WIFI, stage = ConnectionStage.USB_SWITCHING)
        )
        assertEquals(Connection.USB, decision.primary)
        assertEquals(Status.CONNECTING, decision.status)
        assertEquals(TileState.CONNECTING, decision.tiles[Connection.USB])
    }

    @Test
    fun `a cable connection being secured stays on the USB card`() {
        val decision = HomePrimaryPolicy.decide(
            input(
                lastConnection = Connection.WIFI,
                stage = ConnectionStage.SECURING,
                attemptConnection = Connection.USB
            )
        )
        assertEquals(Connection.USB, decision.primary)
        assertEquals(Status.CONNECTING, decision.status)
        assertEquals(TileState.CONNECTING, decision.tiles[Connection.USB])
        assertEquals(TileState.IDLE, decision.tiles[Connection.WIFI])
    }

    @Test
    fun `a bring-up outranks the countdown it came from`() {
        val decision = HomePrimaryPolicy.decide(
            input(reconnectTarget = Connection.WIFI, stage = ConnectionStage.PHONE_JOINING)
        )
        assertEquals(Status.CONNECTING, decision.status)
    }

    @Test
    fun `listening for the phone keeps the button and only names the step`() {
        for (stage in listOf(ConnectionStage.ARMED, ConnectionStage.WAITING_FOR_PHONE, ConnectionStage.SEARCHING)) {
            val decision = HomePrimaryPolicy.decide(input(lastConnection = Connection.WIFI, stage = stage))
            assertEquals(Status.WAITING, decision.status)
            assertEquals(stage, decision.stage)
            assertEquals(TileState.IDLE, decision.tiles[Connection.WIFI])
        }
    }

    @Test
    fun `listening on the wireless side says nothing on a USB card`() {
        val decision = HomePrimaryPolicy.decide(
            input(lastConnection = Connection.USB, stage = ConnectionStage.ARMED)
        )
        assertEquals(Connection.USB, decision.primary)
        assertEquals(Status.READY, decision.status)
        assertNull(decision.stage)
    }

    @Test
    fun `any USB device being attached is no bring-up`() {
        val decision = HomePrimaryPolicy.decide(
            input(lastConnection = Connection.USB, stage = ConnectionStage.USB_ATTACHED)
        )
        assertEquals(Status.WAITING, decision.status)
        assertEquals(TileState.IDLE, decision.tiles[Connection.USB])
    }

    @Test
    fun `a phone named on the cable wins over the attached step`() {
        val decision = HomePrimaryPolicy.decide(
            input(lastConnection = Connection.USB, usbPhoneDetected = true, stage = ConnectionStage.USB_ATTACHED)
        )
        assertEquals(Status.PHONE_DETECTED, decision.status)
    }

    @Test
    fun `a running session leaves no step on the card even if the tracker kept one`() {
        val decision = HomePrimaryPolicy.decide(
            input(
                lastConnection = Connection.USB,
                sessionActive = true,
                sessionConnection = Connection.WIFI,
                stage = ConnectionStage.STARTING_PROJECTION
            )
        )
        assertEquals(Connection.WIFI, decision.primary)
        assertEquals(Status.ACTIVE_SESSION, decision.status)
        assertNull(decision.stage)
    }

    @Test
    fun `only the tile of the running session shows it is connected`() {
        for (connection in Connection.values()) {
            val decision = HomePrimaryPolicy.decide(
                input(sessionActive = true, sessionConnection = connection)
            )
            Connection.values().forEach {
                val expected = if (it == connection) TileState.ACTIVE else TileState.IDLE
                assertEquals(expected, decision.tiles[it])
            }
        }
    }

    @Test
    fun `a scan during the stack's own WiFi search keeps the step and Cancel on the card`() {
        val searching = input(lastConnection = Connection.WIFI, stage = ConnectionStage.SEARCHING)
        val withScan = HomePrimaryPolicy.decide(searching.copy(wifiScanning = true))
        val withoutScan = HomePrimaryPolicy.decide(searching)
        assertEquals(Status.WAITING, withScan.status)
        assertEquals(ConnectionStage.SEARCHING, withScan.stage)
        assertEquals(withoutScan.status, withScan.status)
    }

    @Test
    fun `a scan with no bring-up behind it is still a search the user can repeat`() {
        val decision = HomePrimaryPolicy.decide(
            input(lastConnection = Connection.WIFI, wifiScanning = true, stage = ConnectionStage.USB_ATTACHED)
        )
        assertEquals(Status.SEARCHING, decision.status)
    }

    @Test
    fun `the pill comes back when the card's step belongs to another connection`() {
        // The HP Slate case: the card stands for USB while the stack searches over WiFi.
        val decision = HomePrimaryPolicy.decide(
            input(lastConnection = Connection.USB, wifiScanning = true, stage = ConnectionStage.SEARCHING)
        )
        assertEquals(Connection.USB, decision.primary)
        assertEquals(false, HomePrimaryPolicy.homeTellsBringUp(decision, cardOnScreen = true))
    }

    @Test
    fun `the pill comes back on a page without the card`() {
        val decision = HomePrimaryPolicy.decide(
            input(lastConnection = Connection.WIFI, stage = ConnectionStage.WAKING_PHONE)
        )
        assertEquals(true, HomePrimaryPolicy.homeTellsBringUp(decision, cardOnScreen = true))
        assertEquals(false, HomePrimaryPolicy.homeTellsBringUp(decision, cardOnScreen = false))
    }

    @Test
    fun `without a bring-up the home tells nothing`() {
        assertEquals(false, HomePrimaryPolicy.homeTellsBringUp(HomePrimaryPolicy.decide(input()), cardOnScreen = true))
        assertEquals(false, HomePrimaryPolicy.homeTellsBringUp(null, cardOnScreen = true))
    }

    @Test
    fun `with no mode chosen the bar on a second page still carries the card's connection`() {
        val decision = HomePrimaryPolicy.decide(input(lastConnection = Connection.WIFI))
        assertEquals(
            listOf(Connection.USB, Connection.SELF, Connection.WIFI),
            HomePrimaryPolicy.barConnections(decision, emptySet(), carriesEveryConnection = true)
        )
    }

    @Test
    fun `a single page leaves the card's connection off the bar`() {
        val decision = HomePrimaryPolicy.decide(input())
        assertEquals(
            listOf(Connection.WIFI, Connection.SELF),
            HomePrimaryPolicy.barConnections(decision, emptySet(), carriesEveryConnection = false)
        )
    }

    @Test
    fun `the bar never carries a connection the user did not choose`() {
        val chosen = setOf(Connection.USB)
        val decision = HomePrimaryPolicy.decide(input(chosen = chosen, reconnectTarget = Connection.WIFI))
        assertEquals(Connection.WIFI, decision.primary)
        assertEquals(listOf(Connection.USB), HomePrimaryPolicy.barConnections(decision, chosen, carriesEveryConnection = true))
    }

    @Test
    fun `a chosen connection on the card joins the bar once`() {
        val chosen = setOf(Connection.USB, Connection.WIFI)
        val decision = HomePrimaryPolicy.decide(input(chosen = chosen))
        assertEquals(
            listOf(Connection.WIFI, Connection.USB),
            HomePrimaryPolicy.barConnections(decision, chosen, carriesEveryConnection = true)
        )
    }

    @Test
    fun `the countdown towards the connection that is listening hides the pill`() {
        val decision = HomePrimaryPolicy.decide(
            input(lastConnection = Connection.WIFI, reconnectTarget = Connection.WIFI, stage = ConnectionStage.SEARCHING)
        )
        assertEquals(Status.RECONNECTING, decision.status)
        assertNull(decision.stage)
        assertEquals(true, HomePrimaryPolicy.homeTellsBringUp(decision, cardOnScreen = true))
        assertEquals(false, HomePrimaryPolicy.homeTellsBringUp(decision, cardOnScreen = false))
    }

    @Test
    fun `once the countdown is cancelled the card keeps telling the step with its own Cancel`() {
        val decision = HomePrimaryPolicy.decide(
            input(lastConnection = Connection.WIFI, stage = ConnectionStage.SEARCHING)
        )
        assertEquals(Status.WAITING, decision.status)
        assertEquals(ConnectionStage.SEARCHING, decision.stage)
        assertEquals(true, HomePrimaryPolicy.homeTellsBringUp(decision, cardOnScreen = true))
    }

    @Test
    fun `a countdown towards another connection leaves the step to the pill`() {
        val decision = HomePrimaryPolicy.decide(
            input(reconnectTarget = Connection.USB, stage = ConnectionStage.SEARCHING)
        )
        assertEquals(Status.RECONNECTING, decision.status)
        assertEquals(false, HomePrimaryPolicy.homeTellsBringUp(decision, cardOnScreen = true))
    }

    @Test
    fun `a countdown with nothing running tells no bring-up`() {
        val decision = HomePrimaryPolicy.decide(input(reconnectTarget = Connection.WIFI))
        assertEquals(false, HomePrimaryPolicy.homeTellsBringUp(decision, cardOnScreen = true))
    }
}
