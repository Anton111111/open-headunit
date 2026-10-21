package com.andrerinas.openheadunit.main

/**
 * The one-time hint that shows where pages are added: a touch and hold on an empty spot of the
 * main button. Who sees it and when, and where it goes on the card.
 *
 * Pure, so the rules and the geometry are unit tests rather than drives with a head unit.
 */
object HomePagesHintPolicy {

    data class Input(
        val alreadyShown: Boolean,
        /** Open Headunit is the home screen; without it there are no pages to add. */
        val launcherActive: Boolean,
        /** Android 5.0 and up, where a widget can be added at all. */
        val widgetsSupported: Boolean,
        /** The apps and widgets switch in Customization. */
        val appsAndWidgetsOn: Boolean,
        /** A phone is connected or being connected. */
        val connected: Boolean,
        /** What the main button says right now; null before the first render. */
        val status: HomePrimaryPolicy.Status?,
        /** A bring-up step is being told, on the card or the status pill. */
        val bringUpRunning: Boolean,
        /** Another notice, dialog or the connection issue banner is on screen. */
        val otherNoticeShowing: Boolean,
        /** The pager is on the main button's page, so the card is in view. */
        val onMainPage: Boolean,
    )

    /** The main button is at rest: nothing counting down, connecting or projected. */
    private val idleStatuses = setOf(
        HomePrimaryPolicy.Status.READY,
        HomePrimaryPolicy.Status.WAITING,
        HomePrimaryPolicy.Status.SEARCHING,
        HomePrimaryPolicy.Status.PHONE_DETECTED,
    )

    /** Whether the main button is at rest, so the hint may come up or stay up. */
    fun isIdle(status: HomePrimaryPolicy.Status?): Boolean = status in idleStatuses

    /** A false answer is not final: the home asks again the next time it is shown. */
    fun shouldShow(input: Input): Boolean =
        !input.alreadyShown &&
            input.launcherActive &&
            input.widgetsSupported &&
            input.appsAndWidgetsOn &&
            !input.connected &&
            isIdle(input.status) &&
            !input.bringUpRunning &&
            !input.otherNoticeShowing &&
            input.onMainPage

    /** A rectangle in pixels, in whatever space the caller measured the card in. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
    }

    /** Where the callout sits, seen from the spotlight. */
    enum class Side { LEFT, RIGHT, ABOVE, BELOW }

    data class Size(val width: Int, val height: Int)

    /** The spotlight, the callout and which of the given callout sizes it took (0 is the first). */
    data class Placement(val hole: Box, val callout: Box, val side: Side, val calloutSize: Int)

    /**
     * The empty part of the card the spotlight goes on: of the free rectangles between what is
     * drawn on it, the one with the most room, counted up to [maxWidth] by [maxHeight] so a
     * huge one does not win over one that is merely big enough. Beside the text in landscape, a
     * band above it or between it and the button in portrait. On a tie, the one further from
     * the driver. Null when no rectangle of [minSize] by [minSize] is free.
     *
     * @param interior the card inside its padding.
     * @param occupied what is drawn on the card: text by its real extent, buttons, the clock.
     * @param gap the room kept between the spotlight and anything on the card.
     */
    fun emptySpot(
        interior: Box,
        occupied: List<Box>,
        rightHandDrive: Boolean,
        minSize: Int,
        maxWidth: Int,
        maxHeight: Int,
        gap: Int,
    ): Box? {
        val blocked = occupied.map { Box(it.left - gap, it.top - gap, it.right + gap, it.bottom + gap) }
        val xs = (listOf(interior.left, interior.right) + blocked.flatMap { listOf(it.left, it.right) })
            .map { it.coerceIn(interior.left, interior.right) }.distinct().sorted()
        val ys = (listOf(interior.top, interior.bottom) + blocked.flatMap { listOf(it.top, it.bottom) })
            .map { it.coerceIn(interior.top, interior.bottom) }.distinct().sorted()
        var best: Box? = null
        var bestScore = 0L
        for (left in xs) for (right in xs) {
            if (right - left < minSize) continue
            for (top in ys) for (bottom in ys) {
                if (bottom - top < minSize) continue
                if (blocked.any { it.left < right && it.right > left && it.top < bottom && it.bottom > top }) continue
                val candidate = Box(left, top, right, bottom)
                val score = minOf(candidate.width, maxWidth).toLong() * minOf(candidate.height, maxHeight)
                val further = best != null && score == bestScore &&
                    if (rightHandDrive) candidate.centerX < best.centerX else candidate.centerX > best.centerX
                if (score > bestScore || further) {
                    best = candidate
                    bestScore = score
                }
            }
        }
        return best
    }

