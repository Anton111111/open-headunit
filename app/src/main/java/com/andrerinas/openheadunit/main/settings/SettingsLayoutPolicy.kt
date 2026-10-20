package com.andrerinas.openheadunit.main.settings

/**
 * Where a row sits inside its connected group of settings. The group reads as one card with
 * large outer corners and small inner ones, so the first and last rows round differently.
 */
enum class GroupPosition {
    SINGLE, TOP, MIDDLE, BOTTOM;

    companion object {
        fun of(isFirst: Boolean, isLast: Boolean): GroupPosition = when {
            isFirst && isLast -> SINGLE
            isFirst -> TOP
            isLast -> BOTTOM
            else -> MIDDLE
        }
    }
}

/** Pure layout decisions of the settings screen, kept out of the views so they can be tested. */
object SettingsLayoutPolicy {

    /**
     * Values up to this length sit at the trailing edge of the row, beside the title. Longer ones
     * (a description used as a value, a DPI with its canvas) wrap under the title instead, where
     * they can take several lines rather than being cut off.
     */
    const val MAX_INLINE_VALUE_LENGTH = 28

    fun showValueInline(value: CharSequence): Boolean =
        value.isNotBlank() && value.length <= MAX_INLINE_VALUE_LENGTH && value.none { it == '\n' }

    /** What the note above the list says about settings the connection choice is hiding. */
    enum class ScopeNotice { NONE, WIFI_HIDDEN, USB_HIDDEN, USB_AND_WIFI_HIDDEN }

    fun scopeNotice(showsUsb: Boolean, showsWifi: Boolean): ScopeNotice = when {
        showsUsb && showsWifi -> ScopeNotice.NONE
        showsUsb -> ScopeNotice.WIFI_HIDDEN
        showsWifi -> ScopeNotice.USB_HIDDEN
        else -> ScopeNotice.USB_AND_WIFI_HIDDEN
    }

    /**
     * The category to highlight in the rail for a list scrolled to [firstVisiblePosition]: the
     * last section whose header is at or above it. [headerPositions] is ascending. Returns -1
     * when there are no sections.
     */
    fun activeCategoryIndex(headerPositions: IntArray, firstVisiblePosition: Int): Int {
        if (headerPositions.isEmpty()) return -1
        var active = 0
        for (i in headerPositions.indices) {
            if (headerPositions[i] <= firstVisiblePosition) active = i else break
        }
        return active
    }
}
