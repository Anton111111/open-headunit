package com.andrerinas.openheadunit.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.andrerinas.openheadunit.utils.SettingsBackupManager
import org.junit.Test

class PerformanceOverlayPolicyTest {

    @Test
    fun `app cpu is a share of the whole device, not of one core`() {
        // Two threads busy for a 1000 ms window on a quad core is half the device.
        assertEquals(50, PerformanceOverlayPolicy.appCpuPercent(2000L, 1000L, 4))
        assertEquals(25, PerformanceOverlayPolicy.appCpuPercent(1000L, 1000L, 4))
        assertEquals(100, PerformanceOverlayPolicy.appCpuPercent(4000L, 1000L, 4))
    }

    @Test
    fun `app cpu is clamped when the core count is under-reported`() {
        // A unit that hotplugs cores offline answers fewer than were actually busy.
        assertEquals(100, PerformanceOverlayPolicy.appCpuPercent(2000L, 1000L, 1))
    }

    @Test
    fun `app cpu guards a zero or negative delta`() {
        assertEquals(0, PerformanceOverlayPolicy.appCpuPercent(0L, 0L, 4))
        assertEquals(0, PerformanceOverlayPolicy.appCpuPercent(-500L, 1000L, 4))
        assertEquals(100, PerformanceOverlayPolicy.appCpuPercent(1000L, -1L, 1))
    }

    @Test
    fun `total cpu is the busy share of the aggregate line`() {
        assertEquals(0, PerformanceOverlayPolicy.totalCpuPercent(1000L, 1000L))
        assertEquals(100, PerformanceOverlayPolicy.totalCpuPercent(1000L, 0L))
        assertEquals(40, PerformanceOverlayPolicy.totalCpuPercent(1000L, 600L))
    }

    @Test
    fun `temperature takes the hottest zone and scales milli-degrees`() {
        assertEquals(67, PerformanceOverlayPolicy.temperatureC(listOf(65940, 67210)))
        assertEquals(67, PerformanceOverlayPolicy.temperatureC(listOf(67)))
    }

    @Test
    fun `temperature drops a zone outside the sanity range`() {
        assertEquals(67, PerformanceOverlayPolicy.temperatureC(listOf(67210, 200000, 5)))
        assertNull(PerformanceOverlayPolicy.temperatureC(listOf(200000)))
    }

    @Test
    fun `temperature is null only when no zone answered`() {
        assertNull(PerformanceOverlayPolicy.temperatureC(emptyList()))
        assertNull(PerformanceOverlayPolicy.temperatureC(listOf(null, null)))
    }

    @Test
    fun `an unreadable zone does not discard the zones that answered`() {
        // D-HU's measured shape: 17 zones read, osctsen and outtsen answer EINVAL.
        val zones = List(17) { 60000 + it * 500 } + listOf(null, null)
        assertEquals(68, PerformanceOverlayPolicy.temperatureC(zones))
    }

    private val all = PerformanceOverlayField.values().toSet()
    private val legacy = setOf(
        PerformanceOverlayField.FPS, PerformanceOverlayField.CPU,
        PerformanceOverlayField.TEMP, PerformanceOverlayField.FRAME
    )

    private fun text(fields: Set<PerformanceOverlayField>) =
        PerformanceOverlayPolicy.format(fields, 60, 12, 40, null, 45, 16L)

    @Test
    fun `all four lines print the text the overlay printed before`() {
        assertEquals("FPS: 60\nCPU: app 12% / sys 40%\nTemp: 45C\nFrame: 16ms", text(legacy))
    }

    @Test
    fun `fps alone is one line with no newline`() {
        assertEquals("FPS: 60", text(setOf(PerformanceOverlayField.FPS)))
    }

