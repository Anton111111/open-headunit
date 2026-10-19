package com.andrerinas.openheadunit.main

/**
 * How tall the widget row may be, from the room the home screen has left once the main button
 * keeps everything it needs. Returns 0 when there is not enough room at all, which is what keeps
 * a widget from pushing the main button's action off a short screen.
 *
 * Pure, so the arithmetic is a unit test rather than a head unit.
 */
object WidgetStripPolicy {

    /**
     * @param roomPx what is left of the column after the favorite apps row and the main button's
     *   own content, which is the space this row is allowed to take.
     * @param minPx below this a widget is not worth showing, so the row is left out instead.
     * @param maxPx the most this row may take even on a tall screen, so one large widget does not
     *   end up owning the home screen.
     * @param tallestWidgetPx the tallest widget the user placed, 0 with none placed.
     */
    fun height(roomPx: Int, minPx: Int, maxPx: Int, tallestWidgetPx: Int): Int {
        if (tallestWidgetPx <= 0) return 0
        if (roomPx < minPx) return 0
        return tallestWidgetPx.coerceIn(minPx, minOf(roomPx, maxPx))
    }
}
