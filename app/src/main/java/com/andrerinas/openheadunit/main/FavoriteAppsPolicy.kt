package com.andrerinas.openheadunit.main

/** What one favorite slot holds: nothing, an app, or one of that app's shortcuts. */
sealed class FavoriteTarget {
    object Empty : FavoriteTarget()

    data class App(val packageName: String) : FavoriteTarget()

    /** A deep link inside [packageName], named by [shortcutId] the way Android stores it. */
    data class Shortcut(val packageName: String, val shortcutId: String) : FavoriteTarget()
}

/**
 * The four favorite slots under the home screen's main button, kept in slot order. An empty slot
 * is shown as "+".
 *
 * Stored as one string, comma separated: a package name for an app, and "package|shortcut" for a
 * shortcut. The separator is only ever the first "|", so a shortcut id that contains one still
 * decodes to the same shortcut.
 *
 * Pure, so the slot rules are unit tests rather than taps on a head unit.
 */
object FavoriteAppsPolicy {

    const val SLOT_COUNT = 4

    private const val SEPARATOR = ","
    private const val SHORTCUT_SEPARATOR = "|"

    /** Always [SLOT_COUNT] slots, whatever was stored, so a corrupt value never breaks the row. */
    fun decode(stored: String): List<FavoriteTarget> =
        stored.split(SEPARATOR).map { decodeEntry(it.trim()) }.take(SLOT_COUNT)
            .let { it + List(SLOT_COUNT - it.size) { FavoriteTarget.Empty } }

    private fun decodeEntry(entry: String): FavoriteTarget {
        if (entry.isEmpty()) return FavoriteTarget.Empty
        val separator = entry.indexOf(SHORTCUT_SEPARATOR)
        // No separator, or one with nothing on either side of it: whatever is there is a package
        // name, and a package that does not resolve frees its slot like an uninstalled app.
        if (separator <= 0 || separator == entry.length - 1) return FavoriteTarget.App(entry)
        return FavoriteTarget.Shortcut(
            entry.substring(0, separator),
            entry.substring(separator + 1)
        )
    }

    fun encode(slots: List<FavoriteTarget>): String = slots.joinToString(SEPARATOR) { entry ->
        when (entry) {
            is FavoriteTarget.Empty -> ""
            is FavoriteTarget.App -> entry.packageName
            is FavoriteTarget.Shortcut -> entry.packageName + SHORTCUT_SEPARATOR + entry.shortcutId
        }
    }

    /** Puts [target] in [slot], taking it out of any other slot so it never shows twice. */
    fun place(slots: List<FavoriteTarget>, slot: Int, target: FavoriteTarget): List<FavoriteTarget> =
        slots.mapIndexed { index, current ->
            when {
                index == slot -> target
                current == target -> FavoriteTarget.Empty
                else -> current
            }
        }

    /** @return the slots with [target] in the first free one, or null when all four are taken. */
    fun add(slots: List<FavoriteTarget>, target: FavoriteTarget): List<FavoriteTarget>? {
        if (target == FavoriteTarget.Empty || target in slots) return slots
        val free = slots.indexOf(FavoriteTarget.Empty)
        return if (free < 0) null else place(slots, free, target)
    }

    fun remove(slots: List<FavoriteTarget>, slot: Int): List<FavoriteTarget> =
        slots.mapIndexed { index, current -> if (index == slot) FavoriteTarget.Empty else current }
}