    @Test
    fun `each field alone prints only its own line`() {
        assertEquals("FPS: 60", text(setOf(PerformanceOverlayField.FPS)))
        assertEquals("CPU: app 12% / sys 40%", text(setOf(PerformanceOverlayField.CPU)))
        assertEquals("Temp: 45C", text(setOf(PerformanceOverlayField.TEMP)))
        assertEquals("Frame: 16ms", text(setOf(PerformanceOverlayField.FRAME)))
    }

    @Test
    fun `lines keep the order fps cpu temp frame`() {
        val set = setOf(PerformanceOverlayField.FRAME, PerformanceOverlayField.FPS)
        assertEquals("FPS: 60\nFrame: 16ms", text(set))
    }

    @Test
    fun `no field prints nothing`() {
        assertEquals("", text(emptySet()))
        assertTrue(PerformanceOverlayPolicy.isEmpty(emptySet()))
    }

    @Test
    fun `an unreadable value prints dashes`() {
        assertEquals(
            "FPS: --\nCPU: app -- / sys --\nTemp: --\nFrame: --\nBatt: --\nMem: --\nRSSI: --",
            PerformanceOverlayPolicy.format(all, null, null, null, null, null, null)
        )
    }

    @Test
    fun `system cpu falls back to the load average`() {
        assertEquals(
            "CPU: app 12% / sys 1.50 load",
            PerformanceOverlayPolicy.format(
                setOf(PerformanceOverlayField.CPU), null, 12, null, 1.5, null, null
            )
        )
    }

    @Test
    fun `the field set round-trips through its stored int`() {
        for (bits in 0..127) {
            val fields = PerformanceOverlayPolicy.fromBits(bits)
            assertEquals(bits, PerformanceOverlayPolicy.toBits(fields))
            assertEquals(fields, PerformanceOverlayPolicy.fromBits(PerformanceOverlayPolicy.toBits(fields)))
        }
    }

    @Test
    fun `the stored default is the original four lines`() {
        assertEquals(legacy, PerformanceOverlayPolicy.fromBits(PerformanceOverlayPolicy.DEFAULT_BITS))
    }

    @Test
    fun `unknown bits are ignored`() {
        assertEquals(all, PerformanceOverlayPolicy.fromBits(0xFF))
    }

    @Test
    fun `sampling names only the sources a line needs`() {
        assertEquals(emptySet<PerformanceOverlaySource>(),
            PerformanceOverlayPolicy.sampling(setOf(PerformanceOverlayField.FPS, PerformanceOverlayField.FRAME)))
        assertEquals(setOf(PerformanceOverlaySource.CPU),
            PerformanceOverlayPolicy.sampling(setOf(PerformanceOverlayField.CPU)))
        assertEquals(setOf(PerformanceOverlaySource.TEMP),
            PerformanceOverlayPolicy.sampling(setOf(PerformanceOverlayField.TEMP)))
        assertEquals(PerformanceOverlaySource.values().toSet(), PerformanceOverlayPolicy.sampling(all))
    }

    @Test
    fun `describe names the fields and the sources`() {
        assertEquals("fields=FPS sources=none",
            PerformanceOverlayPolicy.describe(setOf(PerformanceOverlayField.FPS)))
        assertEquals("fields=FPS,CPU,TEMP,FRAME,BATTERY,MEMORY,WIFI sources=cpu,temp,battery,memory,wifi",
            PerformanceOverlayPolicy.describe(all))
        assertEquals("fields=none sources=none", PerformanceOverlayPolicy.describe(emptySet()))
    }

    private fun device(readings: DeviceReadings, field: PerformanceOverlayField) =
        PerformanceOverlayPolicy.format(setOf(field), null, null, null, null, null, null, readings)

    @Test
    fun `battery prints the level and marks a unit that is plugged in`() {
        assertEquals("Batt: 64%", device(DeviceReadings(batteryPercent = 64), PerformanceOverlayField.BATTERY))
        assertEquals("Batt: 64% +",
            device(DeviceReadings(batteryPercent = 64, pluggedIn = true), PerformanceOverlayField.BATTERY))
        assertEquals("Batt: --", device(DeviceReadings(pluggedIn = true), PerformanceOverlayField.BATTERY))
    }

