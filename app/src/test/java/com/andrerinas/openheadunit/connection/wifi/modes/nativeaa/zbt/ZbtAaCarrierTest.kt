package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import android.os.SystemClock
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.HandshakeLink
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException

/** Exercises the actual demultiplexer and carrier, including writes and channel replacement. */
class ZbtAaCarrierTest {
    private lateinit var clock: MockedStatic<SystemClock>
    private var logger: AppLog.Logger? = null
    private var wall = System.currentTimeMillis()
    private var elapsed = 100_000L
    private var running = true
    private var connected = false
    private var busy = false
    private var evidence = 0
    private var canServe = true
    private val sinks = mutableListOf<ByteArrayOutputStream>()

    @Before fun setup() {
        clock = Mockito.mockStatic(SystemClock::class.java)
        clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { elapsed }
        ZbtDaemonReachability.forget()
        logger = AppLog.LOGGER
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) = Unit
        }
    }

    @After fun cleanup() {
        clock.close()
        ZbtDaemonReachability.forget()
        logger?.let { AppLog.LOGGER = it }
    }

    private fun advance(ms: Long) { elapsed += ms; wall += ms }
    private data class Step(val bytes: ByteArray? = null, val before: () -> Unit = {})
    private fun frame(id: Int, body: ByteArray = byteArrayOf()) = ZbtFraming.encodeFrame(body, id)
    private fun peer(connected: Boolean = true, name: String = "AABBCCDDEE01") =
        byteArrayOf(0x10, 1, 0x18, if (connected) 1 else 0, 0x3a, 12) + name.toByteArray()
    private fun presence(connected: Boolean = true) = frame(ZbtMessages.LINK_INFO2, peer(connected))

    private fun open(
        steps: List<Step>, control: (Int, ByteArray) -> Unit, rfcomm: (ByteArray) -> Unit,
        eof: () -> Unit = { running = false }
    ): ZbtByteChannel {
        val queue = ArrayDeque(steps)
        val source = object : InputStream() {
            var data = byteArrayOf()
            var offset = 0
            override fun read(): Int = error("bulk reads only")
            override fun read(target: ByteArray, off: Int, len: Int): Int {
                if (offset == data.size) {
                    val step = queue.removeFirstOrNull() ?: run { eof(); return -1 }
                    step.before()
                    data = step.bytes ?: throw SocketTimeoutException()
                    offset = 0
                }
                val size = minOf(len, data.size - offset)
                data.copyInto(target, off, offset, offset + size)
                offset += size
                return size
            }
        }
        val sink = ByteArrayOutputStream().also { sinks.add(it) }
        return ZbtByteChannel(source, sink, control, rfcomm).also { it.sendRequestInit() }
    }

    private fun carrier(
        factory: ((Int, ByteArray) -> Unit, (ByteArray) -> Unit) -> ZbtByteChannel,
        serve: suspend (HandshakeLink) -> Unit = { running = false; it.close() }
    ) = ZbtAaCarrier(
        serve = serve, isRunning = { running }, isFinishedForSession = { false },
        isSessionConnected = { connected }, isSettling = { false },
        isHandshakeInFlight = { busy }, mayServeHandshake = { canServe },
        onPhoneEvidence = { evidence++ }, openChannel = factory,
        now = { wall }
    )

    private fun ids(sink: ByteArrayOutputStream): List<Int> {
        val data = sink.toByteArray()
        var offset = 0
        return buildList {
            while (offset < data.size) {
                val header = data.copyOfRange(offset, offset + ZbtFraming.HEADER_SIZE)
                add(ZbtFraming.decodeMsgId(header))
                offset += ZbtFraming.HEADER_SIZE + ZbtFraming.decodeBodySize(header)
            }
        }
    }
    private fun wakeCount() = sinks.sumOf { ids(it).count { id -> id == ZbtMessages.REQUEST_RECONN } }

    @Test fun `reopened channel ignores old peer state and callbacks`() = runBlocking {
        var opens = 0
        var oldControl: ((Int, ByteArray) -> Unit)? = null
        var oldRfcomm: ((ByteArray) -> Unit)? = null
        lateinit var carrier: ZbtAaCarrier
        val peers = mutableListOf<String?>()
        carrier = carrier({ control, rfcomm ->
            opens++
            if (opens == 1) {
                oldControl = control
                oldRfcomm = rfcomm
                open(listOf(Step(presence())), control, rfcomm)
            } else {
                advance(5_001)
                oldControl!!(ZbtMessages.LINK_INFO2, peer(false))
                oldControl!!(ZbtMessages.LINK_INFO2, peer())
                oldRfcomm!!(byteArrayOf(9))
                open(listOf(Step(frame(ZbtMessages.INIT_INFO)), Step { advance(10_001) }), control, rfcomm)
            }
        }, { link ->
            peers.add(link.peerAddress)
            if (opens > 1) running = false
            link.close()
        })
        carrier.run()
        assertEquals(listOf("AA:BB:CC:DD:EE:01", null), peers)
        assertEquals(1, evidence)
        assertEquals(0, wakeCount())
    }

    @Test fun `closing during a read prevents another handshake`() = runBlocking {
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm -> open(listOf(
            Step(frame(ZbtMessages.INIT_INFO)), Step { advance(11_000); carrier.close() }
        ), control, rfcomm) }, { fail("closed carrier") })
        carrier.run()
        assertEquals(0, wakeCount())
    }
}
