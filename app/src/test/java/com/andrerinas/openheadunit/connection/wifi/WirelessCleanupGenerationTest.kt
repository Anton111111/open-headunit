package com.andrerinas.openheadunit.connection.wifi

import org.junit.Assert.*
import org.junit.Test

class WirelessCleanupGenerationTest {
    @Test fun `Start after terminal publication but before observer invalidates old cleanup`() {
        val generations = WirelessCleanupGeneration()
        val terminal = Any()
        // CommManager publishes D, explicit Start runs, then the Main observer processes D.
        generations.advance(terminal)
        assertNull(generations.captureFor(terminal))
        val laterTerminal = Any()
        assertNotNull(generations.captureFor(laterTerminal))
    }

    @Test fun `Start during teardown wait invalidates captured owner even on same launcher`() {
        val generations = WirelessCleanupGeneration()
        val terminal = Any()
        val ticket = generations.captureFor(terminal)!!
        generations.advance(terminal)
        assertFalse(generations.isCurrent(ticket))
    }

    @Test fun `ordinary terminal cleanup retains ownership until another arm`() {
        val generations = WirelessCleanupGeneration()
        generations.advance(Any())
        val ticket = generations.captureFor(Any())!!
        assertTrue(generations.isCurrent(ticket))
    }
}
