package com.andrerinas.openheadunit.main

import kotlin.math.ceil

/**
 * How a tile label fits a narrow tile without splitting a word: it wraps only between words, and
 * when its widest word is wider than the tile, the text shrinks just enough for that word to fit
 * on one line. When even the smallest size is too wide, it stays on one line and ends in "...".
 *
 * Pure, so the sizes are unit tests rather than a row of phones.
 */
object LabelFitPolicy {

    data class Fit(
        /** The text size to use, in the same unit as the requested one. */
        val size: Float,
        /** Even the smallest size splits a word: one line with an ellipsis instead. */
        val singleLine: Boolean,
    )

    /** The words of a label, the way the line breaker sees them. */
    fun words(text: CharSequence): List<String> = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

    /**
     * The smallest size a label may shrink to. [preferred] is the floor at the label's own size
     * ([base], the one from the layout); a label scaled down from there (the home button scale, or
     * a bar shrunk to fit a short screen) has its floor scaled with it, so a word still fits at
     * 60% instead of ending in "...". It never goes under [absolute], the smallest text a driver
     * can still read, and never above [requested]: fitting only ever makes a label smaller.
     */
    fun floor(requested: Float, base: Float, preferred: Float, absolute: Float): Float {
        val scale = if (base > 0f) requested / base else 1f
        return minOf(requested, maxOf(absolute, minOf(preferred, preferred * scale)))
    }

    /**
     * @param requested the size the label asks for.
     * @param widestWord how wide its widest word is at [requested].
     * @param available the width the tile leaves for the text.
     * @param min the smallest size worth reading; [requested] when that is already smaller.
     */
    fun fit(requested: Float, widestWord: Float, available: Float, min: Float): Fit {
        if (requested <= 0f || widestWord <= available || available <= 0f) return Fit(requested, singleLine = false)
        val floor = minOf(min, requested)
        // Text widens in step with its size, so the scale that brings the word to the width is
        // the ratio of the two.
        val fitted = requested * available / widestWord
        return if (fitted >= floor) Fit(fitted, singleLine = false) else Fit(floor, singleLine = true)
    }

    /**
     * Checks a shrunk [fit] against the real width of the word at that size, [widthAt], and steps
     * the size down until it fits. At a few pixels text does not narrow exactly in step with its
     * size (each glyph's width is rounded), so the size [fit] works out can still leave the word a
     * pixel too wide, and the line breaker then splits it ("Einstellunge-n"). Below [min] the
     * label goes to one line with "..." as in [fit].
     */
    fun settle(fit: Fit, requested: Float, available: Float, min: Float, widthAt: (Float) -> Float): Fit {
        if (fit.singleLine || fit.size >= requested) return fit
        val floor = minOf(min, requested)
        var size = fit.size
        while (widthAt(size) > available) {
            if (size <= floor) return Fit(floor, singleLine = true)
            size = maxOf(floor, size - SETTLE_STEP)
        }
        return Fit(size, singleLine = false)
    }

    /** How much [settle] takes off the size on each try, in the label's unit (px on a device). */
    const val SETTLE_STEP = 0.25f

    /**
     * How wide a tile has to be for its label to keep every word whole: the tile's own width when
     * the widest word fits at a size no smaller than [min], otherwise just wide enough for that
     * word at [min], plus [paddingPx] around it. Never wider than [maxPx], past which the label
     * ends in "..." rather than the bar taking the screen.
     */
    fun tileWidth(tilePx: Int, requested: Float, widestWord: Float, min: Float, paddingPx: Int, maxPx: Int): Int {
        if (requested <= 0f || widestWord <= 0f) return tilePx
        val floor = minOf(min, requested)
        val needed = ceil(widestWord * floor / requested).toInt() + paddingPx
        return maxOf(tilePx, minOf(needed, maxPx))
    }
}
