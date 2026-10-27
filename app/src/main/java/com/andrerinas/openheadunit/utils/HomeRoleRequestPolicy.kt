package com.andrerinas.openheadunit.utils

/**
 * Tells a home-role request the system ignored from one a person declined. An ignored request
 * closes before its dialog can be answered, so only that one falls back to the Home settings.
 */
object HomeRoleRequestPolicy {

    const val IGNORED_WITHIN_MS = 1000L

    fun opensHomeSettings(roleHeld: Boolean, elapsedMs: Long): Boolean =
        !roleHeld && elapsedMs in 0 until IGNORED_WITHIN_MS
}
