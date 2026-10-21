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
}
