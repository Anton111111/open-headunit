package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import android.os.SystemClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito.mockStatic

class ZbtDiscoveryWaitTest {
    private lateinit var clock: org.mockito.MockedStatic<SystemClock>
    private var elapsedMs = 1_000L
    private val decision = ZbtDiscoveryPolicy.Decision(ZbtDiscoveryPolicy.Mode.IDENTITY, "QF")

    @Before fun setup() {
        clock = mockStatic(SystemClock::class.java)
        clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { elapsedMs }
        reset()
    }
    @After fun cleanup() { reset(); clock.close() }
    private fun reset() {
        ZbtDaemonReachability.forget()
        ZbtDaemonReachability.setCarrierLive(false)
        ZbtDaemonReachability.setCarrierWantsClient(false)
        ZbtDiscovery.beginArming()
    }

    @Test fun stuckLiveOrReopeningCarrierTimesOutWithoutProbingOrPoisoningIdentity() = runBlocking {
        for (live in listOf(false, true)) {
            reset()
            ZbtDaemonReachability.setCarrierLive(live)
            ZbtDaemonReachability.setCarrierWantsClient(!live)
            val measurement = ZbtDiscovery.measurement(decision)
            val startedAt = elapsedMs
            val result = measurement.awaitResolution(
                keepTrying = { true }, pause = { elapsedMs += it },
                probe = { fail("must not dial through a carrier") },
            )
            assertEquals(ZbtDiscovery.Measurement.Completion.CARRIER_BUSY, result)
            assertEquals(10_000L, elapsedMs - startedAt)
            ZbtDiscovery.deferIdentityForArming(decision)
            assertEquals(false, measurement.snapshot().reachable)
            assertNull(measurement.cached())
            // Repeated routing/UI reads stay on fallback even if the old carrier finally exits.
            ZbtDaemonReachability.setCarrierLive(false)
            ZbtDaemonReachability.setCarrierWantsClient(false)
            assertEquals(false, measurement.snapshot().reachable)
            ZbtDiscovery.beginArming()
            assertNull(measurement.snapshot().reachable)
        }
    }

    @Test fun carrierReleaseBeforeDeadlineAllowsIdentityAndNeverUsesLegacyCache() = runBlocking {
        ZbtDaemonReachability.setCarrierWantsClient(true)
        ZbtDaemonReachability.record(true)
        val measurement = ZbtDiscovery.measurement(decision)
        var probes = 0
        val result = measurement.awaitResolution(
            keepTrying = { true },
            pause = { elapsedMs += it; ZbtDaemonReachability.setCarrierWantsClient(false) },
            probe = {
                probes++
                ZbtDaemonReachability.resolve(requireIdentity = true,
                    identityDial = { ZbtIdentityProbe.Outcome.IDENTIFIED })
            },
        )
        assertEquals(ZbtDiscovery.Measurement.Completion.SETTLED, result)
        assertEquals(1, probes)
        assertEquals(true, measurement.snapshot().reachable)
    }

    @Test fun repeatedOwnershipRacesShareOneDeadline() = runBlocking {
        val measurement = ZbtDiscovery.measurement(decision)
        var probes = 0
        val result = measurement.awaitResolution(
            keepTrying = { true }, pause = { elapsedMs += it },
            // The carrier can win between waitsForCarrier and resolve. No answer is cached.
            probe = { probes++ },
        )
        assertEquals(ZbtDiscovery.Measurement.Completion.CARRIER_BUSY, result)
        assertEquals(40, probes)
        assertNull(measurement.cached())
    }

    @Test fun cancelledArmingDoesNotProbeOrChooseFallback() = runBlocking {
        ZbtDaemonReachability.setCarrierWantsClient(true)
        var active = true
        val measurement = ZbtDiscovery.measurement(decision)
        val result = measurement.awaitResolution(
            keepTrying = { active }, pause = { active = false },
            probe = { fail("cancelled discovery probed") },
        )
        assertEquals(ZbtDiscovery.Measurement.Completion.CANCELLED, result)
        assertNull(measurement.snapshot().reachable)
    }

    @Test fun establishedMeasurementRetainsItsExistingRetryWindow() = runBlocking {
        val measurement = ZbtDiscovery.measurement(
            ZbtDiscoveryPolicy.Decision(ZbtDiscoveryPolicy.Mode.REACHABILITY, "external"))
        val result = measurement.awaitResolution(
            keepTrying = { true }, pause = { fail("settled legacy check polled again") },
            probe = { elapsedMs += 30_000; ZbtDaemonReachability.record(false) },
        )
        assertEquals(ZbtDiscovery.Measurement.Completion.SETTLED, result)
        assertEquals(false, measurement.snapshot().reachable)
    }
}
