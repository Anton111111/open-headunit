package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class HctBinderCallsTest {
    @Test
    fun sequentialCompletedCallsNeverReportBusy() = runBlocking {
        repeat(100) { expected ->
            assertEquals(expected, HctBinderCalls.call { expected })
        }
    }

    @Test
    fun admittedIpcDoesNotHoldCancellationMonitor() {
        val gate = HctCallGate(Long.MAX_VALUE) { 0 }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val operation = threads.submit {
                gate.claim()
                entered.countDown()
                release.await()
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            // A system Binder call already submitted cannot be recalled, but its caller
            // can still cancel without waiting for that system call to return.
            threads.submit { gate.cancel() }.get(1, TimeUnit.SECONDS)
            release.countDown()
            operation.get(1, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            threads.shutdownNow()
        }
    }

    @Test
    fun cancellationAndDeadlineBothPreventLateWake() {
        var now = 10L
        var wakes = 0
        val cancelled = HctCallGate(20) { now }
        cancelled.cancel()
        assertThrows(IOException::class.java) {
            cancelled.claim()
            wakes++
        }
        val expired = HctCallGate(20) { now }
        now = 20
        assertThrows(IOException::class.java) {
            expired.claim()
            wakes++
        }
        assertEquals(0, wakes)
    }

    @Test
    fun stalledServiceTimesOutWithoutQueueingAnotherWake() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val wakes = AtomicInteger()
        val stalled = async(Dispatchers.Default) {
            runCatching {
                HctBinderCalls.call { gate ->
                    entered.countDown()
                    try {
                        // Emulates an OEM Binder call that ignores coroutine cancellation.
                        release.await()
                        gate.claim()
                        wakes.incrementAndGet()
                    } finally {
                        exited.countDown()
                    }
                }
            }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val failure = stalled.await().exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(failure!!.message!!.contains("timed out"))
            val next = runCatching { HctBinderCalls.call { wakes.incrementAndGet() } }
            assertTrue(next.exceptionOrNull()?.message?.contains("busy") == true)
        } finally {
            release.countDown()
            assertTrue(exited.await(2, TimeUnit.SECONDS))
            // The worker clears busy after the simulated Binder call unwinds; wait for
            // that handoff so the process-wide worker cannot race the next JUnit method.
            withTimeout(2_000) {
                while (true) {
                    val idle = runCatching { HctBinderCalls.call { Unit } }
                    if (idle.isSuccess) break
                    assertTrue(idle.exceptionOrNull()?.message?.contains("busy") == true)
                    yield()
                }
            }
        }
        assertEquals(0, wakes.get())
    }
}
