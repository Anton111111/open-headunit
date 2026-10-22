package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.main.HomePagesHintPolicy.Box
import org.junit.Assert.assertEquals
import org.junit.Test

class NoticeClearancePolicyTest {

    // The pill as it sat at 1280x720: centered on the screen, 24 to 75px down.
    private val pill = Box(506, 24, 774, 75)

    @Test
    fun `a wide card keeps its corner where it is`() {
        // The main page: the countdown at the far right, well past the pill.
        val countdown = Box(1125, 52, 1220, 147)
        assertEquals(0, NoticeClearancePolicy.push(listOf(pill), listOf(countdown), gap = 12, limit = 500))
    }

    @Test
    fun `a corner under the pill moves down below it`() {
        // A page with widgets beside the card: the countdown ends up under the pill's right end.
        val countdown = Box(702, 52, 790, 140)
        assertEquals(75 + 12 - 52, NoticeClearancePolicy.push(listOf(pill), listOf(countdown), gap = 12, limit = 640))
    }

    @Test
    fun `the lowest notice over the corner decides`() {
        val banner = Box(16, 83, 1264, 140)
        val info = Box(1100, 40, 1240, 90)
        assertEquals(140 + 12 - 40, NoticeClearancePolicy.push(listOf(pill, banner), listOf(info), gap = 12, limit = 640))
    }

    @Test
    fun `a notice already clear of the corner by the gap moves nothing`() {
        val countdown = Box(702, 90, 790, 178)
        assertEquals(0, NoticeClearancePolicy.push(listOf(pill), listOf(countdown), gap = 12, limit = 640))
    }

    @Test
    fun `the corner never leaves the card`() {
        // A short card: 30px under the countdown, though it would need 35.
        val countdown = Box(702, 52, 790, 140)
        assertEquals(30, NoticeClearancePolicy.push(listOf(pill), listOf(countdown), gap = 12, limit = 170))
    }

    @Test
    fun `a card with room keeps the whole push`() {
        assertEquals(65, NoticeClearancePolicy.withinCard(push = 65, overflow = 0))
        assertEquals(65, NoticeClearancePolicy.withinCard(push = 65, overflow = -40))
    }

    @Test
    fun `a short card gives back what its action would lose`() {
        // 800x480: the banner asked for 65px and the action ended 36px past the card.
        assertEquals(29, NoticeClearancePolicy.withinCard(push = 65, overflow = 36))
        assertEquals(0, NoticeClearancePolicy.withinCard(push = 65, overflow = 90))
    }

    @Test
    fun `no notices or no corner moves nothing`() {
        val countdown = Box(702, 52, 790, 140)
        assertEquals(0, NoticeClearancePolicy.push(emptyList(), listOf(countdown), gap = 12, limit = 640))
        assertEquals(0, NoticeClearancePolicy.push(listOf(pill), emptyList(), gap = 12, limit = 640))
    }
}
