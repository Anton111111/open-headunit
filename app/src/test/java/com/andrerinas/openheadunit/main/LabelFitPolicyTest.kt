package com.andrerinas.openheadunit.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelFitPolicyTest {

    @Test
    fun `a label whose words fit keeps its size and may wrap between words`() {
        val fit = LabelFitPolicy.fit(requested = 28f, widestWord = 50f, available = 60f, min = 18f)
        assertEquals(28f, fit.size, 0.001f)
        assertFalse(fit.singleLine)
    }

    @Test
    fun `a word wider than the tile shrinks the text just enough to fit`() {
        // "Settings" at 28px is 70px wide in a 60px tile.
        val fit = LabelFitPolicy.fit(requested = 28f, widestWord = 70f, available = 60f, min = 18f)
        assertEquals(24f, fit.size, 0.001f)
        assertFalse(fit.singleLine)
    }

    @Test
    fun `below the smallest size the label stays on one line with an ellipsis`() {
        // "Einstellungen" far wider than a narrow tile.
        val fit = LabelFitPolicy.fit(requested = 28f, widestWord = 140f, available = 60f, min = 18f)
        assertEquals(18f, fit.size, 0.001f)
        assertTrue(fit.singleLine)
    }

    @Test
    fun `a label already under the minimum is never made bigger`() {
        val fit = LabelFitPolicy.fit(requested = 16f, widestWord = 100f, available = 60f, min = 18f)
        assertEquals(16f, fit.size, 0.001f)
        assertTrue(fit.singleLine)
    }

    @Test
    fun `no room measured yet leaves the label alone`() {
        assertEquals(LabelFitPolicy.Fit(28f, false), LabelFitPolicy.fit(28f, 70f, 0f, 18f))
    }

    @Test
    fun `words are split on any whitespace`() {
        assertEquals(listOf("Self", "Mode"), LabelFitPolicy.words("Self  Mode"))
        assertEquals(listOf("Einstellungen"), LabelFitPolicy.words(" Einstellungen\n"))
        assertEquals(emptyList<String>(), LabelFitPolicy.words(""))
    }
    // Sizes in sp, as the label works them out in px at any density: 9sp preferred, 7sp absolute.

    @Test
    fun `a label at its own size keeps the preferred floor`() {
        assertEquals(9f, LabelFitPolicy.floor(requested = 14f, base = 14f, preferred = 9f, absolute = 7f), 0.001f)
    }

    @Test
    fun `a label scaled to 60 percent may shrink to the readable minimum`() {
        // 14sp at 60% is 8.4sp, under the 9sp floor, so before this it could not shrink at all.
        assertEquals(7f, LabelFitPolicy.floor(requested = 8.4f, base = 14f, preferred = 9f, absolute = 7f), 0.001f)
    }

    @Test
    fun `a label scaled up keeps the preferred floor rather than a bigger one`() {
        assertEquals(9f, LabelFitPolicy.floor(requested = 16.8f, base = 14f, preferred = 9f, absolute = 7f), 0.001f)
    }

    @Test
    fun `the floor never asks for a bigger label`() {
        // The smallest screens draw their labels at 8sp, and 60% of that is 4.8sp.
        assertEquals(8f, LabelFitPolicy.floor(requested = 8f, base = 8f, preferred = 9f, absolute = 7f), 0.001f)
        assertEquals(4.8f, LabelFitPolicy.floor(requested = 4.8f, base = 8f, preferred = 9f, absolute = 7f), 0.001f)
    }

    @Test
    fun `russian Settings at 60 percent on 800x480 fits whole once the floor follows the scale`() {
        // 48px tile, 8px of padding and 2 of slack: 38px for "Настройки", about 50px at 8.4px.
        val floor = LabelFitPolicy.floor(requested = 8.4f, base = 14f, preferred = 9f, absolute = 7f)
        val fit = LabelFitPolicy.fit(requested = 8.4f, widestWord = 50f, available = 38f, min = floor)
        assertTrue(fit.singleLine)
        // The tile widens to fit the word at 7px instead: 50 * 7 / 8.4 = 41.7, plus 10 of padding.
        assertEquals(52, LabelFitPolicy.tileWidth(48, 8.4f, 50f, floor, paddingPx = 10, maxPx = 96))
    }

    @Test
    fun `a tile whose words fit keeps its width`() {
        assertEquals(80, LabelFitPolicy.tileWidth(80, 14f, 60f, 9f, paddingPx = 10, maxPx = 160))
    }

    @Test
    fun `a tile never widens past its cap`() {
        assertEquals(96, LabelFitPolicy.tileWidth(48, 8.4f, 200f, 7f, paddingPx = 10, maxPx = 96))
    }

    @Test
    fun `a label already under the floor widens the tile at its own size`() {
        // 4.8px asked for, 30px wide: the floor is the requested size itself.
        assertEquals(40, LabelFitPolicy.tileWidth(26, 4.8f, 30f, 7f, paddingPx = 10, maxPx = 52))
    }

    @Test
    fun `an empty label leaves the tile alone`() {
        assertEquals(48, LabelFitPolicy.tileWidth(48, 8.4f, 0f, 7f, paddingPx = 10, maxPx = 96))
    }
    @Test
    fun `a shrunk size that still leaves the word too wide steps down until it fits`() {
        // Proportional estimate 7.06px, but rounded glyphs make the word 42px there and 40px at 6.75.
        val widths = mapOf(7.06f to 42f, 6.81f to 41f, 6.56f to 40f)
        val estimate = LabelFitPolicy.Fit(7.06f, singleLine = false)
        val fit = LabelFitPolicy.settle(estimate, requested = 8.4f, available = 40f, min = 6.5f) { size ->
            widths.entries.firstOrNull { kotlin.math.abs(it.key - size) < 0.01f }?.value ?: 39f
        }
        assertEquals(6.56f, fit.size, 0.001f)
        assertFalse(fit.singleLine)
    }

    @Test
    fun `settling never goes under the floor`() {
        val estimate = LabelFitPolicy.Fit(7.1f, singleLine = false)
        val fit = LabelFitPolicy.settle(estimate, requested = 8.4f, available = 40f, min = 7f) { 45f }
        assertEquals(LabelFitPolicy.Fit(7f, singleLine = true), fit)
    }

    @Test
    fun `a label at its requested size or already on one line is left as it is`() {
        val atRequested = LabelFitPolicy.Fit(8.4f, singleLine = false)
        assertEquals(atRequested, LabelFitPolicy.settle(atRequested, 8.4f, 40f, 7f) { 99f })
        val ellipsized = LabelFitPolicy.Fit(7f, singleLine = true)
        assertEquals(ellipsized, LabelFitPolicy.settle(ellipsized, 8.4f, 40f, 7f) { 99f })
    }
}
