package com.andrerinas.openheadunit.utils

import android.graphics.Color
import androidx.annotation.ColorInt

object ColorUtils {

    /**
     * Darkens a given color by multiplying its HSV Value (brightness) by [factor].
     */
    @ColorInt
    fun darkenColor(@ColorInt color: Int, factor: Float = 0.65f): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = (hsv[2] * factor).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    /**
     * Safely parses a hex color string (e.g. "#2CC6F2" or "2CC6F2").
     * Returns [defaultColor] if parsing fails.
     */
    @ColorInt
    fun parseColorSafely(hexString: String, defaultColor: Int = 0): Int {
        val trimmed = hexString.trim()
        if (trimmed.isEmpty()) return defaultColor
        val formatted = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
        return try {
            Color.parseColor(formatted)
        } catch (e: Exception) {
            defaultColor
        }
    }
}
