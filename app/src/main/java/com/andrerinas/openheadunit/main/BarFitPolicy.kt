package com.andrerinas.openheadunit.main

/**
 * How much the home driver bar's tiles shrink when the screen cannot hold them at the user's
 * scale. The Exit tile is pinned below the others and shrinks with them, so without this it is the
 * one that gets clipped on short screens. Pure, so the arithmetic is a unit test rather than a
 * head unit.
 */
object BarFitPolicy {

    /** The tiles stop shrinking here: a car screen is used with a thumb, not with a cursor. */
    const val MIN_SCALE = 0.6f

    /**
     * The scale to draw the tiles at, from their size in the layout. [tilesBasePx] is the height
     * of the tiles at 100%, [fixedPx] the gaps and paddings around them, which do not scale.
     * Returns the user's own scale when everything fits, and [MIN_SCALE] when even that is not
     * enough: past that point the bar scrolls instead of shrinking any further.
     */
    fun tileScale(availablePx: Int, tilesBasePx: Int, fixedPx: Int, userScalePercent: Int): Float {
        val userScale = userScalePercent.coerceIn(60, 120) / 100f
        if (availablePx <= 0 || tilesBasePx <= 0) return userScale
        val room = availablePx - fixedPx
        if (room >= tilesBasePx * userScale) return userScale
        return (room.toFloat() / tilesBasePx).coerceIn(MIN_SCALE, userScale)
    }
}
