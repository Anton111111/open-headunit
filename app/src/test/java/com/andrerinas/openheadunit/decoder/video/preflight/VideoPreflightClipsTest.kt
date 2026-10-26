package com.andrerinas.openheadunit.decoder.video.preflight

import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPreflightClipsTest {

    @Test
    fun `a 1080p panel tests the three h264 sizes`() {
        val clips = VideoPreflightClips.forPanel(
            Settings.Resolution._1920x1080,
            hevcSupported = true,
            storedCodec = "Auto",
            forceSoftware = false,
        )

        assertEquals(
            listOf(
                VideoPreflightClip.H264_480P,
                VideoPreflightClip.H264_720P,
                VideoPreflightClip.H264_1080P,
            ),
            clips,
        )
    }

    @Test
    fun `a 480p panel only tests 480p`() {
        val clips = VideoPreflightClips.forPanel(
            Settings.Resolution._800x480,
            hevcSupported = true,
            storedCodec = "Auto",
            forceSoftware = false,
        )

        assertEquals(listOf(VideoPreflightClip.H264_480P), clips)
    }

    @Test
    fun `a panel above full hd adds h265`() {
        val clips = VideoPreflightClips.forPanel(
            Settings.Resolution._2560x1440,
            hevcSupported = true,
            storedCodec = "Auto",
            forceSoftware = false,
        )

        assertTrue(VideoPreflightClip.H265_1080P in clips)
    }

    @Test
    fun `no hardware hevc means no h265 clip`() {
        val clips = VideoPreflightClips.forPanel(
            Settings.Resolution._2560x1440,
            hevcSupported = false,
            storedCodec = "Auto",
            forceSoftware = false,
        )

        assertFalse(VideoPreflightClip.H265_1080P in clips)
    }

    @Test
    fun `a stored h265 setting is tested on a full hd panel`() {
        val clips = VideoPreflightClips.forPanel(
            Settings.Resolution._1920x1080,
            hevcSupported = true,
            storedCodec = "H.265",
            forceSoftware = false,
        )

        assertTrue(VideoPreflightClip.H265_1080P in clips)
    }

    @Test
    fun `forced software decoding does not test hardware h265`() {
        val clips = VideoPreflightClips.forPanel(
            Settings.Resolution._2560x1440,
            hevcSupported = true,
            storedCodec = "H.265",
            forceSoftware = true,
        )

        assertFalse(VideoPreflightClip.H265_1080P in clips)
    }

    @Test
    fun `explicit software hevc is exercised without hardware support`() {
        for (hardware in listOf(false, true)) {
            val clips = VideoPreflightClips.forPanel(
                Settings.Resolution._1920x1080,
                hevcSupported = hardware,
                storedCodec = "H.265",
                forceSoftware = true,
                selectedSoftwareHevcAvailable = true,
            )
            assertEquals(VideoPreflightClip.H265_1080P, clips.last())
            assertTrue(VideoPreflightClip.H264_1080P in clips)
        }
    }

    @Test
    fun `software avc and Auto do not inherit hevc availability on a large panel`() {
        for (preference in listOf("H.264", "Auto")) {
            val clips = VideoPreflightClips.forPanel(
                Settings.Resolution._2560x1440,
                hevcSupported = true,
                storedCodec = preference,
                forceSoftware = true,
                selectedSoftwareHevcAvailable = true,
            )
            assertEquals(
                listOf(VideoPreflightClip.H264_480P, VideoPreflightClip.H264_720P, VideoPreflightClip.H264_1080P),
                clips,
            )
        }
    }
}
