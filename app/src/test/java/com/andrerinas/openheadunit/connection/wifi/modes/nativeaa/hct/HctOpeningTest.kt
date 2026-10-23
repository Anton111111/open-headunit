package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

import com.andrerinas.openheadunit.aap.protocol.proto.Wireless
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.WppFraming
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

class HctOpeningTest {
    private val expectedRequest = byteArrayOf(
        0, 10, 0, 4, 8, 1, 16, 7, 24, 0, 34, 2, 0xbc.toByte(), 40,
    )

    private val request = HctOpening.versionRequest(Wireless.WifiChannelType.CHANNELS_5GHZ_ONLY, 5180)

    private fun response(major: Int = 1, minor: Int = 7, status: Int = 0): ByteArray =
        WppFraming.encodeFrame(
            Wireless.WifiVersionResponse.newBuilder()
                .setMajor(major).setMinor(minor).setStatus(status).build().toByteArray(),
            5,
        )

    private fun start(ip: String = "0.0.0.0"): ByteArray =
        WppFraming.encodeFrame(
            Wireless.WifiStartRequest.newBuilder().setIpAddress(ip).setPort(1).build().toByteArray(),
            1,
        )

    private fun exchange(input: InputStream, output: ByteArrayOutputStream = ByteArrayOutputStream()) =
        HctOpening.exchange(input, output, request, 1000L, { 0L }, {})

    @Test
    fun `one group-specific request and buffered response leave the next frame untouched`() {
        val first = response()
        val next = WppFraming.encodeFrame(byteArrayOf(), 2)
        val input = ByteArrayInputStream(first + next)
        val output = ByteArrayOutputStream()

        assertArrayEquals(first, exchange(input, output))
        assertArrayEquals(expectedRequest, output.toByteArray())
        assertArrayEquals(next, input.readBytes())
    }