    @Test
    fun `memory and signal print their units or dashes`() {
        assertEquals("Mem: 412MB", device(DeviceReadings(freeMemoryMb = 412), PerformanceOverlayField.MEMORY))
        assertEquals("RSSI: -61dBm", device(DeviceReadings(stationRssiDbm = -61), PerformanceOverlayField.WIFI))
        assertEquals("RSSI: --", device(DeviceReadings(), PerformanceOverlayField.WIFI))
    }

    @Test
    fun `battery percent scales the level and rejects missing extras`() {
        assertEquals(64, PerformanceOverlayPolicy.batteryPercent(64, 100))
        assertEquals(50, PerformanceOverlayPolicy.batteryPercent(128, 256))
        assertEquals(100, PerformanceOverlayPolicy.batteryPercent(120, 100))
        assertNull(PerformanceOverlayPolicy.batteryPercent(-1, 100))
        assertNull(PerformanceOverlayPolicy.batteryPercent(50, 0))
    }

    @Test
    fun `megabytes floors and rejects a negative count`() {
        assertEquals(412L, PerformanceOverlayPolicy.megabytes(412L * 1024 * 1024 + 1000))
        assertNull(PerformanceOverlayPolicy.megabytes(-1L))
    }

    @Test
    fun `station rssi needs an associated station and a real reading`() {
        assertEquals(-61, PerformanceOverlayPolicy.stationRssi(-61, true))
        assertNull(PerformanceOverlayPolicy.stationRssi(-61, false))
        assertNull(PerformanceOverlayPolicy.stationRssi(-127, true))
        assertNull(PerformanceOverlayPolicy.stationRssi(-200, true))
        assertNull(PerformanceOverlayPolicy.stationRssi(0, true))
    }

    @Test
    fun `sampling reads each device source only for its own line`() {
        assertEquals(setOf(PerformanceOverlaySource.BATTERY),
            PerformanceOverlayPolicy.sampling(setOf(PerformanceOverlayField.BATTERY)))
        assertEquals(setOf(PerformanceOverlaySource.MEMORY),
            PerformanceOverlayPolicy.sampling(setOf(PerformanceOverlayField.MEMORY)))
        assertEquals(setOf(PerformanceOverlaySource.WIFI),
            PerformanceOverlayPolicy.sampling(setOf(PerformanceOverlayField.WIFI)))
    }

    @Test
    fun `overlay-fields is in the settings backup as an int`() {
        assertEquals(SettingsBackupManager.ValueType.INT, SettingsBackupManager.backupKeys["overlay-fields"])
    }

    private val labels = mapOf(
        PerformanceOverlayField.FPS to "FPS",
        PerformanceOverlayField.CPU to "CPU",
        PerformanceOverlayField.TEMP to "Temperature",
        PerformanceOverlayField.FRAME to "Frame age"
    )

    private fun summary(show: Boolean, fields: Set<PerformanceOverlayField>, side: String) =
        PerformanceOverlayPolicy.entrySummary(show, fields, labels, "Off", "No lines", side)

    @Test
    fun `the entry row reads off while the overlay is off`() {
        assertEquals("Off", summary(false, all, "left"))
    }

    @Test
    fun `the entry row names the lines and the side`() {
        val set = setOf(PerformanceOverlayField.FPS, PerformanceOverlayField.CPU)
        assertEquals("FPS, CPU (left)", summary(true, set, "left"))
    }

    @Test
    fun `the entry row keeps the line order`() {
        val set = setOf(PerformanceOverlayField.FRAME, PerformanceOverlayField.FPS)
        assertEquals("FPS, Frame age (right)", summary(true, set, "right"))
    }

    @Test
    fun `the entry row says when no line is chosen`() {
        assertEquals("No lines (left)", summary(true, emptySet(), "left"))
    }
}
