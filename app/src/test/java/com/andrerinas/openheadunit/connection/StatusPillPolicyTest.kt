package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.connection.StatusPillPolicy.Title
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusPillPolicyTest {

    @Test
    fun `a session that is up hides the pill whatever step the tracker kept`() {
        for (stage in ConnectionStage.values()) {
            assertNull(StatusPillPolicy.shownStage(stage, overlayOwnsScreen = false, phoneLeftQuiet = false, sessionLive = true))
        }
    }

    @Test
    fun `a step is shown while nothing else holds the pill back`() {
        assertEquals(
            ConnectionStage.SECURING,
            StatusPillPolicy.shownStage(ConnectionStage.SECURING, overlayOwnsScreen = false, phoneLeftQuiet = false, sessionLive = false)
        )
    }

    @Test
    fun `the overlay and the phone's own exit still hold the pill back`() {
        assertNull(StatusPillPolicy.shownStage(ConnectionStage.CONNECTING, overlayOwnsScreen = true, phoneLeftQuiet = false, sessionLive = false))
        assertNull(StatusPillPolicy.shownStage(ConnectionStage.ARMED, overlayOwnsScreen = false, phoneLeftQuiet = true, sessionLive = false))
        assertNull(StatusPillPolicy.shownStage(null, overlayOwnsScreen = false, phoneLeftQuiet = false, sessionLive = false))
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
