package com.andrerinas.openheadunit.utils

/**
 * What the projection activity saw this session, carried across to the session-end record.
 *
 * The decoder already counts its own frames and fallbacks. The renderer recovery count lives in
 * the activity, which may be recreated or finished before the connection tears down, so it is
 * kept here for the length of the session and reset when the next one starts.
 */
object ProjectionHealthRecorder {

    data class RendererSnapshot(val recoveries: Int, val forcedSurface: Boolean)

    @Volatile
    private var recoveries = 0

    @Volatile
    private var forcedSurface = false

    fun reset() {
        recoveries = 0
        forcedSurface = false
    }

    fun noteRendererRecovery(didForceSurface: Boolean) {
        recoveries++
        if (didForceSurface) forcedSurface = true
    }

    fun snapshot(): RendererSnapshot = RendererSnapshot(recoveries, forcedSurface)
}
