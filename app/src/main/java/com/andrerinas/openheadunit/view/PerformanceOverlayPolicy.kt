package com.andrerinas.openheadunit.view

import java.util.Locale

/** One overlay line each. Declaration order is the print order; the bit is the stored flag. */
enum class PerformanceOverlayField(val bit: Int) {
    FPS(1), CPU(2), TEMP(4), FRAME(8), BATTERY(16), MEMORY(32), WIFI(64)
}

/** What the sampler must read for the chosen lines. */
enum class PerformanceOverlaySource { CPU, TEMP, BATTERY, MEMORY, WIFI }

/** The head unit's own state. Null is a value that could not be read and prints as dashes. */
data class DeviceReadings(
    val batteryPercent: Int? = null,
    val pluggedIn: Boolean = false,
    val freeMemoryMb: Long? = null,
    val stationRssiDbm: Int? = null
)

/**
 * The decidable half of the projection performance overlay: which lines print, what they say, what
 * must be sampled for them, the two CPU figures and the temperature it picks out of the thermal zones.
 */
object PerformanceOverlayPolicy {

    const val DEFAULT_BITS = 0b1111

    fun toBits(fields: Set<PerformanceOverlayField>): Int = fields.fold(0) { acc, f -> acc or f.bit }

    fun fromBits(bits: Int): Set<PerformanceOverlayField> =
        PerformanceOverlayField.values().filter { bits and it.bit != 0 }.toSet()

    fun isEmpty(fields: Set<PerformanceOverlayField>): Boolean = fields.isEmpty()

    fun format(
        fields: Set<PerformanceOverlayField>,
        fps: Int?,
        appCpu: Int?,
        totalCpu: Int?,
        loadAverage: Double?,
        tempC: Int?,
        frameAgeMs: Long?,
        device: DeviceReadings = DeviceReadings()
    ): String = PerformanceOverlayField.values().filter { it in fields }.joinToString("\n") { field ->
        when (field) {
            PerformanceOverlayField.FPS -> "FPS: ${fps?.toString() ?: "--"}"
            PerformanceOverlayField.CPU -> {
                val app = appCpu?.let { "$it%" } ?: "--"
                val sys = totalCpu?.let { "$it%" }
                    ?: loadAverage?.let { String.format(Locale.US, "%.2f load", it) }
                    ?: "--"
                "CPU: app $app / sys $sys"
            }
            PerformanceOverlayField.TEMP -> "Temp: ${tempC?.let { "${it}C" } ?: "--"}"
            PerformanceOverlayField.FRAME -> "Frame: ${frameAgeMs?.let { "${it}ms" } ?: "--"}"
            PerformanceOverlayField.BATTERY -> {
                val level = device.batteryPercent?.let { "$it%" } ?: "--"
                // The mark says the car is feeding the unit, which a full battery alone does not.
                if (device.batteryPercent != null && device.pluggedIn) "Batt: $level +" else "Batt: $level"
            }
            PerformanceOverlayField.MEMORY -> "Mem: ${device.freeMemoryMb?.let { "${it}MB" } ?: "--"}"
            PerformanceOverlayField.WIFI -> "RSSI: ${device.stationRssiDbm?.let { "${it}dBm" } ?: "--"}"
        }
    }

    fun sampling(fields: Set<PerformanceOverlayField>): Set<PerformanceOverlaySource> {
        val sources = mutableSetOf<PerformanceOverlaySource>()
        if (PerformanceOverlayField.CPU in fields) sources.add(PerformanceOverlaySource.CPU)
        if (PerformanceOverlayField.TEMP in fields) sources.add(PerformanceOverlaySource.TEMP)
        if (PerformanceOverlayField.BATTERY in fields) sources.add(PerformanceOverlaySource.BATTERY)
        if (PerformanceOverlayField.MEMORY in fields) sources.add(PerformanceOverlaySource.MEMORY)
        if (PerformanceOverlayField.WIFI in fields) sources.add(PerformanceOverlaySource.WIFI)
        return sources
    }

    /** The settings row's value: the chosen lines and the side, or [off] while the overlay is off. */
    fun entrySummary(
        show: Boolean,
        fields: Set<PerformanceOverlayField>,
        labels: Map<PerformanceOverlayField, String>,
        off: String,
        none: String,
        side: String
    ): String {
        if (!show) return off
        val names = PerformanceOverlayField.values().filter { it in fields }
            .joinToString(", ") { labels[it] ?: it.name }
        return "${names.ifEmpty { none }} ($side)"
    }

    fun describe(fields: Set<PerformanceOverlayField>): String {
        val names = PerformanceOverlayField.values().filter { it in fields }.joinToString(",") { it.name }
        val sources = PerformanceOverlaySource.values().filter { it in sampling(fields) }
            .joinToString(",") { it.name.lowercase(Locale.US) }
        return "fields=${names.ifEmpty { "none" }} sources=${sources.ifEmpty { "none" }}"
    }

    /**
     * Process CPU time sums every thread, so on a multi-core unit it can exceed the wall clock it
     * is measured over. Divided by the core count it reads on the same 0-100 scale as the system
     * figure printed next to it, and the clamp covers a unit that hotplugs cores offline.
     */
    fun appCpuPercent(cpuDeltaMs: Long, elapsedDeltaMs: Long, coreCount: Int): Int {
        val elapsed = elapsedDeltaMs.coerceAtLeast(1L)
        val cpu = cpuDeltaMs.coerceAtLeast(0L)
        val cores = coreCount.coerceAtLeast(1)
        return ((cpu.toDouble() / (elapsed * cores)) * 100.0).toInt().coerceIn(0, 100)
    }

    /** Busy share of /proc/stat's aggregate cpu line, which already sums every core. */
    fun totalCpuPercent(totalDelta: Long, idleDelta: Long): Int {
        val total = totalDelta.coerceAtLeast(1L)
        val idle = idleDelta.coerceAtLeast(0L)
        return (((total - idle).toDouble() / total) * 100.0).toInt().coerceIn(0, 100)
    }

    /**
     * The hottest zone that answered. A zone that could not be read is a null entry and is skipped:
     * one EINVAL zone used to discard the whole scan, including the seventeen that read fine.
     */
    fun temperatureC(rawZoneValues: List<Int?>): Int? = rawZoneValues
        .mapNotNull { raw ->
            when {
                raw == null -> null
                raw in 10000..125000 -> raw / 1000
                raw in 10..125 -> raw
                else -> null
            }
        }
        .maxOrNull()

    /** The sticky battery broadcast reports -1 for a missing extra; a scale of 0 is a broken ROM. */
    fun batteryPercent(level: Int, scale: Int): Int? =
        if (level < 0 || scale <= 0) null else (level * 100 / scale).coerceIn(0, 100)

    fun megabytes(bytes: Long): Long? = if (bytes < 0L) null else bytes / (1024L * 1024L)

    /**
     * Only a station that finished association has a signal. A disconnected one reports -127 or
     * an older platform's -200, and P2P groups have no public RSSI at all, so those print dashes.
     */
    fun stationRssi(rssi: Int, associated: Boolean): Int? =
        if (associated && rssi in -126..-1) rssi else null
}
