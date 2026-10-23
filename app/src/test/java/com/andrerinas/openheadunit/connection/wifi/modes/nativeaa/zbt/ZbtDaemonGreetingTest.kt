package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import android.os.SystemClock
import org.mockito.Mockito.mockStatic
import org.junit.Before
import org.junit.After
import java.io.InputStream
import java.net.SocketTimeoutException
import com.andrerinas.openheadunit.utils.AppLog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ZbtDaemonGreetingTest {
    private lateinit var clock: org.mockito.MockedStatic<SystemClock>
    private var elapsedMs = 10_000L
    @Before fun setupClock() {
        clock = mockStatic(SystemClock::class.java)
        clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { elapsedMs }
    }
    @After fun closeClock() { clock.close() }

    @Test fun readsFrameBeforeElapsedRealtimeDeadline() {
        val logger = AppLog.LOGGER
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) = Unit
        }
        try {
            val frame = ZbtFraming.encodeFrame(byteArrayOf(8, 1), ZbtMessages.INIT_INFO)
            ZbtByteChannel(ByteArrayInputStream(frame), ByteArrayOutputStream()).use {
                assertEquals(ZbtReachabilityPolicy.Verdict.ANSWERED,
                    ZbtDaemonReachability.awaitGreeting(it, SystemClock.elapsedRealtime() + 3000))
            }
        } finally { AppLog.LOGGER = logger }
    }

    @Test fun silentPeerExpiresUsingOnlyElapsedRealtime() {
        var reads = 0
        val source = object : InputStream() {
            override fun read(): Int = error("bulk read only")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                check(++reads <= 3) { "greeting exceeded its monotonic budget" }
                elapsedMs += 1_000
                throw SocketTimeoutException()
            }
        }
        ZbtByteChannel(source, ByteArrayOutputStream()).use { channel ->
            assertEquals(ZbtReachabilityPolicy.Verdict.LISTENING_SILENT,
                ZbtDaemonReachability.awaitGreeting(channel, elapsedMs + 3_000))
        }
        assertEquals(3, reads)
    }

    @Test fun closedPeerIsNotPumpedRepeatedlyUntilDeadline() {
        val channel = mock(ZbtByteChannel::class.java)
        whenever(channel.pumpOnce(any(), any())).thenReturn(ZbtByteChannel.Pump.ENDED)
        assertEquals(ZbtReachabilityPolicy.Verdict.LISTENING_SILENT,
            ZbtDaemonReachability.awaitGreeting(channel, SystemClock.elapsedRealtime() + 3000))
        verify(channel, times(1)).pumpOnce(any(), any())
    }
}
