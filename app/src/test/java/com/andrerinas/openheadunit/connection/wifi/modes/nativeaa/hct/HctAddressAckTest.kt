package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class HctAddressAckTest {
    @Test fun `acknowledged preamble proceeds`() = runBlocking {
        val ack = CompletableDeferred<Unit>().apply { complete(Unit) }
        HctAddressAck.await(ack, { false })
    }

    @Test fun `expired local deadline is an IOException and leaves the caller active`() = runBlocking {
        val failure = runCatching {
            HctAddressAck.await(CompletableDeferred(), { false }, timeoutMillis = 20L)
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure!!.message!!.contains("not acknowledged"))
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test fun `withdrawn acknowledgement is a failure with its cause preserved`() = runBlocking {
        val ack = CompletableDeferred<Unit>().apply { cancel() }
        val failure = runCatching { HctAddressAck.await(ack, { false }) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertTrue(failure!!.message!!.contains("invalidated"))
        assertTrue(failure.cause is CancellationException)
        assertTrue(currentCoroutineContext().isActive)
    }

    @Test fun `observer close following carrier stop stays cancellation`() = runBlocking {
        val ack = CompletableDeferred<Unit>().apply { cancel() }
        val failure = runCatching { HctAddressAck.await(ack, { true }) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }

    @Test fun `parent cancellation stays cancellation and is never reported as an IO failure`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var ioFailure = false
        val child = launch {
            try {
                entered.complete(Unit)
                HctAddressAck.await(CompletableDeferred(), { false }, timeoutMillis = 60_000L)
            } catch (error: IOException) {
                ioFailure = true
            }
        }
        entered.await()
        child.cancelAndJoin()
        assertTrue(child.isCancelled)
        assertFalse(ioFailure)
    }

    @Test fun `real deferred IO failure is preserved`() = runBlocking {
        val original = IOException("transport failed")
        val ack = CompletableDeferred<Unit>().apply { completeExceptionally(original) }
        val failure = runCatching { HctAddressAck.await(ack, { false }) }.exceptionOrNull()
        // Coroutine stack-trace recovery may copy the exception while retaining its cause.
        assertTrue(failure is IOException)
        assertEquals(original.message, failure!!.message)
        assertTrue(generateSequence(failure) { it.cause }.any { it === original })
    }

}
