package com.andrerinas.openheadunit.connection.wifi.direct

/**
 * Whether a service destroy leaves the Native AA group up for the next start to read, and whether
 * that start may read it. A phone still on a kept group reconnects with no scan and no join. A re-arm
 * inside one service still rebuilds the group, because a forced re-arm is how a broken one recovers.
 */
object NativeGroupKeepPolicy {

    /** Below API 29 a pending rename is applied only by a create, so that group is not kept. */
    fun keepsOnDestroy(
        userStop: Boolean,
        nativeWifiDirect: Boolean,
        stableIdentity: Boolean,
        rotationPending: Boolean,
    ): Boolean = !userStop && nativeWifiDirect && stableIdentity && !rotationPending

    /**
     * Whether a surviving group still matches the band and channel asked for. A frequency of 0 is
     * unreadable (below API 29) and counts as a match, which is how a survivor was always read there.
     */
    fun matchesSetting(band: P2pBandPreference, fiveGhzChannel: Int, frequencyMhz: Int): Boolean {
        if (frequencyMhz <= 0) return true
        val on5Ghz = frequencyMhz >= 4900
        return when (band) {
            P2pBandPreference.FORCE_2_4GHZ -> !on5Ghz
            P2pBandPreference.FORCE_5GHZ -> on5Ghz && channelMatches(fiveGhzChannel, frequencyMhz)
            P2pBandPreference.AUTO -> !on5Ghz || channelMatches(fiveGhzChannel, frequencyMhz)
        }
    }

    private fun channelMatches(fiveGhzChannel: Int, frequencyMhz: Int): Boolean =
        fiveGhzChannel == 0 || frequencyMhz == 5000 + 5 * fiveGhzChannel
}
