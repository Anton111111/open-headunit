package com.andrerinas.openheadunit.decoder.video.preflight

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The eight bit frame number every clip carries in its bottom right corner.
 *
 * Eight white or black blocks are laid out as two rows of four, least significant bit first.
 * Reading them back is what turns "the decoder produced frames" into "the picture on screen is
 * the frame the decoder was given, in order". A dropped frame leaves a gap in the sequence; a
 * frame the decoder or the renderer corrupted usually loses the contrast the reader needs and
 * comes back unreadable.
 *
 * The geometry is the generator's: a 44 pixel block and a 48 pixel gap on a 1080 line picture,
 * with the grid anchored 260 pixels from the right edge and 330 from the bottom. Every smaller
 * clip is the same layout scaled by its height, which is why one formula covers all of them.
 */
object FrameMarker {

    data class Geometry(val x: Int, val y: Int, val block: Int, val gap: Int) {
        /** Moves the marker from clip coordinates into the video rectangle of a captured bitmap. */
        fun scaledTo(rect: Rect, sourceWidth: Int, sourceHeight: Int): Geometry {
            if (sourceWidth <= 0 || sourceHeight <= 0) return this
            val scale = min(rect.width.toDouble() / sourceWidth, rect.height.toDouble() / sourceHeight)
            fun scaled(value: Int) = (value * scale).roundToInt()
            return Geometry(
                x = rect.left + scaled(x),
                y = rect.top + scaled(y),
                block = max(1, scaled(block)),
                gap = max(1, scaled(gap)),
            )
        }
    }

    /** A rectangle in pixels, independent of android.graphics.Rect so the reader stays testable. */
    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    data class Reading(val frame: Int, val contrast: Int)

    /** The darkest an "on" block and the brightest an "off" block may be before it is unreadable. */
    const val MIN_CONTRAST = 64

    fun geometryFor(width: Int, height: Int): Geometry {
        if (height <= 0) return Geometry(0, 0, 1, 1)
        val scale = height / 1080.0
        fun scaled(value: Int) = (value * scale).roundToInt()
        return Geometry(
            x = width - scaled(260),
            y = height - scaled(330),
            block = max(1, scaled(44)),
            gap = max(1, scaled(48)),
        )
    }

    /** The area a source picture actually occupies inside a target bitmap. */
    fun fitRect(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Rect {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return Rect(0, 0, targetWidth.coerceAtLeast(0), targetHeight.coerceAtLeast(0))
        }
        val sourceAspect = sourceWidth.toDouble() / sourceHeight
        val targetAspect = targetWidth.toDouble() / targetHeight
        return if (targetAspect > sourceAspect) {
            val width = (targetHeight * sourceAspect).roundToInt()
            val left = (targetWidth - width) / 2
            Rect(left, 0, left + width, targetHeight)
        } else {
            val height = (targetWidth / sourceAspect).roundToInt()
            val top = (targetHeight - height) / 2
            Rect(0, top, targetWidth, top + height)
        }
    }

    /**
     * Reads the marker, averaging a small patch at the centre of each block so a single bad pixel
     * does not flip a bit. [luma] returns the brightness of one pixel, 0 to 255.
     *
     * Returns null when the blocks do not have enough contrast to tell white from black. An all
     * dark grid is frame zero, which is a real frame and is left to the caller to judge against
     * where it sampled.
     */
    fun read(
        luma: (x: Int, y: Int) -> Int,
        geometry: Geometry,
        maxX: Int,
        maxY: Int,
    ): Reading? {
        if (geometry.block <= 0 || geometry.gap <= 0 || maxX <= 0 || maxY <= 0) return null
        val radius = max(1, geometry.block / 4)
        var value = 0
        var minOn = 255
        var maxOff = 0
        var sawOn = false
        var sawOff = false

        for (bit in 0 until 8) {
            val centerX = geometry.x + (bit % 4) * geometry.gap + geometry.block / 2
            val centerY = geometry.y + (bit / 4) * geometry.gap + geometry.block / 2
            var sum = 0
            var count = 0
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    val x = centerX + dx
                    val y = centerY + dy
                    if (x in 0 until maxX && y in 0 until maxY) {
                        sum += luma(x, y)
                        count++
                    }
                }
            }
            if (count == 0) return null
            val average = sum / count
            if (average > 128) {
                value = value or (1 shl bit)
                minOn = min(minOn, average)
                sawOn = true
            } else {
                maxOff = max(maxOff, average)
                sawOff = true
            }
        }

        if (!sawOn) return Reading(0, 255 - maxOff)
        if (!sawOff) return null
        val contrast = minOn - maxOff
        if (contrast < MIN_CONTRAST) return null
        return Reading(value, contrast)
    }
}
