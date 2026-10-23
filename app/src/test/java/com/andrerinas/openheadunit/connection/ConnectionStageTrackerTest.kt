package com.andrerinas.openheadunit.connection

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ConnectionStageTrackerTest {

    @Before
    @After
    fun reset() {
        ConnectionStageTracker.clear()
    }

    @Test
    fun `nothing is showing until a stage is reported`() {
        assertNull(ConnectionStageTracker.stage.value)
    }

    @Test
    fun `report honours the policy in both directions`() {
        ConnectionStageTracker.report(ConnectionStage.WAITING_FOR_PHONE)
        ConnectionStageTracker.report(ConnectionStage.CREATING_NETWORK)
        assertEquals(ConnectionStage.WAITING_FOR_PHONE, ConnectionStageTracker.stage.value)

        ConnectionStageTracker.report(ConnectionStage.PHONE_ANSWERED)
        assertEquals(ConnectionStage.PHONE_ANSWERED, ConnectionStageTracker.stage.value)
    }

    @Test
    fun `beginAttempt rewinds where report cannot`() {
        ConnectionStageTracker.report(ConnectionStage.SENDING_CREDENTIALS)
        ConnectionStageTracker.beginAttempt(ConnectionStage.PREPARING_NETWORK)
        assertEquals(ConnectionStage.PREPARING_NETWORK, ConnectionStageTracker.stage.value)
    }

    @Test
    fun `an unanswered wake round drops the pill back to waiting`() {
        ConnectionStageTracker.report(ConnectionStage.WAKING_PHONE)
        ConnectionStageTracker.retreat(ConnectionStage.WAKING_PHONE, ConnectionStage.WAITING_FOR_PHONE)
        assertEquals(ConnectionStage.WAITING_FOR_PHONE, ConnectionStageTracker.stage.value)

        ConnectionStageTracker.report(ConnectionStage.WAKING_PHONE)
        assertEquals(ConnectionStage.WAKING_PHONE, ConnectionStageTracker.stage.value)
    }

    @Test
    fun `a phone that answered mid-round is not retreated from`() {
        ConnectionStageTracker.report(ConnectionStage.PHONE_ANSWERED)
        ConnectionStageTracker.retreat(ConnectionStage.WAKING_PHONE, ConnectionStage.WAITING_FOR_PHONE)
        assertEquals(ConnectionStage.PHONE_ANSWERED, ConnectionStageTracker.stage.value)
    }

    @Test
    fun `clear takes the pill down`() {
        ConnectionStageTracker.report(ConnectionStage.ARMED)
        ConnectionStageTracker.reportNetwork(ConnectionNetworkDetail(5805))
        ConnectionStageTracker.clear()
        assertNull(ConnectionStageTracker.stage.value)
        assertNull(ConnectionStageTracker.network.value)
    }

    @Test
    fun `the network line is reported beside the stage and cleared on its own`() {
        ConnectionStageTracker.report(ConnectionStage.WAKING_PHONE)
        ConnectionStageTracker.reportNetwork(ConnectionNetworkDetail(5805))
        assertEquals(ConnectionNetworkDetail(5805), ConnectionStageTracker.network.value)
        assertEquals(ConnectionStage.WAKING_PHONE, ConnectionStageTracker.stage.value)

        ConnectionStageTracker.reportNetwork(null)
        assertNull(ConnectionStageTracker.network.value)
        assertEquals(ConnectionStage.WAKING_PHONE, ConnectionStageTracker.stage.value)
    }

    /** The regression: a session that ended at the top rank locked every later report out. */
    @Test
    fun `a session that ends takes the pill down and unlocks the next attempt`() {
        ConnectionStageTracker.report(ConnectionStage.STARTING_PROJECTION)
        ConnectionStageTracker.endAttempt()
        assertNull(ConnectionStageTracker.stage.value)

        ConnectionStageTracker.report(ConnectionStage.WAITING_FOR_PHONE)
        assertEquals(ConnectionStage.WAITING_FOR_PHONE, ConnectionStageTracker.stage.value)
    }

    @Test
    fun `a session that ends without removing the group keeps its network line`() {
        ConnectionStageTracker.report(ConnectionStage.STARTING_PROJECTION)
        ConnectionStageTracker.reportNetwork(ConnectionNetworkDetail(5805))
        ConnectionStageTracker.endAttempt()
        assertEquals(ConnectionNetworkDetail(5805), ConnectionStageTracker.network.value)
    }

    @Test
    fun `a failed attempt leaves the group's line up`() {
        ConnectionStageTracker.report(ConnectionStage.WAKING_PHONE)
        ConnectionStageTracker.reportNetwork(ConnectionNetworkDetail(5805))
        ConnectionStageTracker.beginAttempt(ConnectionStage.ARMED)
        assertEquals(ConnectionNetworkDetail(5805), ConnectionStageTracker.network.value)
    }
    @Test
    fun passiveWirelessReadinessPreservesUsbAndLaterProgress() {
        for (stage in ConnectionStage.values()) {
            ConnectionStageTracker.beginAttempt(stage)
            ConnectionStageTracker.reportWirelessWaiting()
            val expected = when (stage) {
                ConnectionStage.PREPARING_NETWORK, ConnectionStage.CREATING_NETWORK,
                ConnectionStage.WAKING_PHONE -> ConnectionStage.WAITING_FOR_PHONE
                else -> stage
            }
            assertEquals(expected, ConnectionStageTracker.stage.value)
        }
        ConnectionStageTracker.endAttempt()
        ConnectionStageTracker.reportWirelessWaiting()
        assertEquals(null, ConnectionStageTracker.stage.value)
    }

}