    @Test
    fun `every header and payload split is preserved`() {
        val frame = response()
        for (split in 1 until frame.size) {
            val input = object : ByteArrayInputStream(frame) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    val untilSplit = if (pos < split) split - pos else length
                    return super.read(bytes, offset, minOf(length, untilSplit))
                }
            }
            assertArrayEquals("split $split", frame, exchange(input))
        }
    }

    @Test
    fun `unproven lower and higher versions are rejected`() {
        for (frame in listOf(response(1, 6), response(1, 8), response(4, 2))) {
            assertThrows(IOException::class.java) { exchange(ByteArrayInputStream(frame)) }
        }
    }

    @Test
    fun `request uses current group frequency and band rather than native defaults`() {
        for ((band, frequency) in listOf(
            Wireless.WifiChannelType.CHANNELS_24GHZ_ONLY to 2437,
            Wireless.WifiChannelType.CHANNELS_DUAL_BAND to 5240,
            Wireless.WifiChannelType.CHANNELS_5GHZ_ONLY to 5745,
        )) {
            val current = HctOpening.versionRequest(band, frequency)
            assertEquals(1, current.major)
            assertEquals(7, current.minor)
            assertEquals(band, current.supportedWifiChannelType)
            assertEquals(listOf(frequency), current.supportedWifiChannelsList)
            assertFalse(current.hasWppInfo())
            val output = ByteArrayOutputStream()
            HctOpening.exchange(ByteArrayInputStream(response()), output, current, 1000L, { 0L }, {})
            assertArrayEquals(WppFraming.encodeFrame(current.toByteArray(), 4), output.toByteArray())
        }
    }

    @Test
    fun `unknown group or radio does not invent a frequency or band`() {
        val current = HctOpening.versionRequest(null, 0)
        assertFalse(current.hasSupportedWifiChannelType())
        assertEquals(emptyList<Int>(), current.supportedWifiChannelsList)
        assertFalse(current.hasWppInfo())
    }

    @Test
    fun `invalid request version or newer endpoint fails before sending`() {
        val requests = listOf(
            request.toBuilder().setMajor(4).setMinor(2).build(),
            request.toBuilder().setMinor(6).build(),
            request.toBuilder().clearMajor().build(),
            request.toBuilder().setWppInfo(
                Wireless.WifiProjectionProtocolInfo.newBuilder().setIpAddress("0.0.0.0").setPort(5289)
            ).build(),
        )
        for (invalid in requests) {
            val output = ByteArrayOutputStream()
            assertThrows(IllegalArgumentException::class.java) {
                HctOpening.exchange(ByteArrayInputStream(response()), output, invalid, 1000L, { 0L }, {})
            }
            assertEquals(0, output.size())
        }
    }

    @Test
    fun `iAP bytes or another WPP message cannot establish a WPP opening`() {
        for (bytes in listOf(
            byteArrayOf(0xff.toByte(), 0x55, 0x02, 0x00),
            WppFraming.encodeFrame(byteArrayOf(8, 0), 6),
        )) {
            assertThrows(IOException::class.java) { exchange(ByteArrayInputStream(bytes)) }
        }
    }

    @Test
    fun `zero and excessive declared lengths are rejected before reading a body`() {
        for (size in listOf(0, 4097, 65535)) {
            val input = ByteArrayInputStream(WppFraming.encodeHeader(size, 5) + byteArrayOf(99))
            assertThrows(IOException::class.java) { exchange(input) }
            assertEquals(99, input.read())
        }
    }

    @Test
    fun `response at the size limit remains valid`() {
        val payload = Wireless.WifiVersionResponse.newBuilder()
            .setMajor(1).setMinor(7).setStatus(0).setDeviceSerial("x".repeat(4087))
            .build().toByteArray()
        assertEquals(4096, payload.size)
        val frame = WppFraming.encodeFrame(payload, 5)
        assertArrayEquals(frame, exchange(ByteArrayInputStream(frame)))
    }

    @Test
    fun `malformed missing and invalid fields never inherit protobuf success defaults`() {
        val payloads = listOf(
            byteArrayOf(0x80.toByte()),
            Wireless.WifiVersionResponse.newBuilder().setMajor(1).setMinor(7).build().toByteArray(),
            Wireless.WifiVersionResponse.newBuilder().setMinor(7).setStatus(0).build().toByteArray(),
            Wireless.WifiVersionResponse.newBuilder().setMajor(1).setStatus(0).build().toByteArray(),
        )
        for (payload in payloads) {
            assertThrows(IOException::class.java) {
                exchange(ByteArrayInputStream(WppFraming.encodeFrame(payload, 5)))
            }
        }
        for (frame in listOf(
            response(0, 7), response(-1, 7), response(1, -1),
            response(status = -2), // -2(WIFI_INACCESSIBLE_CHANNEL)
            response(status = -7), // -7(INVALID_HOST)
            response(status = -9), // -9(INSTRUCT_USER_TO_CHECK_THE_PHONE)
            response(status = 1), // 1(UNSOLICITED_MESSAGE)
        )) {
            assertThrows(IOException::class.java) { exchange(ByteArrayInputStream(frame)) }
        }
    }

    @Test
    fun `EOF anywhere in the frame fails without a second request`() {
        val frame = response()
        for (length in 0 until frame.size) {
            val output = ByteArrayOutputStream()
            assertThrows(EOFException::class.java) {
                exchange(ByteArrayInputStream(frame.copyOf(length)), output)
            }
            assertArrayEquals(expectedRequest, output.toByteArray())
        }
    }

    @Test
    fun `zero read fails rather than spinning`() {
        val input = object : InputStream() {
            override fun read(): Int = error("single-byte read is not expected")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = 0
        }
        assertThrows(IOException::class.java) { exchange(input) }
    }

    @Test
    fun `expired deadline sends nothing`() {
        val output = ByteArrayOutputStream()
        assertThrows(SocketTimeoutException::class.java) {
            HctOpening.exchange(ByteArrayInputStream(response()), output, request, 100L, { 100L }, {})
        }
        assertEquals(0, output.size())
    }

    @Test
    fun `timeout budgets shrink and a response arriving after the deadline is rejected`() {
        var now = 50L
        val timeouts = mutableListOf<Int>()
        val input = object : ByteArrayInputStream(response()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                now += 30L
                return super.read(bytes, offset, length)
            }
        }
        assertThrows(SocketTimeoutException::class.java) {
            HctOpening.exchange(input, ByteArrayOutputStream(), request, 100L, { now }, timeouts::add)
        }
        assertEquals(listOf(50, 20), timeouts)
    }

    @Test
    fun `socket timeout is propagated without another request`() {
        val output = ByteArrayOutputStream()
        val input = object : InputStream() {
            override fun read(): Int = throw SocketTimeoutException("socket timeout")
        }
        assertThrows(SocketTimeoutException::class.java) { exchange(input, output) }
        assertArrayEquals(expectedRequest, output.toByteArray())
    }

    @Test
    fun `later response is validated independently after a queued success`() {
        val initial = response()
        val rejected = response(status = -2)
        val input = ByteArrayInputStream(initial + rejected)
        assertArrayEquals(initial, exchange(input))
        val later = input.readBytes()
        assertArrayEquals(rejected, later)
        assertThrows(IOException::class.java) {
            HctOpening.validateResponse(later.copyOfRange(WppFraming.HEADER_SIZE, later.size))
        }
        for (frame in listOf(response(4, 2), response(1, 6))) {
            assertThrows(IOException::class.java) {
                HctOpening.validateResponse(frame.copyOfRange(WppFraming.HEADER_SIZE, frame.size))
            }
        }
    }

    @Test
    fun `phone start before version is retained after validated response without consuming next frame`() {
        val trigger = start()
        val version = response()
        val next = WppFraming.encodeFrame(byteArrayOf(), 2)
        val input = ByteArrayInputStream(trigger + version + next)
        val output = ByteArrayOutputStream()
        assertArrayEquals(version + trigger, exchange(input, output))
        assertArrayEquals(next, input.readBytes())
        assertArrayEquals(expectedRequest, output.toByteArray())
    }

    @Test
    fun `two start frames at total byte limit are retained`() {
        val trigger = start("x".repeat(4087))
        assertEquals(4096, trigger.size)
        val version = response()
        assertArrayEquals(
            version + trigger + trigger,
            exchange(ByteArrayInputStream(trigger + trigger + version)),
        )
    }

    @Test
    fun `start prelude and version may arrive one byte at a time`() {
        val trigger = start()
        val version = response()
        val input = object : ByteArrayInputStream(trigger + version) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                super.read(bytes, offset, minOf(1, length))
        }
        assertArrayEquals(version + trigger, exchange(input))
    }

    @Test
    fun `actual phone empty and reason-only triggers do not require a projection endpoint`() {
        for (payload in listOf(byteArrayOf(), byteArrayOf(0x18, 0x02))) {
            val trigger = WppFraming.encodeFrame(payload, 1)
            val version = response()
            assertArrayEquals(version + trigger, exchange(ByteArrayInputStream(trigger + version)))
        }
    }

    @Test
    fun `excessive start frame count fails without consuming third body or retrying`() {
        val trigger = start()
        val input = ByteArrayInputStream(trigger + trigger + trigger + response())
        val output = ByteArrayOutputStream()
        assertThrows(IOException::class.java) { exchange(input, output) }
        assertArrayEquals(
            trigger.copyOfRange(WppFraming.HEADER_SIZE, trigger.size) + response(),
            input.readBytes(),
        )
        assertArrayEquals(expectedRequest, output.toByteArray())
    }

    @Test
    fun `prelude total byte limit rejects second body before allocating it`() {
        val trigger = start("x".repeat(4088))
        assertEquals(4097, trigger.size)
        val input = ByteArrayInputStream(trigger + trigger + response())
        assertThrows(IOException::class.java) { exchange(input) }
        assertArrayEquals(
            trigger.copyOfRange(WppFraming.HEADER_SIZE, trigger.size) + response(),
            input.readBytes(),
        )
    }

    @Test
    fun `malformed phone start is not accepted as a prelude`() {
        for (payload in listOf(byteArrayOf(0x80.toByte()), byteArrayOf(0x0a, 0x02, 0x61))) {
            assertThrows(IOException::class.java) {
                exchange(ByteArrayInputStream(WppFraming.encodeFrame(payload, 1) + response()))
            }
        }
    }

    @Test
    fun `EOF after a phone start never substitutes for a version response`() {
        val output = ByteArrayOutputStream()
        assertThrows(EOFException::class.java) { exchange(ByteArrayInputStream(start()), output) }
        assertArrayEquals(expectedRequest, output.toByteArray())
    }

    @Test
    fun `no supported channels preserves opening and later frames for WiFi setup`() {
        val frame = response(status = -8) // -8(NO_SUPPORTED_WIFI_CHANNELS)
        val next = start()
        val input = ByteArrayInputStream(frame + next)
        assertArrayEquals(frame, exchange(input))
        assertArrayEquals(next, input.readBytes())
        assertArrayEquals(frame + next, exchange(ByteArrayInputStream(next + frame)))
        // The common handshake validates later responses with this same entry point.
        assertEquals(-8, HctOpening.validateResponse(frame.copyOfRange(WppFraming.HEADER_SIZE, frame.size)).status)
        for (unsupported in listOf(response(1, 6, -8), response(1, 8, -8), response(2, 7, -8))) {
            assertThrows(IOException::class.java) { exchange(ByteArrayInputStream(unsupported)) }
        }
    }
}
