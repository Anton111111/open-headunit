package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.main.HomeColorPolicy.Source
import com.andrerinas.openheadunit.main.HomeThemePolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeColorPolicyTest {

    private val red = 0xFFE74C3C.toInt()

    @Test
    fun `no color picked keeps the design's own`() {
        Mode.values().forEach { mode ->
            assertEquals(Source.DEFAULT, HomeColorPolicy.source(0, mode, monochromeAtNight = false))
        }
    }

    @Test
    fun `a picked color is used as is by day and in the dark look`() {
        assertEquals(Source.CUSTOM, HomeColorPolicy.source(red, Mode.DAY, monochromeAtNight = false))
        assertEquals(Source.CUSTOM, HomeColorPolicy.source(red, Mode.DARK, monochromeAtNight = false))
    }

    @Test
    fun `extreme dark turns a picked color down`() {
        assertEquals(Source.CUSTOM_DIMMED, HomeColorPolicy.source(red, Mode.EXTREME_DARK, monochromeAtNight = false))
    }

    @Test
    fun `monochrome at night wins over any color, but only at night`() {
        assertEquals(Source.MONOCHROME, HomeColorPolicy.source(red, Mode.DARK, monochromeAtNight = true))
        assertEquals(Source.MONOCHROME, HomeColorPolicy.source(0, Mode.EXTREME_DARK, monochromeAtNight = true))
        assertEquals(Source.CUSTOM, HomeColorPolicy.source(red, Mode.DAY, monochromeAtNight = true))
    }
}
