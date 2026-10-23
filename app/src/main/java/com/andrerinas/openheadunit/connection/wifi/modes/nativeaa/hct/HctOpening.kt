package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

import com.andrerinas.openheadunit.aap.protocol.proto.Wireless
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.WppChannelListPolicy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.WppFraming
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.WppMessageType
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException

/**
 * Confirms WPP on an already acquired HCT carrier. This does not discover or claim the socket.
 *
 * The caller must separately finish the manufacturer's twelve-byte MAC preamble before entering
 * this exchange: combining that preamble with WPP can make the native daemon discard both. A
 * socket connection or its SPC notification alone does not establish a phone's WPP identity.
 */
internal object HctOpening {
    private const val MAX_RESPONSE_BYTES = 4096
    private const val MAX_PRELUDE_FRAMES = 2
    private const val MAX_PRELUDE_BYTES = 8192

    /**
     * Uses the daemon's WPP 1.7 version with this attempt's real group frequency. The related
     * HCT A13 fscsdkbw123 aap_set_state() advertises a fixed 5180 MHz; copying that value would
     * misdescribe other groups. The inspected AVIN A10 daemon has no automatic version request.
     * WPP-over-TCP endpoints are deliberately absent: they require a newer negotiated protocol.
     */
    fun versionRequest(
        channelType: Wireless.WifiChannelType?,
        frequencyMhz: Int,
    ): Wireless.WifiVersionRequest =
        Wireless.WifiVersionRequest.newBuilder()
            .setMajor(1)
            .setMinor(7)
            .also { builder ->
                if (channelType != null) builder.supportedWifiChannelType = channelType
                builder.addAllSupportedWifiChannels(WppChannelListPolicy.forGroup(frequencyMhz))
            }
            .build()

    /**
     * Sends one request and returns a compatible VersionResponse for the normal WPP reader to
     * replay. At most two early phone StartRequests may precede it; later bytes stay in [input].
     *
     * AA 18.0.664204's version handler (pum -> pua) sends a new response to a repeated request and
     * replaces its pending start timer. Consequently this is a single bounded request, not a
     * polling loop or permission to change the protocol version halfway through an exchange.
     * The daemon can have sent 1.7 already, and iap_data_callback() drops bytes until a local
     * client attaches. WPP has no request ID, so a queued response to the daemon's advertisement
     * cannot be distinguished from a response to ours. Receiving one establishes WPP version
     * compatibility, not acceptance of this attempt's channel list or a completed Wi-Fi join.
     *
     * [deadlineMillis] and [nowMillis] use the same monotonic clock. [setTimeoutMillis] must bound
     * the underlying blocking read. The carrier's cancellation watchdog must close a blocked
     * write too: InputStream/OutputStream cannot themselves enforce a write deadline.
     */
    @Throws(IOException::class)
    fun exchange(
        input: InputStream,
        output: OutputStream,
        request: Wireless.WifiVersionRequest,
        deadlineMillis: Long,
        nowMillis: () -> Long,
        setTimeoutMillis: (Int) -> Unit,
    ): ByteArray {
        fun remainingMillis(): Int {
            val now = nowMillis()
            if (now >= deadlineMillis) {
                throw SocketTimeoutException("HCT WPP opening timed out")
            }
            // Avoid overflowing the subtraction if a test or caller uses a distant deadline.
            val remaining = deadlineMillis - now
            return if (remaining <= 0 || remaining > Int.MAX_VALUE) Int.MAX_VALUE else remaining.toInt()
        }

        fun readExactly(bytes: ByteArray) {
            var offset = 0
            while (offset < bytes.size) {
                setTimeoutMillis(remainingMillis())
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count < 0) throw EOFException("HCT carrier closed during WPP opening")
                if (count == 0) throw IOException("HCT carrier made no progress during WPP opening")
                remainingMillis()
                offset += count
            }
        }

        require(request.hasMajor() && request.major == 1 && request.hasMinor() && request.minor == 7) {
            "HCT opening requires WPP 1.7"
        }
        require(!request.hasWppInfo()) { "HCT WPP 1.7 cannot advertise a WPP-over-TCP endpoint" }
        remainingMillis()
        output.write(WppFraming.encodeFrame(request.toByteArray(), WppMessageType.VERSION_REQUEST))
        output.flush()
        remainingMillis()

