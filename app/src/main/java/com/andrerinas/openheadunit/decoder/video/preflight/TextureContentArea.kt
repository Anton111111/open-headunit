package com.andrerinas.openheadunit.decoder.video.preflight

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where the decoded picture sits inside a TextureView capture.
 *
 * A decoder may hand over buffers larger than the picture, padded to its own alignment (Android's
 * software AVC decoder rounds 800 up to 896, for one), and say which part is the picture through
 * the crop. The SurfaceTexture's transform matrix carries that crop, and the screen honours it,
 * but TextureView.getBitmap draws the whole buffer: the picture lands in the top left of the
 * capture with the padding, often green, beside it. Reading the marker from the full capture then
 * samples the wrong pixels on a unit that plays perfectly.
 *
 * The matrix is the column-major 4x4 from SurfaceTexture.getTransformMatrix. Its scale and
 * translation map the unit square onto the picture's texture coordinates, usually with a vertical
 * flip, so the picture's share of the buffer is the span those coordinates cover on each axis.
 */
object TextureContentArea {

    data class Area(val left: Int, val top: Int, val width: Int, val height: Int)

    /**
     * The picture's area in a [bitmapWidth] x [bitmapHeight] capture, or null when the matrix says
     * the picture fills it (nothing to correct) or the matrix is not a plain scale and translate.
     */
    fun fromTransform(matrix: FloatArray, bitmapWidth: Int, bitmapHeight: Int): Area? {
        if (matrix.size < 16 || bitmapWidth <= 0 || bitmapHeight <= 0) return null
        // A rotation or shear puts weight off the diagonal; only scale and translate are expected.
        if (matrix[1] != 0f || matrix[4] != 0f) return null
        val u0 = matrix[12]
        val u1 = matrix[12] + matrix[0]
        val v0 = matrix[13]
        val v1 = matrix[13] + matrix[5]
        val left = min(u0, u1).coerceIn(0f, 1f)
        val right = max(u0, u1).coerceIn(0f, 1f)
        val top = min(v0, v1).coerceIn(0f, 1f)
        val bottom = max(v0, v1).coerceIn(0f, 1f)
        if (right - left <= 0f || bottom - top <= 0f) return null
        // SurfaceTexture insets the crop by about one texel so filtering never reads the padding.
        // Anything that close to the full buffer is the full buffer.
        if (right - left > FULL_SPAN && bottom - top > FULL_SPAN) return null
        return Area(
            left = (left * bitmapWidth).roundToInt(),
            top = (top * bitmapHeight).roundToInt(),
            width = ((right - left) * bitmapWidth).roundToInt(),
            height = ((bottom - top) * bitmapHeight).roundToInt(),
        )
    }

    private const val FULL_SPAN = 0.98f
}
