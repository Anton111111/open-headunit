package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * SPC2 only acknowledges consumption of the address preamble, not a successful WPP session.
 * Its missing/withdrawn reply is an opening failure; cancellation of the owner still propagates.
 */
internal object HctAddressAck {
    suspend fun await(
        acknowledgement: Deferred<Unit>,
        stopped: () -> Boolean,
        timeoutMillis: Long = 1_000L
    ) {
        try {
            withTimeoutOrNull(timeoutMillis) { acknowledgement.await(); true }
                ?: throw IOException("HCT: address preamble was not acknowledged within $timeoutMillis ms")
        } catch (error: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (stopped()) throw error
            throw IOException("HCT: address acknowledgement was invalidated before WPP", error)
        }
    }
}
