package com.andrerinas.openheadunit.connection.wifi

/**
 * Main-thread ownership stamp for work which waits for transport teardown. A launcher may
 * be re-armed without changing its instance, so object identity alone is insufficient.
 * Capture before suspending; compare and mutate the launcher in one non-suspending main
 * thread action. A stale completion must not stop the new shared server or network.
 */
internal class WirelessCleanupGeneration {
    private var current = Any()
    private var armedWhileDisconnected: Any? = null
    fun capture(): Any = current
    fun captureFor(disconnected: Any): Any? =
        if (armedWhileDisconnected === disconnected) null else current
    fun advance(disconnected: Any? = null) {
        current = Any()
        armedWhileDisconnected = disconnected
    }
    fun isCurrent(ticket: Any): Boolean = current === ticket
}
