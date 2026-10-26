package com.andrerinas.openheadunit.decoder.video.preflight

import com.andrerinas.openheadunit.decoder.video.VideoDecoder

/**
 * Splits a raw Annex B stream into the access units MediaCodec expects.
 *
 * The clips are generated with one slice per picture and no access unit delimiters, which keeps
 * the split cheap: every VCL NAL starts a new picture, and the parameter sets and SEI that precede
 * the first one travel with it. A stream that does carry access unit delimiters is handled too:
 * an AUD starts a new unit, and a VCL NAL only starts one when the current unit has no AUD.
 *
 * A stream with several slices per picture and no AUD is not what these clips are, and splitting
 * one would hand MediaCodec each slice as a picture of its own. That is why the generator keeps
 * one slice per frame; the real-time path never needs this splitter.
 */
object AnnexBAccessUnits {

    /** A byte range in the source array that holds one complete access unit. */
    data class Unit(val offset: Int, val length: Int)

    private data class Nal(
        val startCodeOffset: Int,
        val headerOffset: Int,
        val endOffset: Int,
        val type: Int,
    )

    fun split(data: ByteArray, codec: VideoDecoder.CodecType): List<Unit> {
        val nals = findNals(data, codec)
        if (nals.isEmpty()) return emptyList()

        val units = mutableListOf<Unit>()
        var unitStart = nals.first().startCodeOffset
        var unitHasVcl = false
        var unitHasAud = false

        fun closeUnit(end: Int) {
            if (end > unitStart) units += Unit(unitStart, end - unitStart)
        }

        for (nal in nals) {
            when {
                nal.type == audType(codec) -> {
                    closeUnit(nal.startCodeOffset)
                    unitStart = nal.startCodeOffset
                    unitHasVcl = false
                    unitHasAud = true
                }
                isVcl(nal.type, codec) -> {
                    if (unitHasVcl && !unitHasAud) {
                        closeUnit(nal.startCodeOffset)
                        unitStart = nal.startCodeOffset
                    }
                    unitHasVcl = true
                }
            }
        }
        closeUnit(data.size)
        return units
    }

    /**
     * How many bytes at the start of [unit] are parameter sets (SPS and PPS, plus VPS for H.265),
     * or 0 when the unit does not start with them or holds nothing else.
     *
     * Android Auto sends the parameter sets as a message of their own and the first picture after
     * them, so the decoder starts on a configuration buffer and then an IDR. The clips carry both
     * in their first unit; splitting it here lets the test start the decoder the same way.
     */
    fun parameterSetPrefix(data: ByteArray, unit: Unit, codec: VideoDecoder.CodecType): Int {
        if (unit.length <= 0 || unit.offset < 0 || unit.offset + unit.length > data.size) return 0
        val slice = data.copyOfRange(unit.offset, unit.offset + unit.length)
        var end = 0
        for (nal in findNals(slice, codec)) {
            if (!isParameterSet(nal.type, codec)) break
            end = nal.endOffset
        }
        return if (end in 1 until slice.size) end else 0
    }

    private fun isParameterSet(type: Int, codec: VideoDecoder.CodecType): Boolean =
        if (codec == VideoDecoder.CodecType.H264) type == 7 || type == 8 else type in 32..34

    private fun findNals(data: ByteArray, codec: VideoDecoder.CodecType): List<Nal> {
        val starts = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                when {
                    data[i + 2] == 1.toByte() -> {
                        starts += i to i + 3
                        i += 3
                    }
                    i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> {
                        starts += i to i + 4
                        i += 4
                    }
                    else -> i++
                }
            } else {
                i++
            }
        }
        return starts.mapIndexed { index, (start, header) ->
            val end = if (index + 1 < starts.size) starts[index + 1].first else data.size
            Nal(start, header, end, nalType(data, header, codec))
        }
    }

    private fun nalType(data: ByteArray, headerOffset: Int, codec: VideoDecoder.CodecType): Int {
        if (headerOffset >= data.size) return -1
        val header = data[headerOffset].toInt() and 0xFF
        return if (codec == VideoDecoder.CodecType.H264) {
            header and 0x1F
        } else {
            (header shr 1) and 0x3F
        }
    }

    private fun isVcl(type: Int, codec: VideoDecoder.CodecType): Boolean =
        if (codec == VideoDecoder.CodecType.H264) type in 1..5 else type in 0..31

    private fun audType(codec: VideoDecoder.CodecType): Int =
        if (codec == VideoDecoder.CodecType.H264) 9 else 35
}
