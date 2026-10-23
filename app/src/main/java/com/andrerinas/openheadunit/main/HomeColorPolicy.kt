package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.main.HomeThemePolicy.Mode

/**
 * Where a connection's color comes from on the new home screen. The colors picked in
 * Customization are accents there: the main button always wears one, a bar tile only while its
 * connection is on its way or up. Pure, so the rules are a unit test rather than a head unit.
 */
object HomeColorPolicy {

    enum class Source {
        /** The design's own color for the connection. */
        DEFAULT,
        /** The user's color, as picked. */
        CUSTOM,
        /** The user's color turned down a little, so extreme dark stays dark. */
        CUSTOM_DIMMED,
        /** No color at all: the user asked for monochrome at night. */
        MONOCHROME
    }

    /** [customColor] is 0 when the user kept the default, which is how Settings stores it. */
    fun source(customColor: Int, mode: Mode, monochromeAtNight: Boolean): Source = when {
        mode != Mode.DAY && monochromeAtNight -> Source.MONOCHROME
        customColor == 0 -> Source.DEFAULT
        mode == Mode.EXTREME_DARK -> Source.CUSTOM_DIMMED
        else -> Source.CUSTOM
    }
}
