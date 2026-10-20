package com.andrerinas.openheadunit.decoder.video.preflight

import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AnnexBAccessUnitsTest {

    private val h264 = VideoDecoder.CodecType.H264
    private val h265 = VideoDecoder.CodecType.H265

    @Test
    fun `h264 parameter sets and sei travel with the first picture`() {
        val (data, starts) = stream(
            nal(7, 0x67),
            nal(8, 0x68),
            nal(6, 0x06),
            nal(5, 0x65),
            nal(1, 0x41),
            nal(1, 0x41),
        )

        val units = AnnexBAccessUnits.split(data, h264)

        assertEquals(3, units.size)
        assertEquals(listOf(starts[0], starts[4], starts[5]), units.map { it.offset })
        assertEquals(
            listOf(starts[4] - starts[0], starts[5] - starts[4], data.size - starts[5]),
            units.map { it.length },
        )
    }

    @Test
    fun `h265 parameter sets travel with the first picture`() {
        val (data, starts) = stream(
            nal(32, 0x40),
            nal(33, 0x42),
            nal(34, 0x44),
            nal(39, 0x4E),
            nal(20, 0x28),
            nal(1, 0x02),
            nal(1, 0x02),
        )

        val units = AnnexBAccessUnits.split(data, h265)

        assertEquals(3, units.size)
        assertEquals(listOf(starts[0], starts[5], starts[6]), units.map { it.offset })
    }

    @Test
    fun `three byte start codes are kept as they are`() {
        val (data, starts) = streamWithStartCode(3, nal(7, 0x67), nal(5, 0x65), nal(1, 0x41))

        val units = AnnexBAccessUnits.split(data, h264)

        assertEquals(2, units.size)
        assertEquals(listOf(starts[0], starts[2]), units.map { it.offset })
        assertTrue(units.all { it.length > 0 })
    }

    @Test
    fun `an access unit delimiter starts a new unit`() {
        val (data, starts) = streamWithStartCode(
            4,
            nal(7, 0x67),
            nal(5, 0x65),
            nal(9, 0x09),
            nal(1, 0x41),
        )

        val units = AnnexBAccessUnits.split(data, h264)

        assertEquals(2, units.size)
        assertEquals(listOf(starts[0], starts[2]), units.map { it.offset })
        assertEquals(data.size - starts[2], units[1].length)
    }

    @Test
    fun `the shipped clips split into 150 access units`() {
        val clips = listOf(
            "preflight_h264_480p.h264" to h264,
            "preflight_h264_720p.h264" to h264,
            "preflight_h264_1080p.h264" to h264,
            "preflight_h265_1080p.h265" to h265,
        )
        for ((name, codec) in clips) {
            val data = File("src/main/res/raw/$name").readBytes()
            val units = AnnexBAccessUnits.split(data, codec)

            assertEquals(name, VideoPreflightClip.FRAME_COUNT, units.size)
            assertTrue("$name has empty units", units.all { it.length > 4 })
            assertTrue(
                "$name does not start with a start code",
                units.all { unit ->
                    data[unit.offset] == 0.toByte() && data[unit.offset + 1] == 0.toByte()
                },
            )
            val firstType = if (codec == h264) 7 else 32
            assertEquals(
                "$name does not carry its parameter set with the first picture",
                firstType,
                nalType(data, units[0].offset, codec),
            )
        }
    }

    private fun nalType(data: ByteArray, offset: Int, codec: VideoDecoder.CodecType): Int {
        if (offset + 3 > data.size) return -1
        val startCode = if (data[offset + 2] == 1.toByte()) 3 else 4
        val header = offset + startCode
        if (header >= data.size) return -1
        val value = data[header].toInt() and 0xFF
        return if (codec == h264) value and 0x1F else (value shr 1) and 0x3F
    }

    private fun stream(vararg nals: ByteArray): Pair<ByteArray, List<Int>> =
        streamWithStartCode(4, *nals)

    private fun streamWithStartCode(startCode: Int, vararg nals: ByteArray): Pair<ByteArray, List<Int>> {
        val out = mutableListOf<Byte>()
        val starts = mutableListOf<Int>()
        for (nal in nals) {
            starts += out.size
            repeat(startCode - 1) { out += 0 }
            out += 1
            out += nal.toList()
        }
        return out.toByteArray() to starts
    }

    private fun nal(type: Int, header: Int): ByteArray =
        byteArrayOf(header.toByte(), type.toByte(), 0x11, 0x22)
}
