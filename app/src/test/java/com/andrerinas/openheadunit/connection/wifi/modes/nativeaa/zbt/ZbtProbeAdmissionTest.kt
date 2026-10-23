package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import android.os.SystemClock
import com.andrerinas.openheadunit.utils.AppLog
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The real [ZbtProbe.run] against a scripted socket: a carrier claim or a stop that lands after the
 * probe's last keepProbing() check must still stop its reconnect, version and link-poll writes.
 * The claim's socket close is held blocked, so only admission — not the close — can stop them.
 */
class ZbtProbeAdmissionTest {
    private lateinit var clock: MockedStatic<SystemClock>
    private var logger: AppLog.Logger? = null
    private var elapsed = 100_000L
    private val claims = mutableListOf<ZbtDaemonReachability.CarrierClaim>()
    private val releaseClose = CountDownLatch(1)
    private val threads = mutableListOf<Thread>()
    private val messages = mutableListOf<String>()

    @Before fun setup() {
        clock = Mockito.mockStatic(SystemClock::class.java)
        clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { elapsed }
        logger = AppLog.LOGGER
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) { messages.add(msg) }
        }
    }

    @After fun cleanup() {
        releaseClose.countDown()
        threads.forEach { it.join(2_000) }
        synchronized(claims) { claims.forEach { it.close() } }
        clock.close()
        logger?.let { AppLog.LOGGER = it }
    }

    private fun frame(id: Int, body: ByteArray = byteArrayOf()) = ZbtFraming.encodeFrame(body, id)

    /** Each step runs on the probe's reading thread and returns a frame, or null for a read timeout. */
    private inner class ScriptedSocket(steps: List<() -> ByteArray?>, val blockFirstClose: Boolean) : Socket() {
        private val queue = ArrayDeque(steps)
        val written = ByteArrayOutputStream()
        val closeEntered = CountDownLatch(1)
        @Volatile var closes = 0
        override fun connect(endpoint: SocketAddress, timeout: Int) = Unit
        override fun setSoTimeout(timeout: Int) = Unit
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(b: Int) = synchronized(written) { written.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) = synchronized(written) { written.write(b, off, len) }
        }
        override fun getInputStream(): InputStream = object : InputStream() {
            var data = byteArrayOf()
            var offset = 0
            override fun read(): Int = error("bulk reads only")
            override fun read(target: ByteArray, off: Int, len: Int): Int {
                if (offset == data.size) {
                    val step = queue.removeFirstOrNull() ?: return -1
                    data = step() ?: throw SocketTimeoutException()
                    offset = 0
                }
                val size = minOf(len, data.size - offset)
                data.copyInto(target, off, offset, offset + size)
                offset += size
                return size
            }
        }
        override fun close() {
            val first = synchronized(this) { closes++ == 0 }
            if (first && blockFirstClose) {
                closeEntered.countDown()
                releaseClose.await(5, TimeUnit.SECONDS)
            }
        }
        fun ids(): List<Int> {
            val data = synchronized(written) { written.toByteArray() }
            var offset = 0
            return buildList {
                while (offset < data.size) {
                    val header = data.copyOfRange(offset, offset + ZbtFraming.HEADER_SIZE)
                    add(ZbtFraming.decodeMsgId(header))
                    offset += ZbtFraming.HEADER_SIZE + ZbtFraming.decodeBodySize(header)
                }
            }
        }
    }

    /** Registers a carrier claim on another thread and returns once its close is blocked on [entered]. */
    private fun claimWhileCloseBlocks(entered: CountDownLatch) {
        val thread = Thread { ZbtDaemonReachability.claimCarrier().also { synchronized(claims) { claims.add(it) } } }
        threads.add(thread)
        thread.start()
        assertTrue("claim never reached the blocked close", entered.await(2, TimeUnit.SECONDS))
        assertTrue(ZbtDaemonReachability.carrierWantsClient())
    }

    private fun runProbe(socket: Socket, reconnect: Boolean, keepGoing: () -> Boolean = { true },
                         onProgress: (String) -> Unit = {}): String {
        // Static Android clock stubs are thread-local. Run the probe on this test thread;
        // only carrier claiming/blocked close runs on its dedicated worker.
        return ZbtProbe.run(reconnect, onProgress, keepGoing) { socket }
    }

    @Test fun `claim registered while another observer's close blocks stops RequestReconn`() {
        // An observer registered first: the claim closes it first and blocks there, so the probe's own
        // opening is not cancelled yet. Only the claim itself, seen through keepOpening, can stop it.
        val blocker = ZbtDaemonReachability.observerOpening { true }!!
        val blockerEntered = CountDownLatch(1)
        blocker.attach(Closeable { blockerEntered.countDown(); releaseClose.await(5, TimeUnit.SECONDS) })
        val socket = ScriptedSocket(listOf({ frame(ZbtMessages.INIT_INFO) }, { null }), blockFirstClose = false)
        try {
            val verdict = runProbe(socket, reconnect = true, onProgress = {
                if (it.startsWith("Asking the module to reconnect")) claimWhileCloseBlocks(blockerEntered)
            })
            assertEquals(ZbtProbe.carrierTookOverVerdict(), verdict)
            assertEquals(listOf(ZbtMessages.REQUEST_INIT), socket.ids())
        } finally {
            releaseClose.countDown()
            ZbtDaemonReachability.releaseObserver(blocker)
        }
    }

    @Test fun `claim during a read while the probe socket close blocks stops the version request`() {
        lateinit var socket: ScriptedSocket
        socket = ScriptedSocket(listOf(
            { frame(ZbtMessages.INIT_INFO) }, { null },
            // Phone bytes are what make the probe send its version request.
            { claimWhileCloseBlocks(socket.closeEntered); frame(ZbtMessages.RFCOMM_DATA, byteArrayOf(1, 2)) },
        ), blockFirstClose = true)
        val verdict = runProbe(socket, reconnect = false)
        assertEquals(ZbtProbe.carrierTookOverVerdict(), verdict)
        assertEquals(listOf(ZbtMessages.REQUEST_INIT), socket.ids())
    }

    @Test fun `a stop between the loop check and the link poll stops the poll`() {
        var going = true
        val socket = ScriptedSocket(listOf(
            { frame(ZbtMessages.INIT_INFO) }, { null },
            { elapsed += 30_001; going = false; null },
        ), blockFirstClose = false)
        val verdict = runProbe(socket, reconnect = false, keepGoing = { going })
        assertEquals("Probe stopped.", verdict)
        assertEquals(listOf(ZbtMessages.REQUEST_INIT), socket.ids())
    }

    @Test fun `claim released before hello read returns is still a local cancellation`() {
        val socket = ScriptedSocket(listOf({
            // Complete the real claim/close/release before pumpOnce observes the read failure.
            ZbtDaemonReachability.claimCarrier().close()
            assertFalse(ZbtDaemonReachability.carrierWantsClient())
            throw IOException("socket closed by the local carrier")
        }), blockFirstClose = false)
        assertEquals("Probe stopped.", runProbe(socket, reconnect = false))
        assertEquals(listOf(ZbtMessages.REQUEST_INIT), socket.ids())
    }

    @Test fun `claim released before watch read returns does not report daemon failure`() {
        val socket = ScriptedSocket(listOf(
            { frame(ZbtMessages.INIT_INFO) }, { null },
            {
                ZbtDaemonReachability.claimCarrier().close()
                assertFalse(ZbtDaemonReachability.carrierWantsClient())
                throw IOException("socket closed by the local carrier")
            },
        ), blockFirstClose = false)
        assertEquals("Probe stopped.", runProbe(socket, reconnect = false))
        assertEquals(listOf(ZbtMessages.REQUEST_INIT), socket.ids())
        assertFalse(messages.any { "daemon ended" in it || "exchange failed" in it })
    }

    @Test fun `an uncontested probe still sends reconnect, version and poll`() {
        val socket = ScriptedSocket(listOf(
            { frame(ZbtMessages.INIT_INFO) }, { null },
            { frame(ZbtMessages.RFCOMM_DATA, byteArrayOf(1, 2)) },
            { elapsed += 30_001; null },
        ), blockFirstClose = false)
        runProbe(socket, reconnect = true)
        val ids = socket.ids()
        assertEquals(ZbtMessages.REQUEST_INIT, ids.first())
        assertTrue(ids.toString(), ZbtMessages.REQUEST_RECONN in ids)
        assertTrue(ids.toString(), ZbtMessages.RFCOMM_DATA in ids)
        assertTrue(ids.toString(), ZbtMessages.LINK_INFO_REQUEST in ids)
    }
}
