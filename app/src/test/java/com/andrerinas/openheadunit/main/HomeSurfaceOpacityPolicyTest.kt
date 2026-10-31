package com.andrerinas.openheadunit.main

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSurfaceOpacityPolicyTest {

    private val tile = 0xFF1C212A.toInt()

    @Test
    fun `the default leaves a color as it is`() {
        assertEquals(tile, HomeSurfaceOpacityPolicy.apply(tile, HomeSurfaceOpacityPolicy.DEFAULT))
    }

    @Test
    fun `half opacity halves the alpha and keeps the rgb`() {
        assertEquals(0x801C212A.toInt(), HomeSurfaceOpacityPolicy.apply(tile, 50))
    }

    @Test
    fun `a color that is already translucent is scaled, not replaced`() {
        assertEquals(0x401C212A, HomeSurfaceOpacityPolicy.apply(0x801C212A.toInt(), 50))
    }

    @Test
    fun `a stored value out of range is clamped`() {
        assertEquals(HomeSurfaceOpacityPolicy.MIN, HomeSurfaceOpacityPolicy.clamp(-10))
        assertEquals(HomeSurfaceOpacityPolicy.MAX, HomeSurfaceOpacityPolicy.clamp(250))
        assertEquals(0x001C212A, HomeSurfaceOpacityPolicy.apply(tile, -10))
    }

    @Test
    fun `a pressed fill never fades below its floor`() {
        assertEquals(0x661C212A, HomeSurfaceOpacityPolicy.pressed(tile, 0))
        assertEquals(0x801C212A.toInt(), HomeSurfaceOpacityPolicy.pressed(tile, 50))
        assertEquals(tile, HomeSurfaceOpacityPolicy.pressed(tile, HomeSurfaceOpacityPolicy.DEFAULT))
    }
}
