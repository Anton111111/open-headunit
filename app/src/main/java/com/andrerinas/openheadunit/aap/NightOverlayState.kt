package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.MediaPlayback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small, session-owned display state; independent of media notification preferences. */
class NightOverlayState {
    data class Navigation(val maneuver: String, val distanceMeters: Int?)
    data class Content(
        val navigation: Navigation? = null,
        val title: String = "",
        val artist: String = "",
        val isPlaying: Boolean? = null
    )

    private var owner: AaPresentationSession? = null
    private val mutableContent = MutableStateFlow(Content())
    val content = mutableContent.asStateFlow()

    @Synchronized
    fun begin(session: AaPresentationSession) {
        owner = session
        mutableContent.value = Content()
    }

    @Synchronized
    fun metadata(session: AaPresentationSession, metadata: MediaPlayback.MediaMetaData) {
        if (owner !== session || !session.isActive) return
        mutableContent.value = mutableContent.value.copy(
            title = metadata.song.trim(),
            artist = metadata.artist.trim()
        )
    }

    @Synchronized
    fun playback(session: AaPresentationSession, status: MediaPlayback.MediaPlaybackStatus) {
        if (owner !== session || !session.isActive || !status.hasState()) return
        mutableContent.value = mutableContent.value.copy(
            isPlaying = status.state == MediaPlayback.MediaPlaybackStatus.State.PLAYING
        )
    }

    @Synchronized
    fun navigation(session: AaPresentationSession, navigation: Navigation?) {
        if (owner !== session || !session.isActive) return
        mutableContent.value = mutableContent.value.copy(navigation = navigation)
    }

    @Synchronized
    fun close(session: AaPresentationSession) {
        if (owner !== session) return
        owner = null
        mutableContent.value = Content()
    }
}
