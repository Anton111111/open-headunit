package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.main.HomePagesHintPolicy.Box

/**
 * How far the main button's top corner (the countdown, or the date, time and speed) moves down so
 * the status notices at the top of the screen do not cover it. The notices are centered on the
 * screen, so on a wide card they sit over its empty middle and nothing moves; it is a narrower
 * card (a page with widgets beside it, portrait, a small unit) that puts its corner under them.
 *
 * Pure, so the geometry is a unit test rather than a row of screens.
 */
object NoticeClearancePolicy {

    /**
     * @param notices the notices on screen, in the home's coordinates.
     * @param corner the corner's views where they sit with no push at all.
     * @param gap the room to leave between a notice and the corner under it.
     * @param limit the lowest the corner may end, the bottom of the card's content: a push past it
     *     would take the corner off the card, so the push stops there.
     * @return how far down to move the corner, 0 when nothing overlaps.
     */
    fun push(notices: List<Box>, corner: List<Box>, gap: Int, limit: Int): Int {
        val needed = corner.maxOfOrNull { view ->
            notices.filter { overlapsAcross(it, view) && it.bottom > view.top - gap }
                .maxOfOrNull { it.bottom + gap - view.top } ?: 0
        } ?: 0
        if (needed <= 0) return 0
        val room = limit - (corner.maxOfOrNull { it.bottom } ?: limit)
        return minOf(needed, room).coerceAtLeast(0)
    }

    /**
     * What is left of [push] once the card has room for its action. [overflow] is how much taller
     * the card's content is than the card with the push applied and the lines that step aside
     * gone: on a short card the push gives that back, since a corner a little under a notice is
     * better than a cut action.
     */
    fun withinCard(push: Int, overflow: Int): Int = (push - overflow.coerceAtLeast(0)).coerceAtLeast(0)

    /** Side by side does not count: only a notice that shares some width with the view can cover it. */
    private fun overlapsAcross(a: Box, b: Box): Boolean = a.left < b.right && b.left < a.right
}
