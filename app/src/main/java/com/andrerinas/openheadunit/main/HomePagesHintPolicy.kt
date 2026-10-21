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

        fun overlapsX(from: Int, to: Int): Boolean = left < to && right > from
        fun overlapsY(from: Int, to: Int): Boolean = top < to && bottom > from
    }

    enum class Side { LEFT, RIGHT }

    /**
     * The spotlight: a square on the card's far side from the driver, where the content leaves
     * room, as large as fits up to [maxSize]. Null when no square of [minSize] fits, so the hint
     * waits rather than covering the card's content.
     *
     * @param interior the card inside its padding.
     * @param occupied what is drawn on the card: text by its real extent, buttons, the clock.
     * @param gap the room kept between the spotlight and anything on the card.
     */
    fun hole(
        interior: Box,
        occupied: List<Box>,
        rightHandDrive: Boolean,
        minSize: Int,
        maxSize: Int,
        gap: Int,
    ): Box? {
        if (minSize <= 0 || maxSize < minSize) return null
        val step = ((maxSize - minSize) / 8).coerceAtLeast(1)
        var size = maxSize
        while (size > minSize) {
            place(interior, occupied, rightHandDrive, size, gap)?.let { return it }
            size -= step
        }
        return place(interior, occupied, rightHandDrive, minSize, gap)
    }

    private fun place(interior: Box, occupied: List<Box>, rightHandDrive: Boolean, size: Int, gap: Int): Box? {
        if (interior.width < size || interior.height < size) return null
        // A column of the spotlight's width against the far edge.
        val columnFrom = if (rightHandDrive) interior.left else interior.right - size
        val columnTo = columnFrom + size
        val inColumn = occupied.filter { it.overlapsX(columnFrom - gap, columnTo + gap) }
        // The tallest free stretch of that column.
        var best: Pair<Int, Int>? = null
        var cursor = interior.top
        for (box in inColumn.sortedBy { it.top } + Box(0, interior.bottom + gap, 0, interior.bottom + gap)) {
            val end = box.top - gap
            if (end - cursor >= size && (best == null || end - cursor > best.second - best.first)) {
                best = cursor to end
            }
            cursor = maxOf(cursor, box.bottom + gap)
        }
        val (freeTop, freeBottom) = best ?: return null
        val top = (freeTop + freeBottom - size) / 2
        // Then as far from the content as the row it sits in allows, centred in that room.
        val inRow = occupied.filter { it.overlapsY(top - gap, top + size + gap) }
        val left = if (rightHandDrive) {
            val limit = inRow.minOfOrNull { it.left - gap }?.coerceAtMost(interior.right) ?: interior.right
            interior.left + ((limit - interior.left - size) / 2).coerceAtLeast(0)
        } else {
            val limit = inRow.maxOfOrNull { it.right + gap }?.coerceAtLeast(interior.left) ?: interior.left
            limit + ((interior.right - limit - size) / 2).coerceAtLeast(0)
        }
        return Box(left, top, left + size, top + size)
    }

    /**
     * Which side of the spotlight the callout goes: towards the driver, where the card's content
     * is, and the other side when that has no room. Null when neither has.
     */
    fun calloutSide(hole: Box, screen: Box, calloutWidth: Int, gap: Int, rightHandDrive: Boolean): Side? {
        val leftFits = hole.left - gap - calloutWidth >= screen.left
        val rightFits = hole.right + gap + calloutWidth <= screen.right
        val preferred = if (rightHandDrive) Side.RIGHT else Side.LEFT
        val fits = { side: Side -> if (side == Side.LEFT) leftFits else rightFits }
        return when {
            fits(preferred) -> preferred
            fits(if (preferred == Side.LEFT) Side.RIGHT else Side.LEFT) ->
                if (preferred == Side.LEFT) Side.RIGHT else Side.LEFT
            else -> null
        }
    }

    /** Where the callout's top goes: level with the spotlight, inside the screen. */
    fun calloutTop(hole: Box, screen: Box, calloutHeight: Int, margin: Int): Int {
        val wanted = hole.centerY - calloutHeight / 2
        val max = screen.bottom - margin - calloutHeight
        return if (max < screen.top + margin) screen.top + margin else wanted.coerceIn(screen.top + margin, max)
    }
}
