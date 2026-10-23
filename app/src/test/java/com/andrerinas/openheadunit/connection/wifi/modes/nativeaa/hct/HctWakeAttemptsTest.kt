package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

import org.junit.Assert.*
import org.junit.Test

class HctWakeAttemptsTest {
    private val phone = "11:22:33:44:55:66"
    private val other = "22:33:44:55:66:77"

    @Test fun `repeated selection coalesces while status reads are in progress`() {
        val attempts = HctWakeAttempts()
        val first = attempts.select(phone, 0)!!
        assertNull(attempts.select(phone, 100))
        assertTrue(attempts.claim(first, 200))
        assertFalse(attempts.claim(first, 201))
    }

    @Test fun `new unsent target supersedes the old worker without its cleanup erasing the new request`() {
        val attempts = HctWakeAttempts()
        val old = attempts.select(phone, 0)!!
        val replacement = attempts.select(other, 1)!!
        assertFalse(attempts.claim(old, 2))
        attempts.finishUnsent(old)
        assertTrue(attempts.claim(replacement, 3))
    }

    @Test fun `unknown result and elapsed timeout do not cancel a native connector`() {
        val attempts = HctWakeAttempts()
        val first = attempts.select(phone, 0)!!
        assertTrue(attempts.claim(first, 1))
        // Simulates timeout/cancellation after IPC, including a late worker's cleanup.
        attempts.finishUnsent(first)
        assertTrue(attempts.outstanding(first))
        assertNull(attempts.select(phone, 30_000))
        assertNull(attempts.select(other, 300_000))
    }

    @Test fun `fresh AA during status read wins before native wake submission`() {
        val attempts = HctWakeAttempts()
        val first = attempts.select(phone, 0)!!
        attempts.reported(1)
        assertFalse(attempts.claim(first, 2))
        assertNull(attempts.select(phone, 2))
        assertTrue(attempts.beginOpening())
        assertNull(attempts.select(other, 20_000))
        attempts.endOpening()
        assertNotNull(attempts.select(phone, 20_001))
    }

    @Test fun `opening reserves the carrier before the peer Binder read returns`() {
        val attempts = HctWakeAttempts()
        val queued = attempts.select(phone, 0)!!
        assertTrue(attempts.beginOpening())
        assertFalse(attempts.beginOpening())
        assertFalse(attempts.claim(queued, 1))
        assertNull(attempts.select(other, 2))
    }

    @Test fun `only matching WPP clears submitted attempt and opening still excludes new wakes`() {
        val attempts = HctWakeAttempts()
        val ticket = attempts.select(phone, 0)!!
        assertTrue(attempts.claim(ticket, 1))
        attempts.reported(2)
        attempts.beginOpening()
        attempts.accepted(other)
        assertTrue(attempts.outstanding(ticket))
        attempts.accepted(phone)
        assertFalse(attempts.outstanding(ticket))
        assertNull(attempts.select(other, 10_000))
        attempts.endOpening()
        assertNotNull(attempts.select(other, 10_001))
    }

    @Test fun `failed opening does not rearm a submitted reconnect`() {
        val attempts = HctWakeAttempts()
        val ticket = attempts.select(phone, 0)!!
        attempts.claim(ticket, 1)
        attempts.reported(2)
        attempts.beginOpening()
        attempts.endOpening()
        assertNull(attempts.select(phone, 10_000))
    }

    @Test fun `policy refusal before submission allows a later explicit retry`() {
        val attempts = HctWakeAttempts()
        val ticket = attempts.select(phone, 0)!!
        attempts.finishUnsent(ticket)
        assertNotNull(attempts.select(phone, 10))
        assertFalse(attempts.claim(ticket, 11))
    }