    /**
     * The spotlight and its callout. The spotlight starts as large as the spot allows up to the
     * maximum, and shrinks, kept against the spot's far side, until a callout fits beside it
     * (towards the driver first), below or above it. [bounds] are tried in order, the card first;
     * [callouts] are the sizes to try, the regular one first.
     */
    fun place(
        spot: Box,
        maxWidth: Int,
        maxHeight: Int,
        minSize: Int,
        bounds: List<Box>,
        callouts: List<Size>,
        gap: Int,
        arrowInset: Int,
        rightHandDrive: Boolean,
    ): Placement? {
        for (area in bounds) for ((index, size) in callouts.withIndex()) {
            for (scale in HOLE_SCALES) {
                val hole = sizedHole(spot, maxWidth, maxHeight, minSize, scale, rightHandDrive)
                calloutFor(hole, area, size, gap, arrowInset, rightHandDrive)?.let { (box, side) ->
                    return Placement(hole, box, side, index)
                }
            }
        }
        return null
    }

    private val HOLE_SCALES = listOf(1f, 0.85f, 0.7f, 0.55f, 0.4f, 0f)

    private fun sizedHole(spot: Box, maxWidth: Int, maxHeight: Int, minSize: Int, scale: Float, rightHandDrive: Boolean): Box {
        val fullWidth = minOf(spot.width, maxWidth)
        val fullHeight = minOf(spot.height, maxHeight)
        val width = maxOf(minSize, (fullWidth * scale).toInt()).coerceAtMost(fullWidth)
        val height = maxOf(minSize, (fullHeight * scale).toInt()).coerceAtMost(fullHeight)
        // Centred in the spot while it is full size, then held against the far side as it shrinks,
        // which leaves the room towards the driver to the callout.
        val centredLeft = spot.left + (spot.width - fullWidth) / 2
        val left = if (rightHandDrive) centredLeft else centredLeft + fullWidth - width
        val top = spot.top + (spot.height - height) / 2
        return Box(left, top, left + width, top + height)
    }

    private fun calloutFor(hole: Box, area: Box, size: Size, gap: Int, arrowInset: Int, rightHandDrive: Boolean): Pair<Box, Side>? {
        val w = size.width
        val h = size.height
        if (w > area.width || h > area.height) return null
        val towardsDriver = if (rightHandDrive) Side.RIGHT else Side.LEFT
        val away = if (rightHandDrive) Side.LEFT else Side.RIGHT
        for (side in listOf(towardsDriver, away, Side.BELOW, Side.ABOVE)) {
            val box = when (side) {
                Side.LEFT, Side.RIGHT -> {
                    val left = if (side == Side.LEFT) hole.left - gap - w else hole.right + gap
                    val top = (hole.centerY - h / 2).coerceIn(area.top, area.bottom - h)
                    Box(left, top, left + w, top + h)
                }
                Side.ABOVE, Side.BELOW -> {
                    val top = if (side == Side.ABOVE) hole.top - gap - h else hole.bottom + gap
                    val left = (hole.centerX - w / 2).coerceIn(area.left, area.right - w)
                    Box(left, top, left + w, top + h)
                }
            }
            val inside = box.left >= area.left && box.right <= area.right && box.top >= area.top && box.bottom <= area.bottom
            // The arrow has to reach the hand from a straight part of the callout's edge.
            val pointsAtHand = when (side) {
                Side.LEFT, Side.RIGHT -> hole.centerY in (box.top + arrowInset)..(box.bottom - arrowInset)
                Side.ABOVE, Side.BELOW -> hole.centerX in (box.left + arrowInset)..(box.right - arrowInset)
            }
            if (inside && pointsAtHand) return box to side
        }
        return null
    }
}
