package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.main.HomePagesHintPolicy.Box
import com.andrerinas.openheadunit.main.HomePagesHintPolicy.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePagesHintPolicyTest {

    private val ready = HomePagesHintPolicy.Input(
        alreadyShown = false,
        launcherActive = true,
        widgetsSupported = true,
        appsAndWidgetsOn = true,
        connected = false,
        status = HomePrimaryPolicy.Status.READY,
        bringUpRunning = false,
        otherNoticeShowing = false,
        onMainPage = true,
    )

    @Test
    fun `an idle launcher home that never showed it shows the hint`() {
        assertTrue(HomePagesHintPolicy.shouldShow(ready))
    }

    @Test
    fun `it is shown only once`() {
        assertFalse(HomePagesHintPolicy.shouldShow(ready.copy(alreadyShown = true)))
    }

    @Test
    fun `only the launcher home with widgets has pages to add`() {
        assertFalse(HomePagesHintPolicy.shouldShow(ready.copy(launcherActive = false)))
        assertFalse(HomePagesHintPolicy.shouldShow(ready.copy(widgetsSupported = false)))
        assertFalse(HomePagesHintPolicy.shouldShow(ready.copy(appsAndWidgetsOn = false)))
    }

    @Test
    fun `it waits while a phone connects, counts down or is projected`() {
        assertFalse(HomePagesHintPolicy.shouldShow(ready.copy(connected = true)))
        assertFalse(HomePagesHintPolicy.shouldShow(ready.copy(bringUpRunning = true)))
        listOf(
            HomePrimaryPolicy.Status.ACTIVE_SESSION,
            HomePrimaryPolicy.Status.CONNECTING,
            HomePrimaryPolicy.Status.RECONNECTING,
            null,
        ).forEach { assertFalse(it.toString(), HomePagesHintPolicy.shouldShow(ready.copy(status = it))) }
    }

    @Test
    fun `waiting for the phone or searching for it is still idle`() {
        listOf(
            HomePrimaryPolicy.Status.WAITING,
            HomePrimaryPolicy.Status.SEARCHING,
            HomePrimaryPolicy.Status.PHONE_DETECTED,
        ).forEach { assertTrue(it.toString(), HomePagesHintPolicy.shouldShow(ready.copy(status = it))) }
    }

    @Test
    fun `it waits for other notices and for the main page`() {
        assertFalse(HomePagesHintPolicy.shouldShow(ready.copy(otherNoticeShowing = true)))
        assertFalse(HomePagesHintPolicy.shouldShow(ready.copy(onMainPage = false)))
    }

    // A card of 800 by 400 with its content on the driver's side.
    private val interior = Box(100, 50, 900, 450)
    private val title = Box(100, 200, 400, 260)
    private val action = Box(100, 330, 360, 400)
    private val clock = Box(780, 50, 900, 100)

    @Test
    fun `the spotlight goes on the far side, clear of the content and the clock`() {
        val hole = HomePagesHintPolicy.hole(interior, listOf(title, action, clock), false, 150, 200, 10)
        assertNotNull(hole)
        hole!!
        assertTrue(hole.left >= title.right + 10)
        assertTrue(hole.right <= interior.right)
        assertTrue(hole.top >= clock.bottom + 10)
        assertTrue(hole.bottom <= interior.bottom)
        assertEquals(200, hole.width)
    }

    @Test
    fun `with right-hand drive the spotlight mirrors to the left`() {
        val mirror = { b: Box -> Box(1000 - b.right, b.top, 1000 - b.left, b.bottom) }
        val hole = HomePagesHintPolicy.hole(
            interior, listOf(title, action, clock).map(mirror), true, 150, 200, 10
        )!!
        assertTrue(hole.right <= mirror(title).left - 10)
        assertTrue(hole.left >= interior.left)
    }

    @Test
    fun `a card with no empty spot gets no hint`() {
        val crowded = Box(100, 50, 900, 450)
        assertNull(HomePagesHintPolicy.hole(interior, listOf(crowded), false, 150, 200, 10))
    }

    @Test
    fun `a short card takes a smaller spotlight down to the minimum`() {
        val short = Box(100, 50, 900, 220)
        val hole = HomePagesHintPolicy.hole(short, listOf(title.copy(top = 60, bottom = 120)), false, 150, 200, 10)!!
        assertTrue(hole.height in 150..170)
        assertNull(HomePagesHintPolicy.hole(Box(100, 50, 900, 190), emptyList(), false, 150, 200, 10))
    }

    @Test
    fun `the callout goes towards the driver and switches sides when there is no room`() {
        val screen = Box(0, 0, 1280, 720)
        val hole = Box(900, 300, 1100, 500)
        assertEquals(Side.LEFT, HomePagesHintPolicy.calloutSide(hole, screen, 380, 18, rightHandDrive = false))
        val leftHole = Box(100, 300, 300, 500)
        assertEquals(Side.RIGHT, HomePagesHintPolicy.calloutSide(leftHole, screen, 380, 18, rightHandDrive = false))
        assertEquals(Side.RIGHT, HomePagesHintPolicy.calloutSide(leftHole, screen, 380, 18, rightHandDrive = true))
        assertNull(HomePagesHintPolicy.calloutSide(Box(500, 300, 700, 500), Box(0, 0, 800, 720), 600, 18, false))
    }

    @Test
    fun `the callout stays level with the spotlight and inside the screen`() {
        val screen = Box(0, 0, 1280, 720)
        assertEquals(300, HomePagesHintPolicy.calloutTop(Box(900, 300, 1100, 500), screen, 200, 12))
        assertEquals(12, HomePagesHintPolicy.calloutTop(Box(900, 0, 1100, 100), screen, 300, 12))
        assertEquals(720 - 12 - 300, HomePagesHintPolicy.calloutTop(Box(900, 620, 1100, 720), screen, 300, 12))
    }
}
