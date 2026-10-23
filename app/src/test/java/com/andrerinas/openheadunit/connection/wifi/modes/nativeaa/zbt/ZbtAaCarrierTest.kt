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
    private val logs = mutableListOf<String>()
    private val sinks = mutableListOf<ByteArrayOutputStream>()

    @Before fun setup() {
        clock = Mockito.mockStatic(SystemClock::class.java)
        clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { elapsed }
        ZbtDaemonReachability.forget()
        logger = AppLog.LOGGER
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) { logs.add(msg) }
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
        now = { wall }, elapsedNow = { elapsed }
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

    @Test fun `connected phone consumes startup wake and starts handshake without waiting`() = runBlocking {
        val startedAt = elapsed
        var served = false
        val carrier = carrier({ control, rfcomm -> open(listOf(
            Step(frame(ZbtMessages.INIT_INFO)), Step(presence())
        ), control, rfcomm) }, { link ->
            served = true
            assertEquals(startedAt, elapsed)
            assertEquals("AA:BB:CC:DD:EE:01", link.peerAddress)
            running = false
            link.close()
        })
        carrier.requestWake()
        carrier.run()
        assertTrue(served)
        assertEquals(0, wakeCount())
    }

    @Test fun `disconnected report followed by connected report does not spend an early wake`() = runBlocking {
        val carrier = carrier({ control, rfcomm -> open(listOf(
            Step(presence(false)), Step(presence()) { advance(1_000) }
        ), control, rfcomm) })
        carrier.requestWake()
        carrier.run()
        assertEquals(0, wakeCount())
        assertEquals(1, evidence)
    }

    @Test fun `silent module gets one coalesced wake after the observation budget`() = runBlocking {
        canServe = false
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm -> open(listOf(
            Step(frame(ZbtMessages.INIT_INFO)),
            Step { advance(7_999); assertEquals(0, wakeCount()); carrier.requestWake(true) },
            Step { advance(1); assertEquals(0, wakeCount()) },
            Step { advance(20_000); assertEquals(1, wakeCount()) }
        ), control, rfcomm) })
        carrier.requestWake()
        carrier.requestWake(true)
        carrier.run()
        assertEquals(1, wakeCount())
    }

    @Test fun `HFP zero and a pairing scalar do not bypass the opening wait`() = runBlocking {
        val carrier = carrier({ control, rfcomm -> open(listOf(
            Step(frame(ZbtMessages.HFP_LINK_INFO, byteArrayOf(0x10, 0))),
            Step(frame(ZbtMessages.LINK_INFO, byteArrayOf(0x10, 1, 0x18, 0, 0x20, 1)))
        ), control, rfcomm) }, { fail("no connected phone or blind deadline") })
        carrier.requestWake()
        carrier.run()
        assertEquals(0, wakeCount())
    }

    @Test fun `anonymous module retains the blind handshake fallback`() = runBlocking {
        var served = false
        val carrier = carrier({ control, rfcomm -> open(listOf(
            Step(frame(ZbtMessages.INIT_INFO)), Step { advance(8_000) },
            Step { advance(2_001) }
        ), control, rfcomm) }, { link ->
            served = true
            assertNull(link.peerAddress)
            running = false
            link.close()
        })
        carrier.requestWake()
        carrier.run()
        assertTrue(served)
        assertEquals(1, wakeCount())
    }

    @Test fun `real phone bytes cancel a queued wake without losing handshake data`() = runBlocking {
        val payload = byteArrayOf(1, 2, 3)
        val carrier = carrier({ control, rfcomm -> open(listOf(
            Step(frame(ZbtMessages.RFCOMM_DATA, payload))
        ), control, rfcomm) }, { link ->
            assertEquals("phone bytes should be served immediately", 100_000L, elapsed)
            assertEquals(1, link.input.read())
            assertEquals(2, link.input.read())
            assertEquals(3, link.input.read())
            running = false
            link.close()
        })
        carrier.requestWake()
        carrier.run()
        assertEquals(0, wakeCount())
    }

    @Test fun `phone bytes also suppress later automatic and user wake requests`() = runBlocking {
        canServe = false
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm -> open(listOf(
            Step(frame(ZbtMessages.RFCOMM_DATA, byteArrayOf(1))),
            Step { advance(8_001); carrier.requestWake() },
            Step { advance(16_000); repeat(20) { carrier.requestWake(true) } }
        ), control, rfcomm) })
        carrier.run()
        assertEquals(0, wakeCount())
        assertEquals(1, logs.count { "reconnect request ignored: phone data" in it })
    }

    @Test fun `explicit disconnect releases phone data wake suppression`() = runBlocking {
        canServe = false
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm -> open(listOf(
            Step(frame(ZbtMessages.RFCOMM_DATA, byteArrayOf(1))),
            Step(presence(false)) { advance(8_001) },
            Step { carrier.requestWake(true) }
        ), control, rfcomm) })
        carrier.run()
        assertEquals(1, wakeCount())
    }

    @Test fun `incomplete link report and HFP zero do not erase phone data evidence`() = runBlocking {
        canServe = false
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm -> open(listOf(
            Step(frame(ZbtMessages.RFCOMM_DATA, byteArrayOf(1))),
            Step(frame(ZbtMessages.LINK_INFO, byteArrayOf(0x10, 1))),
            Step(frame(ZbtMessages.HFP_LINK_INFO, byteArrayOf(0x10, 0))),
            Step { advance(8_001); carrier.requestWake(true) }
        ), control, rfcomm) })
        carrier.run()
        assertEquals(0, wakeCount())
    }

    @Test fun `new channel does not inherit phone data suppression`() = runBlocking {
        canServe = false
        var opens = 0
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm ->
            if (++opens == 1) {
                open(listOf(Step(frame(ZbtMessages.RFCOMM_DATA, byteArrayOf(1)))),
                    control, rfcomm, eof = {})
            } else {
                open(listOf(Step { advance(8_001); carrier.requestWake(true) }), control, rfcomm)
            }
        })
        carrier.run()
        assertEquals(2, opens)
        assertEquals(1, wakeCount())
    }

    @Test fun `empty phone frame does not suppress a new wake`() = runBlocking {
        canServe = false
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm -> open(listOf(
            Step(frame(ZbtMessages.RFCOMM_DATA)),
            Step { advance(8_001); carrier.requestWake(true) }
        ), control, rfcomm) })
        carrier.run()
        assertEquals(1, wakeCount())
    }

    @Test fun `reopened channel ignores old peer callbacks and old queued wakes`() = runBlocking {
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
            if (opens == 1) {
                // A real disconnect during setup can allow a user wake to queue. The old
                // socket's pending work must not transfer to the next phone/channel.
                oldControl!!(ZbtMessages.LINK_INFO2, peer(false))
                busy = true
                carrier.requestWake(true)
                busy = false
            } else running = false
            link.close()
        })
        carrier.run()
        assertEquals(listOf("AA:BB:CC:DD:EE:01", null), peers)
        assertEquals(1, evidence)
        assertEquals(0, wakeCount())
    }

    @Test fun `a new request after a real disconnect can still wake the phone`() = runBlocking {
        canServe = false
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm -> open(listOf(
            Step(presence()),
            Step(presence(false)) { advance(9_000); carrier.requestWake(true) },
            Step { carrier.requestWake(true) }
        ), control, rfcomm) })
        carrier.requestWake()
        carrier.run()
        assertEquals(1, wakeCount())
    }

    @Test fun `projection that becomes live during observation consumes the pending wake`() = runBlocking {
        val carrier = carrier({ control, rfcomm -> open(listOf(
            Step(frame(ZbtMessages.INIT_INFO)), Step { advance(9_000); connected = true }
        ), control, rfcomm) }, { fail("projection is already live") })
        carrier.requestWake()
        carrier.run()
        assertEquals(0, wakeCount())
    }

    @Test fun `closing during observation drops queued work`() = runBlocking {
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm -> open(listOf(
            Step(frame(ZbtMessages.INIT_INFO)), Step { advance(11_000); carrier.close() }
        ), control, rfcomm) }, { fail("closed carrier") })
        carrier.requestWake()
        carrier.run()
        carrier.requestWake(true)
        assertEquals(0, wakeCount())
    }

    @Test fun `repeated clicks cannot refresh an expired pending wake`() = runBlocking {
        canServe = false
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm -> open(listOf(
            Step(frame(ZbtMessages.INIT_INFO)),
            Step { busy = true; advance(59_999); carrier.requestWake(true) },
            Step { advance(1); busy = false }, Step { advance(1) }
        ), control, rfcomm) })
        carrier.requestWake()
        carrier.run()
        assertEquals(0, wakeCount())
    }

    @Test fun `incomplete legacy link report preserves a connected peer before its first bytes`() = runBlocking {
        canServe = false
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm -> open(listOf(
            Step(presence()),
            Step(frame(ZbtMessages.LINK_INFO, byteArrayOf(0x10, 1))),
            Step { advance(8_001); carrier.requestWake(true) }
        ), control, rfcomm) })
        carrier.run()
        assertEquals(0, wakeCount())
        assertEquals(1, evidence)
    }

    @Test fun `missing or invalid is_connect in LinkInfo2 cannot create a reconnect opportunity`() = runBlocking {
        canServe = false
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm -> open(listOf(
            Step(presence()),
            Step(frame(ZbtMessages.LINK_INFO2, byteArrayOf(0x10, 1))),
            Step(frame(ZbtMessages.LINK_INFO2, byteArrayOf(0x10, 1, 0x18, 2))),
            Step { advance(8_001); carrier.requestWake(true) }
        ), control, rfcomm) })
        carrier.run()
        assertEquals(0, wakeCount())
        assertEquals(1, evidence)
    }

    @Test fun `legacy connected report without type preserves peer but explicit down still releases it`() = runBlocking {
        canServe = false
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm -> open(listOf(
            Step(presence()),
            Step(frame(ZbtMessages.LINK_INFO, byteArrayOf(0x18, 1))),
            Step { advance(8_001); carrier.requestWake(true) },
            Step(frame(ZbtMessages.LINK_INFO, byteArrayOf(0x18, 0))) { assertEquals(0, wakeCount()) },
            Step { carrier.requestWake(true) }
        ), control, rfcomm) })
        carrier.run()
        assertEquals(1, wakeCount())
        assertEquals(1, evidence)
    }

    @Test fun `LinkInfo2 missing type preserves peer while a valid non AA type keeps its behavior`() = runBlocking {
        canServe = false
        lateinit var carrier: ZbtAaCarrier
        carrier = carrier({ control, rfcomm -> open(listOf(
            Step(presence()),
            Step(frame(ZbtMessages.LINK_INFO2, byteArrayOf(0x18, 1))),
            Step { advance(8_001); carrier.requestWake(true) },
            Step(frame(ZbtMessages.LINK_INFO2, byteArrayOf(0x10, 3, 0x18, 1))) { assertEquals(0, wakeCount()) },
            Step { carrier.requestWake(true) }
        ), control, rfcomm) })
        carrier.run()
        assertEquals(1, wakeCount())
        assertEquals(1, evidence)
    }

}
