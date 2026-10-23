package com.andrerinas.openheadunit.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface DeviceHack {
    val id: String
    /** Start once per instance; repeated starts while active must be harmless. */
    fun start()
    /** Release hardware subscriptions and ignore late callbacks. Safe before start and on repetition. */
    fun stop()
}

interface ProjectionVideoControl {
    fun acquireSuppression(reason: String): VideoSuppression
}

fun interface VideoSuppression {
    fun release()
}

class ProjectionVideoSuppression(
    private val onChanged: (Boolean) -> Unit
) : ProjectionVideoControl {
    private val tokens = mutableSetOf<Any>()
    private val state = MutableStateFlow(false)
    val suppressed: StateFlow<Boolean> = state.asStateFlow()

    @Synchronized
    override fun acquireSuppression(reason: String): VideoSuppression {
        require(reason.isNotBlank()) { "Video suppression requires a reason" }
        val token = Any()
        tokens.add(token)
        publish()
        return VideoSuppression { release(token) }
    }

    @Synchronized
    private fun release(token: Any) {
        if (tokens.remove(token)) publish()
    }

    private fun publish() {
        val suppressed = tokens.isNotEmpty()
        if (state.value == suppressed) return
        onChanged(suppressed)
        state.value = suppressed
    }
}

enum class DeviceHackScope {
    SERVICE,
    PROJECTION_SESSION
}

data class DeviceHackSpec(
    val id: String,
    val configurationKey: String = id,
    val scope: DeviceHackScope = DeviceHackScope.SERVICE,
    val create: (ProjectionVideoControl) -> DeviceHack
)

/** All lifecycle calls and hack effects run on the main thread. Stopped instances are not reused. */
class DeviceHackManager(
    private val video: ProjectionVideoControl,
    private val onLifecycleChanged: (id: String, started: Boolean) -> Unit = { _, _ -> }
) {
    private data class Running(
        val configurationKey: String,
        val hack: DeviceHack,
        val video: OwnedVideoControl
    )

    private val running = linkedMapOf<String, Running>()

    fun reconcile(specs: List<DeviceHackSpec>, projectionActive: Boolean = false) {
        require(specs.map { it.id }.distinct().size == specs.size) { "Duplicate device hack IDs" }
        val eligible = specs.filter {
            it.scope == DeviceHackScope.SERVICE || projectionActive
        }
        val requested = eligible.associateBy { it.id }
        running.keys.toList().forEach { id ->
            if (running.getValue(id).configurationKey != requested[id]?.configurationKey) {
                stop(id)
            }
        }
        eligible.forEach { spec ->
            if (spec.id !in running) {
                val ownedVideo = OwnedVideoControl(video)
                var started = false
                try {
                    val hack = spec.create(ownedVideo)
                    running[spec.id] = Running(spec.configurationKey, hack, ownedVideo)
                    require(hack.id == spec.id) { "Device hack ID does not match its definition" }
                    hack.start()
                    started = true
                    onLifecycleChanged(spec.id, true)
                } finally {
                    if (!started) {
                        ownedVideo.close()
                        stop(spec.id)
                    }
                }
            }
        }
    }

    fun stop() {
        running.keys.toList().forEach(::stop)
    }

    private fun stop(id: String) {
        val entry = running.remove(id) ?: return
        // Revoke effects first, including those attempted by a late hardware callback.
        entry.video.close()
        entry.hack.stop()
        onLifecycleChanged(id, false)
    }

    private class OwnedVideoControl(private val delegate: ProjectionVideoControl) : ProjectionVideoControl {
        private var closed = false
        private val tokens = mutableSetOf<VideoSuppression>()

        override fun acquireSuppression(reason: String): VideoSuppression {
            check(!closed) { "Stopped device hack cannot suppress video" }
            val token = delegate.acquireSuppression(reason)
            tokens.add(token)
            return VideoSuppression {
                if (tokens.remove(token)) token.release()
            }
        }

        fun close() {
            closed = true
            tokens.toList().forEach { it.release() }
            tokens.clear()
        }
    }
}
