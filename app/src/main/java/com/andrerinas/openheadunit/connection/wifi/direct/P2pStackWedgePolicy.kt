package com.andrerinas.openheadunit.connection.wifi.direct

/**
 * Whether WiFi Direct is wedged: WiFi on, the stack keeps reporting disabled and our creates keep
 * failing, until WiFi itself restarts (measured on a MediaTek Android 10 unit). Another app cycling
 * the stack looks alike, so [P2pInterfaceChurnPolicy] seeing churn rules this out. Times are
 * `SystemClock.elapsedRealtime()`, which a head unit setting its clock after boot cannot move.
 */
object P2pStackWedgePolicy {

    /** How far back the two counts look. */
    const val WINDOW_MS = 60_000L

    /** Disabled broadcasts within [WINDOW_MS] before the stack counts as cycling. */
    const val MIN_DISABLE_EDGES = 2

    /** Failed creates within [WINDOW_MS] before the cycling counts as blocking us. */
    const val MIN_CREATE_FAILURES = 3

    /** No second automatic restart sooner than this after the last one. */
    const val RESTART_COOLDOWN_MS = 120_000L

    /** At most this many automatic restarts in any hour, so a unit that never recovers stops trying. */
    const val MAX_RESTARTS_PER_HOUR = 3

    const val HOUR_MS = 3_600_000L

    /** Run through root or this unit's ADB; both can call `svc`. The pause lets the supplicant exit. */
    const val RESTART_COMMAND = "svc wifi disable && sleep 3 && svc wifi enable"

    /** How many of [times] fall within [windowMs] before [now]. A time after [now] is a clock jump and is ignored. */
    fun countRecent(times: Collection<Long>, now: Long, windowMs: Long = WINDOW_MS): Int =
        times.count { val age = now - it; age in 0..windowMs }

    /** Most stamps kept per deque; well above any count that matters, so the log can say "at least". */
    const val MAX_STAMPS = 512

    /**
     * True when WiFi is on, its Direct stack keeps reporting disabled and our creates keep failing.
     * Never while another app is cycling the stack: that case has its own banner and its own remedy.
     */
    fun isWedged(
        wifiEnabled: Boolean,
        disableEdgesInWindow: Int,
        createFailuresInWindow: Int,
        foreignChurnSeen: Boolean = false,
    ): Boolean =
        wifiEnabled &&
            !foreignChurnSeen &&
            disableEdgesInWindow >= MIN_DISABLE_EDGES &&
            createFailuresInWindow >= MIN_CREATE_FAILURES

    /** Whether [P2pInterfaceChurnPolicy] judged a window as foreign churn within [WINDOW_MS] of [now]. */
    fun foreignChurnRecent(now: Long, lastForeignChurnAtMs: Long): Boolean =
        lastForeignChurnAtMs > 0L && (now - lastForeignChurnAtMs) in 0..WINDOW_MS

    /**
     * Whether an automatic restart may use this route. Root counts once granted; ADB only after a
     * real command ran at setup, so an unpaired key never raises "Allow USB debugging?" on the road.
     */
    fun mayRestartAutomatically(viaRoot: Boolean, viaAdb: Boolean, adbVerified: Boolean): Boolean =
        viaRoot || (viaAdb && adbVerified)

    /**
     * Whether an automatic restart may run now: never without the opt-in, since it switches WiFi
     * off for every app, and never during a live session, which proves the stack works.
     */
    fun mayAutoRestart(
        optedIn: Boolean,
        sessionConnected: Boolean,
        now: Long,
        restartTimes: Collection<Long>,
    ): Boolean {
        if (!optedIn || sessionConnected) return false
        val last = restartTimes.maxOrNull()
        if (last != null && (now - last) in 0 until RESTART_COOLDOWN_MS) return false
        return countRecent(restartTimes, now, HOUR_MS) < MAX_RESTARTS_PER_HOUR
    }
}
