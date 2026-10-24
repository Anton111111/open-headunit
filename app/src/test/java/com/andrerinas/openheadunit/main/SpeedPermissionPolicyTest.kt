package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.main.SpeedPermissionPolicy.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedPermissionPolicyTest {

    @Test
    fun grantedShowsTheSpeedWhateverTheRationale() {
        assertEquals(Outcome.SHOW_SPEED, SpeedPermissionPolicy.afterRequest(granted = true, canAskAgain = false))
        assertEquals(Outcome.SHOW_SPEED, SpeedPermissionPolicy.afterRequest(granted = true, canAskAgain = true))
    }

    @Test
    fun deniedOnceOnlyExplains() {
        assertEquals(Outcome.EXPLAIN, SpeedPermissionPolicy.afterRequest(granted = false, canAskAgain = true))
    }

    @Test
    fun deniedForGoodPointsToTheAppSettings() {
        assertEquals(Outcome.OPEN_APP_SETTINGS, SpeedPermissionPolicy.afterRequest(granted = false, canAskAgain = false))
    }
}
