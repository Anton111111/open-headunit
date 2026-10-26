package com.andrerinas.openheadunit.main

import kotlin.math.roundToInt

/**
 * How see-through the home's panels, tiles, buttons and their borders are, so the driver's
 * background image shows behind them. Text, icons and the focus ring stay opaque.
 */
object HomeSurfaceOpacityPolicy {

    const val MIN = 0
    const val MAX = 100
    const val DEFAULT = MAX
    const val STEP = 5

    /** Below Android 5 a press or a D-pad focus shows only as the pressed fill, so it never fades past this. */
    const val PRESSED_MIN = 40

    fun clamp(percent: Int): Int = percent.coerceIn(MIN, MAX)

    /** [color] with its own alpha scaled by [percent]; the RGB is unchanged. */
    fun apply(color: Int, percent: Int): Int {
        val alpha = (color ushr 24 and 0xFF) * clamp(percent) / 100.0
        return (color and 0x00FFFFFF) or (alpha.roundToInt() shl 24)
    }

    /** [apply] for a pressed or focused fill, held at [PRESSED_MIN] or above. */
    fun pressed(color: Int, percent: Int): Int = apply(color, maxOf(clamp(percent), PRESSED_MIN))
}