        val prelude = ByteArrayOutputStream()
        var preludeFrames = 0
        while (true) {
            val header = ByteArray(WppFraming.HEADER_SIZE)
            readExactly(header)
            val size = WppFraming.decodePayloadSize(header)
            val type = WppFraming.decodeType(header)
            if (type != WppMessageType.VERSION_RESPONSE && type != WppMessageType.START_REQUEST) {
                throw IOException("HCT carrier did not return a WPP version response")
            }
            if (size > MAX_RESPONSE_BYTES ||
                (type == WppMessageType.VERSION_RESPONSE && size == 0)
            ) {
                throw IOException("HCT WPP opening frame length is invalid: $size")
            }
            if (type == WppMessageType.START_REQUEST &&
                (preludeFrames >= MAX_PRELUDE_FRAMES ||
                    prelude.size() + header.size + size > MAX_PRELUDE_BYTES)
            ) {
                throw IOException("HCT WPP opening prelude exceeded its limit")
            }
            val payload = ByteArray(size)
            readExactly(payload)
            if (type == WppMessageType.VERSION_RESPONSE) {
                validateResponse(payload)
                // The phone can send its MD start trigger after the daemon's response was lost.
                // Our request already starts negotiation; the common FSM ignores incoming type 1.
                // Replay the validated version first so that trigger cannot bypass this gate,
                // retaining the original prelude bytes for the reader rather than discarding them.
                return header + payload + prelude.toByteArray()
            }
            try {
                // AA 18's jtv.run case 10 sends the default empty ydh message, or only reason=2.
                // The HU-to-phone form requires an endpoint, but the MD trigger does not.
                // Partial parsing validates protobuf encoding without imposing those HU fields.
                Wireless.WifiStartRequest.parser().parsePartialFrom(payload)
            } catch (e: IOException) {
                throw IOException("HCT WPP opening start request is malformed", e)
            }
            prelude.write(header)
            prelude.write(payload)
            preludeFrames++
        }
    }

    /**
     * Validate every version response on this carrier, including ones arriving after opening.
     * A buffered response to the daemon's request can precede refusal of our own advertisement;
     * accepting the first response must not turn subsequent refusals into informational logs.
     */
    @Throws(IOException::class)
    fun validateResponse(payload: ByteArray): Wireless.WifiVersionResponse {
        if (payload.size !in 1..MAX_RESPONSE_BYTES) {
            throw IOException("HCT WPP version response length is invalid: ${payload.size}")
        }
        val response = try {
            Wireless.WifiVersionResponse.parseFrom(payload)
        } catch (e: IOException) {
            throw IOException("HCT WPP version response is malformed", e)
        }
        // The schema is optional for compatibility, but absent fields are not a successful
        // handshake. In particular, protobuf's absent status default of 0(SUCCESS) is not acceptance.
        if (!response.hasMajor() || response.major <= 0 ||
            !response.hasMinor() || response.minor < 0 || !response.hasStatus()
        ) {
            throw IOException("HCT WPP version response is incomplete")
        }
        // WPP -8(NO_SUPPORTED_WIFI_CHANNELS) can occur on 2.4 GHz/hotspot sessions that
        // still proceed to Wi-Fi setup. Accept it alongside 0(SUCCESS), as the Android radio
        // route does; neither status proves that Wi-Fi joined. These are WPP status codes,
        // not GAL MessageStatus values, where the same -8 means STATUS_MEDIA_CONFIG_MISMATCH.
        if (response.status != 0 && response.status != NO_SUPPORTED_WIFI_CHANNELS) {
            throw IOException("Phone rejected HCT WPP opening: status=${response.status}")
        }
        // Lower or higher versions are not assumed compatible with this carrier's opening.
        // No fallback contract for them has been established in the inspected sender path.
        if (response.major != 1 || response.minor != 7) {
            throw IOException("Phone returned an unsupported HCT WPP version")
        }
        return response
    }

    private const val NO_SUPPORTED_WIFI_CHANNELS = -8
}
