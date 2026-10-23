package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** A late Binder reply cannot admit a wake after cancellation or the deadline. */
internal class HctCallGate(
    private val deadlineNanos: Long,
    private val now: () -> Long = System::nanoTime
) {
    private var active = true
    private var claimed = false

    @Synchronized
    fun cancel() {
        active = false
    }

    @Synchronized
    fun checkActive() {
        if (!active || now() - deadlineNanos >= 0) {
            throw IOException("HCT: status request expired")
        }
    }

    @Synchronized
    fun claim(admit: () -> Boolean = { true }): Boolean {
        checkActive()
        if (claimed) throw IOException("HCT: wake request already admitted")
        // Local admission only: cancellation cannot publish an outcome between reserving
        // native work and recording that reservation. Never do Binder IPC in this callback.
        if (!admit()) return false
        claimed = true
        return true
    }
}

internal object HctBinderCalls {
    private const val TIMEOUT_MS = 1_500L

    private val busy = AtomicBoolean()
    // Binder transactions cannot be reliably interrupted. A hung service retains the busy
    // flag, so later attempts fail without queueing another call. The one queue slot only
    // bridges a completed task's result delivery and its worker returning to the executor.
    private val worker = ThreadPoolExecutor(
        0, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1),
        { runnable -> Thread(runnable, "hct-status").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    suspend fun <T> call(block: (HctCallGate) -> T): T {
        val result = withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine<Result<T>> { continuation ->
                val gate = HctCallGate(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS))
                continuation.invokeOnCancellation { gate.cancel() }
                if (!busy.compareAndSet(false, true)) {
                    continuation.resumeWith(Result.success(Result.failure(
                        IOException("HCT: Bluetooth status service is busy")
                    )))
                    return@suspendCancellableCoroutine
                }
                try {
                    worker.execute {
                        val outcome = runCatching {
                            gate.checkActive()
                            block(gate).also { gate.checkActive() }
                        }
                        busy.set(false)
                        if (continuation.isActive) continuation.resumeWith(Result.success(outcome))
                    }
                } catch (error: java.util.concurrent.RejectedExecutionException) {
                    gate.cancel()
                    busy.set(false)
                    continuation.resumeWith(Result.success(Result.failure(
                        IOException("HCT: Bluetooth status service is busy", error)
                    )))
                }
            }
        } ?: throw IOException("HCT: Bluetooth status service timed out")
        return result.getOrThrow()
    }
}
