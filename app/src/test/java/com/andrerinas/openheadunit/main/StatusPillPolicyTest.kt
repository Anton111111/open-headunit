package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.connection.ConnectionStage
import com.andrerinas.openheadunit.main.StatusPillPolicy.Title
import com.andrerinas.openheadunit.main.StatusPillPolicy.Withheld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusPillPolicyTest {

    private fun withheld(
        stage: ConnectionStage? = ConnectionStage.WAITING_FOR_PHONE,
        sessionLive: Boolean = false,
        overlay: Boolean = false,
        phoneLeft: Boolean = false,
        homeTellsIt: Boolean = false,
    ) = StatusPillPolicy.withheld(stage, sessionLive, overlay, phoneLeft, homeTellsIt)

    @Test
    fun `a bring-up step with nothing in the way is shown`() {
        val stage = ConnectionStage.WAITING_FOR_PHONE
        assertNull(withheld(stage))
        assertEquals(stage, StatusPillPolicy.shown(stage, withheld(stage)))
    }

    /** The reported case: Home during a session showed "Starting Android Auto" over the card. */
    @Test
    fun `a live session hides the step the tracker kept from its start`() {
        val stage = ConnectionStage.STARTING_PROJECTION
        val reason = withheld(stage, sessionLive = true)
        assertEquals(Withheld.SESSION_LIVE, reason)
        assertNull(StatusPillPolicy.shown(stage, reason))
    }

    @Test
    fun `a live session wins over every other reason in the log`() {
        assertEquals(
            Withheld.SESSION_LIVE,
            withheld(sessionLive = true, overlay = true, phoneLeft = true, homeTellsIt = true)
        )
    }

    @Test
    fun `the other reasons keep their old precedence`() {
        assertEquals(Withheld.PHONE_LEFT, withheld(phoneLeft = true, homeTellsIt = true, overlay = true))
        assertEquals(Withheld.HOME_TELLS_IT, withheld(homeTellsIt = true, overlay = true))
        assertEquals(Withheld.OVERLAY, withheld(overlay = true))
    }

    @Test
    fun `no step means no pill and nothing withheld`() {
        assertNull(withheld(stage = null, sessionLive = true))
        assertNull(StatusPillPolicy.shown(null, null))
    }

    @Test
    fun `a stack that only listens reads as its step`() {
        for (stage in listOf(
            ConnectionStage.ARMED,
            ConnectionStage.WAITING_FOR_PHONE,
            ConnectionStage.SEARCHING,
            ConnectionStage.USB_ATTACHED,
        )) {
            assertEquals(Title.STEP, StatusPillPolicy.title(stage, attemptInProgress = false, hasCustomText = false))
        }
    }

    @Test
    fun `a connection on its way reads as starting`() {
        for (stage in ConnectionStage.values().filterNot { StatusPillPolicy.isWaiting(it) }) {
            assertEquals(Title.STARTING, StatusPillPolicy.title(stage, attemptInProgress = false, hasCustomText = false))
        }
    }

    @Test
    fun `waiting inside an auto-connect attempt still reads as starting`() {
        assertEquals(
            Title.STARTING,
            StatusPillPolicy.title(ConnectionStage.WAITING_FOR_PHONE, attemptInProgress = true, hasCustomText = false)
        )
    }

    @Test
    fun `the attempt's own text wins over both`() {
        assertEquals(Title.CUSTOM, StatusPillPolicy.title(ConnectionStage.ARMED, attemptInProgress = false, hasCustomText = true))
        assertEquals(Title.CUSTOM, StatusPillPolicy.title(ConnectionStage.SECURING, attemptInProgress = true, hasCustomText = true))
    }

    @Test
    fun `only the listening steps count as waiting`() {
        assertTrue(StatusPillPolicy.isWaiting(ConnectionStage.ARMED))
        assertFalse(StatusPillPolicy.isWaiting(ConnectionStage.CREATING_NETWORK))
        assertFalse(StatusPillPolicy.isWaiting(ConnectionStage.STARTING_PROJECTION))
    }
}