    @Test fun `closing during Binder read prevents late submission and reopening`() {
        val attempts = HctWakeAttempts()
        val ticket = attempts.select(phone, 0)!!
        attempts.close()
        assertFalse(attempts.claim(ticket, 1))
        assertFalse(attempts.beginOpening())
        assertNull(attempts.select(phone, 2))
    }
    @Test fun `concurrent selections cannot publish in reverse order`() {
        val attempts = HctWakeAttempts()
        val channel = kotlinx.coroutines.channels.Channel<HctWakeAttempts.Ticket>(
            kotlinx.coroutines.channels.Channel.CONFLATED)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val secondStarted = java.util.concurrent.CountDownLatch(1)
        val threads = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val first = threads.submit<HctWakeAttempts.Hold?> {
                attempts.enqueue(phone, 0) {
                    entered.countDown()
                    check(release.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    channel.trySend(it).isSuccess
                }
            }
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val second = threads.submit<HctWakeAttempts.Hold?> {
                secondStarted.countDown()
                attempts.enqueue(other, 1) { channel.trySend(it).isSuccess }
            }
            assertTrue(secondStarted.await(2, java.util.concurrent.TimeUnit.SECONDS))
            release.countDown()
            assertNull(first.get(2, java.util.concurrent.TimeUnit.SECONDS))
            assertNull(second.get(2, java.util.concurrent.TimeUnit.SECONDS))
            val received = channel.tryReceive().getOrThrow()
            assertEquals(other, received.target)
            assertTrue(attempts.claim(received, 2))
        } finally {
            release.countDown()
            channel.close()
            threads.shutdownNow()
        }
    }

    @Test fun `failed publication releases its selection for another attempt`() {
        val attempts = HctWakeAttempts()
        assertEquals(HctWakeAttempts.Hold.QUEUE_CLOSED, attempts.enqueue(phone, 0) { false })
        assertNull(attempts.enqueue(phone, 1) { true })
        assertEquals(HctWakeAttempts.Hold.DUPLICATE_SELECTION, attempts.enqueue(phone, 2) { true })
    }

    @Test fun `held different phone selection leaves receive target unchanged`() {
        val attempts = HctWakeAttempts()
        var chosen = phone
        val ticket = attempts.select(phone, 0)!!
        assertTrue(attempts.claim(ticket, 1))
        val held = attempts.selectAndEnqueue(other, 2, { chosen = other }) { fail("must not publish"); true }
        assertEquals(HctWakeAttempts.Hold.AWAITING_OEM_RESULT, held)
        assertEquals(phone, chosen)
        attempts.reported(3)
        assertTrue(attempts.beginOpening())
        attempts.accepted(chosen)
        assertFalse(attempts.outstanding(ticket))
    }

    @Test fun `fresh AA selection opens picker gate without another reconnect`() {
        val attempts = HctWakeAttempts()
        var prompt = true
        attempts.reported(1)
        assertEquals(HctWakeAttempts.Hold.FRESH_AA,
            attempts.selectAndEnqueue(phone, 2, { prompt = false }) { fail("must not wake"); true })
        assertFalse(prompt)
        assertTrue(attempts.beginOpening())
        var changed = false
        assertEquals(HctWakeAttempts.Hold.OPENING,
            attempts.selectAndEnqueue(other, 3, { changed = true }) { true })
        assertFalse(changed)
    }

    @Test fun `closed publication never changes receive selection`() {
        val attempts = HctWakeAttempts()
        var changed = false
        assertEquals(HctWakeAttempts.Hold.QUEUE_CLOSED,
            attempts.selectAndEnqueue(phone, 0, { changed = true }) { false })
        assertFalse(changed)
    }

    @Test fun `carrier recreation preserves submitted native work and matching new WPP reconciles it`() {
        val process = HctPendingReconnect()
        val first = HctWakeAttempts(process)
        val ticket = first.select(phone, 0)!!
        assertTrue(first.claim(ticket, 1))
        first.beginOpening()
        first.close()
        val next = HctWakeAttempts(process)
        assertNull(next.select(other, 100_000))
        assertNull(next.select(phone, 100_000))
        first.accepted(phone) // Late result from a closed carrier cannot erase the operation.
        assertTrue(next.outstanding(ticket))
        assertTrue(next.beginOpening())
        next.accepted(phone)
        next.endOpening()
        val second = next.select(other, 100_001)!!
        assertTrue(next.claim(second, 100_002))
        first.accepted(other)
        assertTrue(next.outstanding(second))
    }

