package com.andrerinas.openheadunit.decoder.video.preflight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextureContentAreaTest {

    /** Column-major 4x4 with only scale and translate, the way SurfaceTexture builds it. */
    private fun matrix(sx: Float, sy: Float, tx: Float, ty: Float) = FloatArray(16).apply {
        this[0] = sx
        this[5] = sy
        this[10] = 1f
        this[12] = tx
        this[13] = ty
        this[15] = 1f
    }

    @Test
    fun `a picture that fills the buffer needs no correction`() {
        assertNull(TextureContentArea.fromTransform(matrix(1f, -1f, 0f, 1f), 320, 180))
    }

    @Test
    fun `the one texel inset alone needs no correction`() {
        val inset = 1f / 800f
        assertNull(TextureContentArea.fromTransform(matrix(1f - inset, -(1f - inset), inset / 2, 1f - inset / 2), 320, 180))
    }

    @Test
    fun `800 wide picture in an 896 wide buffer sits on the left`() {
        // Android's software AVC decoder on API 36: 800x480 decoded into a 896 wide buffer. The
        // capture showed the picture in the left 285 of 320 pixels and green padding beside it.
        val area = TextureContentArea.fromTransform(matrix(800f / 896f, -1f, 0f, 1f), 320, 180)

        assertEquals(TextureContentArea.Area(0, 0, 286, 180), area)
    }

    @Test
    fun `720 lines in a 736 line buffer keep the picture at the top`() {
        val area = TextureContentArea.fromTransform(matrix(1f, -720f / 736f, 0f, 720f / 736f), 320, 180)

        assertEquals(TextureContentArea.Area(0, 0, 320, 176), area)
    }

    @Test
    fun `a rotated transform is left alone`() {
        val rotated = matrix(0f, 0f, 0f, 1f).apply {
            this[1] = 1f
            this[4] = -1f
        }
        assertNull(TextureContentArea.fromTransform(rotated, 320, 180))
    }

    @Test
    fun `a short or empty matrix is left alone`() {
        assertNull(TextureContentArea.fromTransform(FloatArray(4), 320, 180))
        assertNull(TextureContentArea.fromTransform(matrix(0f, 0f, 0f, 0f), 320, 180))
    }
}
