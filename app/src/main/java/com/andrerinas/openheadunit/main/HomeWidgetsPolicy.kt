package com.andrerinas.openheadunit.main

import kotlin.math.roundToInt

/**
 * The widgets the home screen keeps under the main button, in the order they are shown.
 *
 * Stored as one comma separated string of App Widget ids. Two is the most the row can hold and
 * still leave the main button room to breathe, so a third is refused rather than wrapped.
 *
 * Pure, so the slot rules are unit tests rather than taps on a head unit. The Android half of a
 * widget, the host that draws it, lives in [HomeWidgets].
 */
object HomeWidgetsPolicy {

    const val MAX_WIDGETS = 2

    private const val SEPARATOR = ","

    /** Whatever was stored, never more than [MAX_WIDGETS], so a corrupt value never breaks the row. */
    fun decode(stored: String): List<Int> = stored.split(SEPARATOR)
        .mapNotNull { it.trim().toIntOrNull() }
        .take(MAX_WIDGETS)

    fun encode(ids: List<Int>): String = ids.take(MAX_WIDGETS).joinToString(SEPARATOR)

    /** @return [ids] with [id] at the end, or null when the row is already full. */
    fun add(ids: List<Int>, id: Int): List<Int>? = when {
        id in ids -> ids
        ids.size >= MAX_WIDGETS -> null
        else -> ids + id
    }

    fun remove(ids: List<Int>, id: Int): List<Int> = ids.filterNot { it == id }

    /** A box in pixels as the dp a widget reads its size in. */
    fun toDp(px: Int, density: Float): Int =
        if (density <= 0f) px.coerceAtLeast(0) else (px.coerceAtLeast(0) / density).roundToInt()
}