    @Test fun `unsent work does not survive carrier recreation`() {
        val process = HctPendingReconnect()
        val old = HctWakeAttempts(process)
        val ticket = old.select(phone, 0)!!
        old.close()
        val next = HctWakeAttempts(process)
        val newTicket = next.select(other, 1)!!
        assertNotEquals(ticket.id, newTicket.id)
        assertFalse(old.claim(ticket, 2))
        assertTrue(next.claim(newTicket, 2))
    }

    @Test fun `worker cannot inspect prompt until published selection is committed`() {
        val attempts = HctWakeAttempts()
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        var prompt = true
        var result: java.util.concurrent.Future<Boolean>? = null
        val started = java.util.concurrent.CountDownLatch(1)
        try {
            attempts.selectAndEnqueue(phone, 0, { prompt = false }) { ticket ->
                result = worker.submit<Boolean> {
                    started.countDown()
                    attempts.prepare(ticket) { !prompt } == HctWakeAttempts.Preparation.READY
                }
                check(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
                true
            }
            assertTrue(result!!.get(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally { worker.shutdownNow() }
    }

    @Test fun `old live opening cannot clear a newer request for the same phone`() {
        val process = HctPendingReconnect()
        val old = HctWakeAttempts(process)
        val first = old.select(phone, 0)!!
        old.claim(first, 1)
        old.beginOpening()
        val next = HctWakeAttempts(process)
        next.beginOpening()
        next.accepted(phone)
        next.endOpening()
        val second = next.select(phone, 2)!!
        next.claim(second, 3)
        old.accepted(phone)
        assertTrue(next.outstanding(second))
    }

    @Test fun `fresh handoff cannot reserve opening before new picker target commits`() {
        val attempts = HctWakeAttempts()
        var chosen = other
        var prompt = true
        attempts.select(other, 0)
        attempts.reported(1) // Incoming A cancels the unsent B request.
        var consumer: Thread? = null
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task).also { consumer = it }
        }
        val started = java.util.concurrent.CountDownLatch(1)
        var opened: java.util.concurrent.Future<Boolean>? = null
        try {
            assertEquals(HctWakeAttempts.Hold.FRESH_AA,
                attempts.selectAndEnqueue(phone, 2, {
                    prompt = false
                    opened = worker.submit<Boolean> {
                        started.countDown()
                        attempts.beginOpening() && !prompt && chosen == phone
                    }
                    check(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
                    while (consumer!!.state != Thread.State.BLOCKED && !opened!!.isDone &&
                        System.nanoTime() < deadline) Thread.yield()
                    assertEquals(Thread.State.BLOCKED, consumer!!.state)
                    assertFalse(opened!!.isDone)
                    chosen = phone
                }) { fail("fresh AA must not publish a wake"); true })
            assertTrue(opened!!.get(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally { worker.shutdownNow() }
    }

    @Test fun `local busy refusal is distinct from a superseded selection`() {
        val attempts = HctWakeAttempts()
        val ticket = attempts.select(phone, 0)!!
        assertEquals(HctWakeAttempts.Preparation.NOT_READY, attempts.prepare(ticket) { false })
        assertEquals(HctWakePolicy.Result.LOCAL_NOT_READY, attempts.claim(ticket, 1) { false })
        assertFalse(attempts.outstanding(ticket))
        attempts.finishUnsent(ticket)
        assertEquals(HctWakeAttempts.Preparation.SUPERSEDED, attempts.prepare(ticket) { true })
        assertNotNull(attempts.select(phone, 2))
    }

    @Test fun `fresh AA while status reads run supersedes wake without a local busy error`() {
        val attempts = HctWakeAttempts()
        val ticket = attempts.select(phone, 0)!!
        assertEquals(HctWakeAttempts.Preparation.READY, attempts.prepare(ticket) { true })
        attempts.reported(1)
        assertEquals(HctWakePolicy.Result.STATE_CHANGED, attempts.claim(ticket, 2) { false })
        assertFalse(attempts.outstanding(ticket))
    }

}
