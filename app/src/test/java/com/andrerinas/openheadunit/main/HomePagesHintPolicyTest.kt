package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.main.HomePagesHintPolicy.Box
import com.andrerinas.openheadunit.main.HomePagesHintPolicy.Side
import com.andrerinas.openheadunit.main.HomePagesHintPolicy.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // A landscape card of 800 by 400 with its content on the driver's side.
    private val interior = Box(100, 50, 900, 450)
    private val title = Box(100, 200, 400, 260)
    private val action = Box(100, 330, 360, 400)
    private val clock = Box(780, 50, 900, 100)

    private fun clear(hole: Box, of: List<Box>, gap: Int) = of.none {
        it.left - gap < hole.right && it.right + gap > hole.left && it.top - gap < hole.bottom && it.bottom + gap > hole.top
    }

    @Test
    fun `the spot is the empty part beside the text, clear of the content and the clock`() {
        val spot = HomePagesHintPolicy.emptySpot(interior, listOf(title, action, clock), false, 150, 360, 290, 10)!!
        assertTrue(clear(spot, listOf(title, action, clock), 10))
        assertTrue(spot.left >= title.right + 10)
        assertTrue(spot.right <= interior.right && spot.bottom <= interior.bottom)
        // Bigger than the old fixed square: it takes the empty part of the card.
        assertTrue(spot.width > 200 && spot.height > 200)
    }

    @Test
    fun `with right-hand drive the spot mirrors to the left`() {
        val mirror = { b: Box -> Box(1000 - b.right, b.top, 1000 - b.left, b.bottom) }
        val content = listOf(title, action, clock).map(mirror)
        val spot = HomePagesHintPolicy.emptySpot(interior, content, true, 150, 360, 290, 10)!!
        assertTrue(spot.right <= mirror(title).left - 10)
        assertTrue(clear(spot, content, 10))
    }

    @Test
    fun `a card with no empty spot gets no hint`() {
        assertNull(HomePagesHintPolicy.emptySpot(interior, listOf(Box(100, 50, 900, 450)), false, 150, 360, 290, 10))
        assertNull(HomePagesHintPolicy.emptySpot(Box(100, 50, 900, 190), emptyList(), false, 150, 360, 290, 10))
    }

    // A tall portrait card: the kicker, title and subtitle in the middle, the button at the bottom,
    // text running nearly the whole width, so there is no room beside it.
    private val tall = Box(40, 40, 1040, 1900)
    private val kicker = Box(40, 900, 300, 960)
    private val portraitTitle = Box(40, 980, 1000, 1080)
    private val subtitle = Box(40, 1100, 900, 1160)
    private val button = Box(40, 1700, 1040, 1860)

    @Test
    fun `in portrait the spot is a band above the text or between it and the button`() {
        val content = listOf(kicker, portraitTitle, subtitle, button)
        val spot = HomePagesHintPolicy.emptySpot(tall, content, false, 150, 900, 720, 10)!!
        assertTrue(clear(spot, content, 10))
        assertTrue(spot.bottom <= kicker.top - 10 || (spot.top >= subtitle.bottom + 10 && spot.bottom <= button.top - 10))
        assertTrue(spot.width > 900)
    }

    private val screen = Box(16, 16, 1264, 704)

    @Test
    fun `the callout goes beside the spotlight towards the driver, inside the bounds`() {
        val placed = HomePagesHintPolicy.place(
            spot = Box(500, 120, 880, 440), maxWidth = 360, maxHeight = 290, minSize = 150,
            bounds = listOf(Box(100, 50, 900, 450)), callouts = listOf(Size(300, 200)),
            gap = 18, arrowInset = 32, rightHandDrive = false,
        )!!
        assertEquals(Side.LEFT, placed.side)
        assertTrue(placed.callout.right + 18 <= placed.hole.left)
        assertTrue(placed.callout.left >= 100)
        assertTrue(placed.hole.width >= 150)
    }

    @Test
    fun `the spotlight shrinks before the callout leaves the card`() {
        val placed = HomePagesHintPolicy.place(
            spot = Box(110, 60, 890, 440), maxWidth = 360, maxHeight = 290, minSize = 150,
            bounds = listOf(Box(100, 50, 900, 450)), callouts = listOf(Size(380, 200)),
            gap = 18, arrowInset = 32, rightHandDrive = false,
        )!!
        assertTrue(placed.hole.width < 360)
        assertTrue(placed.callout.left >= 100 && placed.callout.right <= 900)
    }

    @Test
    fun `in portrait the callout goes below or above the spotlight`() {
        val placed = HomePagesHintPolicy.place(
            spot = Box(40, 1170, 1040, 1690), maxWidth = 900, maxHeight = 720, minSize = 150,
            bounds = listOf(tall), callouts = listOf(Size(900, 260)),
            gap = 18, arrowInset = 32, rightHandDrive = false,
        )!!
        assertTrue(placed.side == Side.BELOW || placed.side == Side.ABOVE)
        assertTrue(placed.callout.top >= tall.top && placed.callout.bottom <= tall.bottom)
        assertTrue(placed.hole.centerX in placed.callout.left + 32..placed.callout.right - 32)
    }

    @Test
    fun `the small callout and then the wider bounds are tried before giving up`() {
        val cardOnly = Box(300, 50, 900, 450)
        val placed = HomePagesHintPolicy.place(
            spot = Box(700, 60, 890, 440), maxWidth = 360, maxHeight = 290, minSize = 150,
            bounds = listOf(cardOnly, screen), callouts = listOf(Size(500, 300), Size(380, 200)),
            gap = 18, arrowInset = 32, rightHandDrive = false,
        )!!
        assertEquals(1, placed.calloutSize)
        assertTrue(placed.callout.left >= cardOnly.left)
        assertNull(
            HomePagesHintPolicy.place(
                spot = Box(700, 60, 890, 440), maxWidth = 360, maxHeight = 290, minSize = 150,
                bounds = listOf(Box(650, 50, 900, 450)), callouts = listOf(Size(380, 200)),
                gap = 18, arrowInset = 32, rightHandDrive = false,
            )
        )
    }

    @Test
    fun `a narrow band is enough for a small spotlight`() {
        // A short portrait card where only 70 of free height is left above the text.
        val card = Box(0, 0, 400, 300)
        val content = listOf(Box(0, 80, 380, 300))
        val spot = HomePagesHintPolicy.emptySpot(card, content, false, 64, 360, 290, 5)!!
        assertTrue(spot.height in 64..75)
        assertNull(HomePagesHintPolicy.emptySpot(card, content, false, 148, 360, 290, 5))
    }

    @Test
    fun `the ring shrinks with a small spotlight and stops growing at its full size`() {
        assertEquals(96, HomePagesHintPolicy.ringDiameter(Box(0, 0, 300, 200), 96))
        assertEquals(56, HomePagesHintPolicy.ringDiameter(Box(0, 0, 300, 70), 96))
    }

    @Test
    fun `the card wins whenever it has a spot`() {
        val card = HomePagesHintPolicy.Region(Box(0, 0, 400, 300), listOf(Box(0, 0, 300, 300)))
        val page = HomePagesHintPolicy.Region(Box(0, 0, 400, 900), listOf(Box(0, 0, 400, 300)))
        val spot = HomePagesHintPolicy.bestSpot(listOf(card, page), false, 64, 360, 290, 5)!!
        assertTrue(spot.bottom <= 300 && spot.left >= 300)
    }

    @Test
    fun `without room on the card the empty part of the page around it is used`() {
        val card = HomePagesHintPolicy.Region(Box(0, 0, 400, 300), listOf(Box(0, 0, 400, 300)))
        val page = HomePagesHintPolicy.Region(Box(0, 0, 400, 900), listOf(Box(0, 0, 400, 300), Box(0, 310, 400, 400)))
        val spot = HomePagesHintPolicy.bestSpot(listOf(card, page), false, 64, 360, 290, 5)!!
        assertTrue(spot.top >= 405)
        assertNull(HomePagesHintPolicy.bestSpot(listOf(card), false, 64, 360, 290, 5))
    }

    @Test
    fun `with no spot at all the callout is centred, the first size that fits`() {
        val screen = Box(0, 0, 480, 240)
        val (box, index) = HomePagesHintPolicy.centredCallout(
            screen, 16, listOf(Size(380, 330), Size(448, 300), Size(294, 260), Size(448, 200))
        )
        assertEquals(3, index)
        assertEquals(Box(16, 20, 464, 220), box)
    }

    @Test
    fun `when nothing fits the last size is still shown inside the margins`() {
        val (box, index) = HomePagesHintPolicy.centredCallout(Box(0, 0, 320, 200), 16, listOf(Size(288, 400)))
        assertEquals(0, index)
        assertEquals(16, box.left)
        assertEquals(16, box.top)
    }

    @Test
    fun `the small callout comes before a spotlight under the preferred size`() {
        // A compact card: the regular callout only fits beside a tiny spotlight, the small one
        // beside a readable one.
        val card = Box(0, 0, 600, 340)
        val spot = Box(380, 100, 590, 330)
        val callouts = listOf(Size(460, 200), Size(330, 180))
        val placed = HomePagesHintPolicy.place(
            spot = spot, maxWidth = 360, maxHeight = 290, minSize = 64,
            bounds = listOf(card), callouts = callouts,
            gap = 18, arrowInset = 32, rightHandDrive = false, preferredSize = 120,
        )!!
        assertEquals(1, placed.calloutSize)
        assertTrue(placed.hole.width >= 120)
        // Only when no callout fits that way does the spotlight go below it.
        val tight = HomePagesHintPolicy.place(
            spot = spot, maxWidth = 360, maxHeight = 290, minSize = 64,
            bounds = listOf(card), callouts = listOf(Size(460, 200)),
            gap = 18, arrowInset = 32, rightHandDrive = false, preferredSize = 120,
        )!!
        assertTrue(tight.hole.width < 120)
    }
}
