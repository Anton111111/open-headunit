package com.andrerinas.openheadunit.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStagePolicyTest {

    @Test
    fun `first stage of an attempt always applies`() {
        for (stage in ConnectionStage.values()) {
            assertTrue(stage.name, ConnectionStagePolicy.shouldApply(null, stage))
        }
    }

    @Test
    fun `a later step replaces an earlier one`() {
        assertTrue(
            ConnectionStagePolicy.shouldApply(
                ConnectionStage.CREATING_NETWORK,
                ConnectionStage.WAKING_PHONE
            )
        )
    }

    @Test
    fun `an earlier step cannot rewind the display`() {
        // The group info and credential callbacks fire three or four times per group.
        assertFalse(
            ConnectionStagePolicy.shouldApply(
                ConnectionStage.WAITING_FOR_PHONE,
                ConnectionStage.CREATING_NETWORK
            )
        )
    }

    @Test
    fun `a same-rank re-delivery is accepted rather than blocked`() {
        assertTrue(
            ConnectionStagePolicy.shouldApply(
                ConnectionStage.CREATING_NETWORK,
                ConnectionStage.CREATING_NETWORK
            )
        )
        assertTrue(
            ConnectionStagePolicy.shouldApply(
                ConnectionStage.CREATING_NETWORK,
                ConnectionStage.USB_SWITCHING
            )
        )
    }

    @Test
    fun `a stray stage from another path cannot displace a near-finished attempt`() {
        assertFalse(
            ConnectionStagePolicy.shouldApply(
                ConnectionStage.SECURING,
                ConnectionStage.USB_ATTACHED
            )
        )
        assertFalse(
            ConnectionStagePolicy.shouldApply(
                ConnectionStage.STARTING_PROJECTION,
                ConnectionStage.ARMED
            )
        )
    }

    @Test
    fun `ranks never decrease in declaration order`() {
        val stages = ConnectionStage.values()
        for (i in 1 until stages.size) {
            assertTrue(
                "${stages[i].name} ranks below ${stages[i - 1].name}",
                stages[i].rank >= stages[i - 1].rank
            )
        }
    }

    @Test
    fun `every tail stage outranks every pre-connect stage`() {
        val tail = listOf(
            ConnectionStage.CONNECTING,
            ConnectionStage.SECURING,
            ConnectionStage.STARTING_PROJECTION
        )
        val preConnect = ConnectionStage.values().filterNot { it in tail }
        for (t in tail) {
            for (p in preConnect) {
                assertTrue("${t.name} vs ${p.name}", t.rank > p.rank)
            }
        }
    }

    @Test
    fun `a retreat is honoured only from the stage that asked for it`() {
        assertTrue(ConnectionStagePolicy.shouldRetreat(ConnectionStage.WAKING_PHONE, ConnectionStage.WAKING_PHONE))
        assertFalse(ConnectionStagePolicy.shouldRetreat(ConnectionStage.PHONE_ANSWERED, ConnectionStage.WAKING_PHONE))
        assertFalse(ConnectionStagePolicy.shouldRetreat(ConnectionStage.CREATING_NETWORK, ConnectionStage.WAKING_PHONE))
        assertFalse(ConnectionStagePolicy.shouldRetreat(null, ConnectionStage.WAKING_PHONE))
    }

    @Test
    fun `every stage carries a label`() {
        for (stage in ConnectionStage.values()) {
            assertTrue(stage.name, stage.label != 0)
        }
    }

    @Test
    fun `a network the unit gave up on falls back to waiting for the phone`() {
        for (stage in listOf(ConnectionStage.PREPARING_NETWORK, ConnectionStage.CREATING_NETWORK)) {
            assertEquals(stage.name, ConnectionStage.ARMED, ConnectionStagePolicy.afterNetworkAbandoned(stage))
        }
    }

    @Test
    fun `giving up on the network leaves any other step alone`() {
        assertEquals(null, ConnectionStagePolicy.afterNetworkAbandoned(null))
        for (stage in ConnectionStage.values()) {
            if (stage == ConnectionStage.PREPARING_NETWORK || stage == ConnectionStage.CREATING_NETWORK) continue
            assertEquals(stage.name, stage, ConnectionStagePolicy.afterNetworkAbandoned(stage))
        }
    }
}
