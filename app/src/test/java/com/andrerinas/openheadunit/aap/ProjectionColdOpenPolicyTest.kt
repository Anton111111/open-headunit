package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectionColdOpenPolicyTest {

    @Test
    fun `opened with nothing connected and nothing coming goes back home`() {
        assertEquals(
            ProjectionColdOpenPolicy.Action.LEAVE,
            ProjectionColdOpenPolicy.onOpenedWithoutSession(callerExpectsSession = false, settingsRestart = false)
        )
    }

    @Test
    fun `self mode opening the screen first still waits for its session`() {
        assertEquals(
            ProjectionColdOpenPolicy.Action.WAIT,
            ProjectionColdOpenPolicy.onOpenedWithoutSession(callerExpectsSession = true, settingsRestart = false)
        )
    }

    @Test
    fun `a session coming back after saving settings is waited for`() {
        assertEquals(
            ProjectionColdOpenPolicy.Action.WAIT,
            ProjectionColdOpenPolicy.onOpenedWithoutSession(callerExpectsSession = false, settingsRestart = true)
        )
    }
}
