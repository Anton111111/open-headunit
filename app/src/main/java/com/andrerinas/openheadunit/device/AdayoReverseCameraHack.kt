package com.andrerinas.openheadunit.device

internal interface AdayoGearSource {
    fun start(onGear: (Int) -> Unit)
    fun stop()
}

internal class AdayoReverseCameraHack(
    private val video: ProjectionVideoControl,
    private val gears: AdayoGearSource = AdayoMcuGearSource()
) : DeviceHack {
    override val id = ID
    private var active = false
    private var stopped = false
    // No observed reverse event means no owned suppression, including immediately after start.
    private var suppression: VideoSuppression? = null

    override fun start() {
        check(!stopped) { "Stopped Adayo hack cannot be restarted" }
        if (active) return
        active = true
        gears.start { gear ->
            if (active) {
                if (gear == REVERSE_GEAR && suppression == null) {
                    suppression = video.acquireSuppression("Adayo reverse camera")
                } else if (gear != REVERSE_GEAR) {
                    suppression?.release()
                    suppression = null
                }
            }
        }
    }

    override fun stop() {
        if (stopped) return
        active = false
        stopped = true
        try {
            gears.stop()
        } finally {
            suppression?.release()
            suppression = null
        }
    }

    companion object {
        const val ID = "adayo_haval.reverse_camera_video"
        private const val REVERSE_GEAR = 8
    }
}
