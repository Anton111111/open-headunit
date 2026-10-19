package com.andrerinas.openheadunit.view

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The FPS / CPU / temperature / device HUD drawn over the projection. It owns its view, its one-second
 * sampling loop and the worker that reads /proc and /sys, so the activity only attaches it,
 * starts it and re-lifts it whenever something is added over the top.
 */
class PerformanceOverlay(
    private val settings: Settings,
    private val lastFrameRenderedMs: () -> Long
) {
    private var textView: TextView? = null
    private var currentFps: Int? = null
    private var released = false
    private var fields = emptySet<PerformanceOverlayField>()
    private var sources = emptySet<PerformanceOverlaySource>()

    private val handler = Handler(Looper.getMainLooper())
    private val sampler = PerformanceSampler()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PerformanceSampler").apply {
            priority = Thread.MIN_PRIORITY
        }
    }
    private val sampleInFlight = AtomicBoolean(false)
    private val tick = object : Runnable {
        override fun run() {
            requestUpdate()
            handler.postDelayed(this, 1000L)
        }
    }

    val isAttached: Boolean get() = textView != null

    // Named rather than inline so a relaunched activity can tell this instance's listener apart
    // from its own before clearing it - lambdas have no usable identity across instances.
    val fpsListener: (Int) -> Unit = { fps -> currentFps = fps }

    fun attachTo(container: FrameLayout) {
        if (textView != null) return
        fields = settings.overlayFields
        sources = PerformanceOverlayPolicy.sampling(fields)
        sampler.appContext = container.context.applicationContext
        AppLog.i("PerformanceOverlay: ${PerformanceOverlayPolicy.describe(fields)}")
        val view = TextView(container.context).apply {
            setTextColor(Color.YELLOW)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#80000000"))
            setPadding(10, 5, 10, 5)
            text = PerformanceOverlayPolicy.format(fields, null, null, null, null, null, null)
            // An empty text view still draws its padding and background, so strip both.
            if (PerformanceOverlayPolicy.isEmpty(fields)) {
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(0, 0, 0, 0)
            }
            // Lift it above everything. Only from API 21, which is why bringToFront exists below.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = 100f
                translationZ = 100f
            }
            if (settings.hudMirroring) {
                scaleX = -1.0f
            }
        }
        val onRight = settings.overlayPosition == Settings.OverlayPosition.RIGHT
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or if (onRight) Gravity.END else Gravity.START
            if (onRight) setMargins(0, 20, 20, 0) else setMargins(20, 20, 0, 0)
        }
        textView = view
        container.addView(view, params)
    }

    /**
     * Below API 21 nothing elevates the HUD and it is added before the projection view, so a
     * TextureView draws over it and a SurfaceView's hole punch erases it. Re-lift after every add.
     */
    fun bringToFront() {
        textView?.bringToFront()
    }

    fun setVisible(visible: Boolean) {
        textView?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun setMirror(scaleX: Float) {
        textView?.scaleX = scaleX
    }

    fun start() {
        handler.removeCallbacks(tick)
        if (PerformanceOverlayPolicy.isEmpty(fields)) return
        tick.run()
    }

    fun stop() {
        handler.removeCallbacks(tick)
    }

    fun release() {
        stop()
        released = true
        executor.shutdownNow()
    }

    private fun requestUpdate() {
        if (!sampleInFlight.compareAndSet(false, true)) return

        val fpsSnapshot = currentFps
        val lastFrameSnapshot = lastFrameRenderedMs()
        try {
            executor.execute {
                try {
                    val text = buildText(fpsSnapshot, lastFrameSnapshot)
                    handler.post {
                        if (!released && textView?.visibility == View.VISIBLE) {
                            textView?.text = text
                        }
                    }
                } catch (e: Exception) {
                    AppLog.w("PerformanceOverlay: update failed: ${e.message}")
                } finally {
                    sampleInFlight.set(false)
                }
            }
        } catch (e: RejectedExecutionException) {
            sampleInFlight.set(false)
        }
    }

    private fun buildText(fpsSnapshot: Int?, lastFrameSnapshot: Long): String {
        val metrics = sampler.sample(sources)
        val frameAgeMs = if (lastFrameSnapshot > 0L) {
            SystemClock.elapsedRealtime() - lastFrameSnapshot
        } else {
            null
        }
        return PerformanceOverlayPolicy.format(
            fields, fpsSnapshot, metrics.appCpuPercent, metrics.totalCpuPercent,
            metrics.loadAverage, metrics.temperatureC, frameAgeMs, metrics.device
        )
    }

    private class PerformanceSampler {
        private data class TotalCpuSnapshot(
            val totalJiffies: Long,
            val idleJiffies: Long
        )

        data class Metrics(
            val appCpuPercent: Int?,
            val totalCpuPercent: Int?,
            val loadAverage: Double?,
            val temperatureC: Int?,
            val device: DeviceReadings
        )

        @Volatile
        var appContext: Context? = null

        private val coreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        private var previousTotalCpu: TotalCpuSnapshot? = null
        private var previousProcessCpuMs: Long? = null
        private var previousElapsedMs: Long? = null

        fun sample(sources: Set<PerformanceOverlaySource>): Metrics {
            var appCpu: Int? = null
            var totalCpu: Int? = null
            var load: Double? = null
            if (PerformanceOverlaySource.CPU in sources) {
                val nowElapsedMs = SystemClock.elapsedRealtime()
                val nowProcessCpuMs = android.os.Process.getElapsedCpuTime()
                val previousProcess = previousProcessCpuMs
                val previousElapsed = previousElapsedMs
                previousProcessCpuMs = nowProcessCpuMs
                previousElapsedMs = nowElapsedMs

                if (previousProcess != null && previousElapsed != null) {
                    appCpu = PerformanceOverlayPolicy.appCpuPercent(
                        nowProcessCpuMs - previousProcess,
                        nowElapsedMs - previousElapsed,
                        coreCount
                    )
                }

                val currentTotalCpu = readTotalCpuSnapshot()
                val previousTotal = previousTotalCpu
                previousTotalCpu = currentTotalCpu
                if (currentTotalCpu != null && previousTotal != null) {
                    totalCpu = PerformanceOverlayPolicy.totalCpuPercent(
                        currentTotalCpu.totalJiffies - previousTotal.totalJiffies,
                        currentTotalCpu.idleJiffies - previousTotal.idleJiffies
                    )
                }
                load = readLoadAverage()
            }
            val temp = if (PerformanceOverlaySource.TEMP in sources) readTemperatureC() else null
            return Metrics(appCpu, totalCpu, load, temp, readDevice(sources))
        }

        private fun readDevice(sources: Set<PerformanceOverlaySource>): DeviceReadings {
            val context = appContext ?: return DeviceReadings()
            var percent: Int? = null
            var plugged = false
            if (PerformanceOverlaySource.BATTERY in sources) {
                // A null receiver only reads the sticky broadcast, so nothing is left registered.
                val battery = try {
                    context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                } catch (e: Exception) {
                    null
                }
                if (battery != null) {
                    percent = PerformanceOverlayPolicy.batteryPercent(
                        battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                        battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    )
                    plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                }
            }
            val freeMb = if (PerformanceOverlaySource.MEMORY in sources) readFreeMemoryMb(context) else null
            val rssi = if (PerformanceOverlaySource.WIFI in sources) readStationRssi(context) else null
            return DeviceReadings(percent, plugged, freeMb, rssi)
        }

        private fun readFreeMemoryMb(context: Context): Long? = try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            PerformanceOverlayPolicy.megabytes(info.availMem)
        } catch (e: Exception) {
            null
        }

        @Suppress("DEPRECATION")
        private fun readStationRssi(context: Context): Int? = try {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo
            if (info == null) null
            else PerformanceOverlayPolicy.stationRssi(info.rssi, info.supplicantState == SupplicantState.COMPLETED)
        } catch (e: Exception) {
            null
        }

        private fun readTotalCpuSnapshot(): TotalCpuSnapshot? {
            return try {
                val cpuLine = File("/proc/stat").useLines { lines ->
                    lines.firstOrNull { it.startsWith("cpu ") }
                } ?: return null
                val cpuValues = cpuLine.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                if (cpuValues.size < 5) return null
                val idle = cpuValues.getOrElse(3) { 0L } + cpuValues.getOrElse(4) { 0L }
                val total = cpuValues.take(8).sum()
                TotalCpuSnapshot(total, idle)
            } catch (e: Exception) {
                null
            }
        }

        // A denied read writes an SELinux audit line every sample, which on a ROM that denies it
        // filled a third of a reporter's log; one failure means the next reads fail too.
        private var loadAverageReadable = true

        private fun readLoadAverage(): Double? {
            if (!loadAverageReadable) return null
            return try {
                File("/proc/loadavg")
                    .readText()
                    .trim()
                    .split(Regex("\\s+"))
                    .firstOrNull()
                    ?.toDoubleOrNull()
            } catch (e: Exception) {
                loadAverageReadable = false
                null
            }
        }

        // Each zone is read on its own: some SoCs register a zone whose temp answers EINVAL, and
        // one of those used to throw out of the scan and discard every zone that did answer.
        private fun readTemperatureC(): Int? {
            return try {
                val thermalRoot = File("/sys/class/thermal")
                val raw = thermalRoot.listFiles()
                    ?.filter { it.name.startsWith("thermal_zone") }
                    ?.map { zone ->
                        try {
                            zone.resolve("temp").readText().trim().toIntOrNull()
                        } catch (e: Exception) {
                            null
                        }
                    }
                    .orEmpty()
                PerformanceOverlayPolicy.temperatureC(raw)
            } catch (e: Exception) {
                null
            }
        }
    }
}
