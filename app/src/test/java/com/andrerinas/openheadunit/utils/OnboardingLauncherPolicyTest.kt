package com.andrerinas.openheadunit.utils

import com.andrerinas.openheadunit.utils.OnboardingLauncherPolicy.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingLauncherPolicyTest {

    @Test
    fun `nothing happens without a pending request`() {
        assertEquals(Outcome.NONE, OnboardingLauncherPolicy.resolve(requestPending = false, roleHeld = false))
        assertEquals(Outcome.NONE, OnboardingLauncherPolicy.resolve(requestPending = false, roleHeld = true))
    }

    @Test
    fun `a granted role stores launcher mode`() {
        assertEquals(Outcome.COMMIT, OnboardingLauncherPolicy.resolve(requestPending = true, roleHeld = true))
    }

    @Test
    fun `a declined or unanswered request turns the switch back off`() {
        assertEquals(Outcome.REVERT, OnboardingLauncherPolicy.resolve(requestPending = true, roleHeld = false))
    }

    @Test
    fun `a home role already held opens the wizard with launcher mode on`() {
        assertTrue(OnboardingLauncherPolicy.launcherModeOnOpen(stored = false, roleHeld = true))
        assertTrue(OnboardingLauncherPolicy.launcherModeOnOpen(stored = true, roleHeld = false))
        assertFalse(OnboardingLauncherPolicy.launcherModeOnOpen(stored = false, roleHeld = false))
    }

    @Test
    fun `finishing never drops the alias while the app is home`() {
        assertFalse(OnboardingLauncherPolicy.syncsAlias(stored = false, roleHeld = true))
        assertTrue(OnboardingLauncherPolicy.syncsAlias(stored = true, roleHeld = true))
        assertTrue(OnboardingLauncherPolicy.syncsAlias(stored = false, roleHeld = false))
        assertTrue(OnboardingLauncherPolicy.syncsAlias(stored = true, roleHeld = false))
    }

    @Test
    fun `the reopen note shows only when reopen was on and offered with the launcher switch on`() {
        assertTrue(OnboardingLauncherPolicy.showsReopenNote(launcherSwitchOn = true, reopenOn = true, reopenOffered = true))
        assertFalse(OnboardingLauncherPolicy.showsReopenNote(launcherSwitchOn = false, reopenOn = true, reopenOffered = true))
        assertFalse(OnboardingLauncherPolicy.showsReopenNote(launcherSwitchOn = true, reopenOn = false, reopenOffered = true))
        assertFalse(OnboardingLauncherPolicy.showsReopenNote(launcherSwitchOn = true, reopenOn = true, reopenOffered = false))
    }
}
