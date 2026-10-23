package com.andrerinas.openheadunit.main

import org.junit.Assert.assertEquals
import org.junit.Test

class BarFitPolicyTest {

    // A short head unit: 320dp of height, the tiles at 80dp each plus 56dp of Exit and 48dp of gaps.
    private val available = 320
    private val tiles = 296
    private val fixed = 48

    @Test
    fun `the user's scale is kept when everything fits`() {
        assertEquals(1.0f, BarFitPolicy.tileScale(600, tiles, fixed, 100), 0.001f)
    }

    @Test
    fun `the tiles shrink to the room the screen leaves`() {
        // 320 - 48 = 272 for tiles that need 296: 0.918 of their size.
        assertEquals(0.919f, BarFitPolicy.tileScale(available, tiles, fixed, 100), 0.001f)
    }

    @Test
    fun `a user scale above 100 is respected when it still fits`() {
        assertEquals(1.2f, BarFitPolicy.tileScale(600, tiles, fixed, 120), 0.001f)
    }

    @Test
    fun `a scale the screen cannot hold falls back to what fits`() {
        // At 120% the tiles would need 355dp and the screen leaves 272: 0.918, the largest that fits.
        assertEquals(0.919f, BarFitPolicy.tileScale(available, tiles, fixed, 120), 0.001f)
    }

    @Test
    fun `the tiles never shrink past the floor`() {
        assertEquals(BarFitPolicy.MIN_SCALE, BarFitPolicy.tileScale(100, tiles, fixed, 100), 0.001f)
    }

    @Test
    fun `a screen that has not been laid out yet keeps the user's scale`() {
        assertEquals(1.0f, BarFitPolicy.tileScale(0, tiles, fixed, 100), 0.001f)
        assertEquals(1.0f, BarFitPolicy.tileScale(available, 0, fixed, 100), 0.001f)
    }
}
