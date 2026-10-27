package com.andrerinas.openheadunit.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRoleRequestPolicyTest {

    @Test
    fun `a request closed at once with no role opens the Home settings`() {
        assertTrue(HomeRoleRequestPolicy.opensHomeSettings(roleHeld = false, elapsedMs = 40))
    }

    @Test
    fun `a request a person declined opens nothing`() {
        assertFalse(HomeRoleRequestPolicy.opensHomeSettings(roleHeld = false, elapsedMs = 4000))
    }

    @Test
    fun `a granted role opens nothing`() {
        assertFalse(HomeRoleRequestPolicy.opensHomeSettings(roleHeld = true, elapsedMs = 40))
    }

    @Test
    fun `the bound is exclusive`() {
        assertFalse(HomeRoleRequestPolicy.opensHomeSettings(false, HomeRoleRequestPolicy.IGNORED_WITHIN_MS))
    }

    @Test
    fun `a clock that went backwards opens nothing`() {
        assertFalse(HomeRoleRequestPolicy.opensHomeSettings(roleHeld = false, elapsedMs = -1))
    }
}
