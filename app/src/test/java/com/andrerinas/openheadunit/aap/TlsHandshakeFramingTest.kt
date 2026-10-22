package com.andrerinas.openheadunit.aap

import android.os.SystemClock
import com.andrerinas.openheadunit.connection.projection.ProjectionConnection
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import java.nio.ByteBuffer
import javax.net.ssl.*

class TlsHandshakeFramingTest {
    private val version = byteArrayOf(0, 3, 0, 8, 0, 2, 0, 1, 0, 7, 0, 0)
    private val tls = byteArrayOf(0, 3, 0, 5, 0, 3, 22, 3, 3)

    @Test fun `silent TLS uses one 15 second budget on USB and WiFi`() = mockStatic(SystemClock::class.java).use { clock ->
        for (type in ProjectionConnection.Type.entries) {
            var now = 0L
            clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { now }
            val engine = mock(SSLEngine::class.java)
            `when`(engine.handshakeStatus).thenReturn(SSLEngineResult.HandshakeStatus.NEED_UNWRAP)
            val connection = mock(ProjectionConnection::class.java)
            `when`(connection.type).thenReturn(type)
            var reads = 0
            `when`(connection.recvBlocking(any(), anyInt(), anyInt(), anyBoolean())).thenAnswer {
                assertEquals(15000 - now.toInt(), it.getArgument<Int>(2))
                now += 1000 // Early empty returns do not restart the phase timeout.
                reads++
                0
            }
            assertFalse(context(engine).performHandshake(connection))
            assertEquals(15000L, now)
            assertEquals(15, reads)
            verify(engine, never()).unwrap(any<ByteBuffer>(), any<ByteBuffer>())
        }
    }

    @Test fun `TLS never skips an encrypted old session envelope`() = mockStatic(SystemClock::class.java).use {
        for (flags in 0x08..0x0f) {
            val engine = mock(SSLEngine::class.java)
            `when`(engine.handshakeStatus).thenReturn(SSLEngineResult.HandshakeStatus.NEED_UNWRAP)
            val connection = mock(ProjectionConnection::class.java)
            `when`(connection.recvBlocking(any(), anyInt(), anyInt(), anyBoolean())).thenAnswer {
                byteArrayOf(5, flags.toByte(), 0, 8).copyInto(it.getArgument<ByteArray>(0))
                4
            }
            assertThrows(java.io.IOException::class.java) { context(engine).performHandshake(connection) }
            verify(engine, never()).unwrap(any<ByteBuffer>(), any<ByteBuffer>())
        }
    }

    private fun context(engine: SSLEngine): AapSslContext {
        val context = mock(SSLContext::class.java)
        val session = mock(SSLSession::class.java)
        `when`(context.createSSLEngine(anyString(), anyInt())).thenReturn(engine)
        `when`(engine.session).thenReturn(session)
        `when`(session.packetBufferSize).thenReturn(64)
        `when`(session.applicationBufferSize).thenReturn(64)
        return AapSslContext(context)
    }

    @Test fun `late response and fragmented TLS envelopes reach engine as payload only`() = mockStatic(SystemClock::class.java).use {
        for (status in listOf(0, 1, -8, 255)) {
            assertLateVersionReachesTls(status)
        }
    }

    private fun assertLateVersionReachesTls(status: Int) {
        val engine = mock(SSLEngine::class.java)
        var finished = false
        `when`(engine.handshakeStatus).thenAnswer {
            if (finished) SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING else SSLEngineResult.HandshakeStatus.NEED_UNWRAP
        }
        `when`(engine.unwrap(any<ByteBuffer>(), any<ByteBuffer>())).thenAnswer {
            val input = it.getArgument<ByteBuffer>(0)
            val bytes = ByteArray(input.remaining()).also(input::get)
            assertArrayEquals(byteArrayOf(22, 3, 3), bytes)
            finished = true
            SSLEngineResult(SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.FINISHED, 3, 0)
        }
        val lateVersion = version.copyOf().also {
            it[10] = (status shr 8).toByte()
            it[11] = status.toByte()
        }
        val stream = lateVersion + tls + byteArrayOf(99)
        var position = 0
        val connection = mock(ProjectionConnection::class.java)
        `when`(connection.type).thenReturn(ProjectionConnection.Type.USB)
        `when`(connection.recvBlocking(any(), anyInt(), anyInt(), anyBoolean())).thenAnswer {
            assertFalse(it.getArgument<Boolean>(3))
            assertTrue(it.getArgument<Int>(2) >= 100)
            it.getArgument<ByteArray>(0)[0] = stream[position++]
            1
        }
        assertTrue(context(engine).performHandshake(connection))
        assertEquals(version.size + tls.size, position)
        verify(engine, times(1)).unwrap(any<ByteBuffer>(), any<ByteBuffer>())
    }

    @Test fun `non TLS message or rejected late version is not fed to engine`() = mockStatic(SystemClock::class.java).use {
        // Both bytes must be FF: 0xFFFF is -1(STATUS_NO_COMPATIBLE_VERSION), not 0x00FF(255).
        for (frame in listOf(version.copyOf().also { it[10] = -1; it[11] = -1 }, tls.copyOf().also { it[5] = 11 },
                tls.copyOf().also { it[0] = 1 })) {
            val engine = mock(SSLEngine::class.java)
            `when`(engine.handshakeStatus).thenReturn(SSLEngineResult.HandshakeStatus.NEED_UNWRAP)
            var position = 0
            val connection = mock(ProjectionConnection::class.java)
            `when`(connection.recvBlocking(any(), anyInt(), anyInt(), anyBoolean())).thenAnswer {
                val count = it.getArgument<Int>(1)
                frame.copyInto(it.getArgument<ByteArray>(0), 0, position, position + count)
                position += count
                count
            }
            assertFalse(context(engine).performHandshake(connection))
            verify(engine, never()).unwrap(any<ByteBuffer>(), any<ByteBuffer>())
        }
    }
}
