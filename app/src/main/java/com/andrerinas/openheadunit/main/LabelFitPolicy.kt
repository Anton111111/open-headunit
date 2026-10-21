package com.andrerinas.openheadunit.main

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
}
