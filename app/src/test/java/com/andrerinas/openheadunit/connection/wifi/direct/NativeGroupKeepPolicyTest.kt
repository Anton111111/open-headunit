package com.andrerinas.openheadunit.connection.wifi.direct

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeGroupKeepPolicyTest {

    @Test
    fun `a destroy nobody asked for keeps a stable Native group`() {
        assertTrue(keeps())
    }

    @Test
    fun `the user's own stop always removes the group`() {
        assertFalse(keeps(userStop = true))
    }

    /** The next start reads a survivor only with a kept identity, so keeping one without it buys nothing. */
    @Test
    fun `nothing is kept without a kept identity or off the Native WiFi Direct route`() {
        assertFalse(keeps(stableIdentity = false))
        assertFalse(keeps(nativeWifiDirect = false))
    }

    /** Below API 29 only a create applies a rename, and an adopt would keep the old name on the air. */
    @Test
    fun `a pending identity rotation is not kept`() {
        assertFalse(keeps(rotationPending = true))
    }

    private fun keeps(
        userStop: Boolean = false,
        nativeWifiDirect: Boolean = true,
        stableIdentity: Boolean = true,
        rotationPending: Boolean = false,
    ) = NativeGroupKeepPolicy.keepsOnDestroy(userStop, nativeWifiDirect, stableIdentity, rotationPending)

    @Test
    fun `an unreadable frequency matches any setting`() {
        for (band in P2pBandPreference.entries) {
            assertTrue(NativeGroupKeepPolicy.matchesSetting(band, fiveGhzChannel = 149, frequencyMhz = 0))
        }
    }

    @Test
    fun `a forced band rejects a group on the other band`() {
        assertFalse(NativeGroupKeepPolicy.matchesSetting(P2pBandPreference.FORCE_2_4GHZ, 0, 5180))
        assertTrue(NativeGroupKeepPolicy.matchesSetting(P2pBandPreference.FORCE_2_4GHZ, 0, 2437))
        assertFalse(NativeGroupKeepPolicy.matchesSetting(P2pBandPreference.FORCE_5GHZ, 0, 2437))
        assertTrue(NativeGroupKeepPolicy.matchesSetting(P2pBandPreference.FORCE_5GHZ, 0, 5745))
    }

    /** Auto takes what the platform gave when 5 GHz was refused, so a 2.4 GHz survivor is still its own. */
    @Test
    fun `auto accepts either band`() {
        assertTrue(NativeGroupKeepPolicy.matchesSetting(P2pBandPreference.AUTO, 0, 2437))
        assertTrue(NativeGroupKeepPolicy.matchesSetting(P2pBandPreference.AUTO, 0, 5180))
    }

    @Test
    fun `a pinned channel rejects a 5 GHz group on another channel`() {
        assertTrue(NativeGroupKeepPolicy.matchesSetting(P2pBandPreference.AUTO, 149, 5745))
        assertFalse(NativeGroupKeepPolicy.matchesSetting(P2pBandPreference.AUTO, 149, 5180))
        assertFalse(NativeGroupKeepPolicy.matchesSetting(P2pBandPreference.FORCE_5GHZ, 36, 5240))
        assertTrue(NativeGroupKeepPolicy.matchesSetting(P2pBandPreference.FORCE_5GHZ, 36, 5180))
    }
}
