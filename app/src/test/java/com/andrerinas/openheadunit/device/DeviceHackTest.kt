package com.andrerinas.openheadunit.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceHackTest {
    @Test
    fun `suppression remains until all independent tokens are released`() {
        val changes = mutableListOf<Boolean>()
        val video = ProjectionVideoSuppression(changes::add)
        val camera = video.acquireSuppression("camera")
        val other = video.acquireSuppression("another workaround")
        camera.release()
        camera.release()
        assertTrue(video.suppressed.value)
        assertEquals(listOf(true), changes)
        other.release()
        assertFalse(video.suppressed.value)
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun `manager retains unchanged hacks and revokes only removed owner effects`() {
        val video = ProjectionVideoSuppression {}
        val manager = DeviceHackManager(video)
        val first = FakeHack("first")
        val second = FakeHack("second")
        val firstSpec = DeviceHackSpec(first.id) { first.video = it; first }
        val secondSpec = DeviceHackSpec(second.id) { second.video = it; second }
        manager.reconcile(listOf(firstSpec, secondSpec))
        first.video.acquireSuppression("first effect")
        second.video.acquireSuppression("second effect")
        manager.reconcile(listOf(secondSpec))
        assertEquals(1, first.stops)
        assertEquals(1, second.starts)
        assertTrue(video.suppressed.value)
        assertThrows(IllegalStateException::class.java) {
            first.video.acquireSuppression("late callback")
        }
        manager.stop()
        manager.stop()
        assertEquals(1, second.stops)
        assertFalse(video.suppressed.value)
    }

    @Test
    fun `configuration change replaces hack but unrelated refresh does not`() {
        val manager = DeviceHackManager(ProjectionVideoSuppression {})
        val old = FakeHack("workaround")
        val replacement = FakeHack("workaround")
        manager.reconcile(listOf(DeviceHackSpec(old.id, "one") { old }))
        manager.reconcile(listOf(DeviceHackSpec(old.id, "one") { error("Must not recreate") }))
        manager.reconcile(listOf(DeviceHackSpec(replacement.id, "two") { replacement }))
        assertEquals(1, old.starts)
        assertEquals(1, old.stops)
        assertEquals(1, replacement.starts)
        manager.stop()
    }

    @Test
    fun `non video hack needs no gear callback or video operations`() {
        val manager = DeviceHackManager(ProjectionVideoSuppression { error("Unexpected video effect") })
        val keys = FakeHack("hardware_keys")
        manager.reconcile(listOf(DeviceHackSpec(keys.id) { keys }))
        manager.stop()
        assertEquals(1, keys.starts)
        assertEquals(1, keys.stops)
    }

    @Test
    fun `failed startup releases owned effects and propagates failure`() {
        val video = ProjectionVideoSuppression {}
        val manager = DeviceHackManager(video)
        var stops = 0
        val spec = DeviceHackSpec("broken") { control ->
            object : DeviceHack {
                override val id = "broken"
                override fun start() {
                    control.acquireSuppression("partially started")
                    error("Startup failed")
                }
                override fun stop() { stops++ }
            }
        }
        assertThrows(IllegalStateException::class.java) { manager.reconcile(listOf(spec)) }
        assertFalse(video.suppressed.value)
        assertEquals(1, stops)
        manager.stop()
        assertEquals(1, stops)
    }

    @Test
    fun `projection hacks are created only in a live session and recreated on reconnect`() {
        val video = ProjectionVideoSuppression {}
        val events = mutableListOf<Pair<String, Boolean>>()
        val manager = DeviceHackManager(video) { id, started -> events.add(id to started) }
        val instances = mutableListOf<FakeHack>()
        val sessionSpec = DeviceHackSpec("camera", scope = DeviceHackScope.PROJECTION_SESSION) {
            FakeHack("camera").apply { this.video = it; instances.add(this) }
        }
        val serviceHack = FakeHack("service")
        val specs = listOf(sessionSpec, DeviceHackSpec(serviceHack.id) { serviceHack })

        manager.reconcile(specs, projectionActive = false)
        assertTrue(instances.isEmpty())
        assertEquals(1, serviceHack.starts)
        manager.reconcile(specs, projectionActive = true)
        val first = instances.single()
        first.video.acquireSuppression("reverse")
        manager.reconcile(specs, projectionActive = true)
        assertEquals(1, instances.size)
        assertEquals(1, first.starts)
        manager.reconcile(specs, projectionActive = false)
        assertEquals(1, first.stops)
        assertFalse(video.suppressed.value)
        assertEquals(0, serviceHack.stops)
        manager.reconcile(specs, projectionActive = true)
        assertEquals(2, instances.size)
        assertEquals(1, instances.last().starts)
        assertEquals(listOf(true, false, true), events.filter { it.first == "camera" }.map { it.second })
        manager.stop()
        assertEquals(1, instances.last().stops)
        assertEquals(1, serviceHack.stops)
    }

    @Test
    fun `enabling projection hack while idle waits for projection and disabling stops it`() {
        val manager = DeviceHackManager(ProjectionVideoSuppression {})
        val hack = FakeHack("camera")
        val spec = DeviceHackSpec(hack.id, scope = DeviceHackScope.PROJECTION_SESSION) { hack }
        manager.reconcile(emptyList(), projectionActive = false)
        manager.reconcile(listOf(spec), projectionActive = false)
        assertEquals(0, hack.starts)
        manager.reconcile(listOf(spec), projectionActive = true)
        assertEquals(1, hack.starts)
        manager.reconcile(emptyList(), projectionActive = true)
        assertEquals(1, hack.stops)
    }

    @Test
    fun `Adayo reverse off without preceding on does not change video state`() {
        val changes = mutableListOf<Boolean>()
        val video = ProjectionVideoSuppression(changes::add)
        val gears = FakeGears()
        val hack = AdayoReverseCameraHack(video, gears)
        hack.start()
        gears.emit(1)
        gears.emit(0)
        gears.emit(1)
        assertTrue(changes.isEmpty())
        assertFalse(video.suppressed.value)
        val other = video.acquireSuppression("another hack")
        gears.emit(1)
        assertEquals(listOf(true), changes)
        assertTrue(video.suppressed.value)
        other.release()
        hack.stop()
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun `Adayo handles repeated gear events stop and late callbacks`() {
        val changes = mutableListOf<Boolean>()
        val video = ProjectionVideoSuppression(changes::add)
        val gears = FakeGears()
        val hack = AdayoReverseCameraHack(video, gears)
        hack.start()
        hack.start()
        assertEquals(1, gears.starts)
        gears.emit(8)
        gears.emit(8)
        assertEquals(listOf(true), changes)
        gears.emit(1)
        assertFalse(video.suppressed.value)
        gears.emit(8)
        hack.stop()
        hack.stop()
        gears.emit(8)
        assertEquals(1, gears.stops)
        assertFalse(video.suppressed.value)
        assertEquals(listOf(true, false, true, false), changes)
    }

    private class FakeHack(override val id: String) : DeviceHack {
        var starts = 0
        var stops = 0
        lateinit var video: ProjectionVideoControl
        override fun start() { starts++ }
        override fun stop() { stops++ }
    }

    private class FakeGears : AdayoGearSource {
        var starts = 0
        var stops = 0
        private lateinit var listener: (Int) -> Unit
        override fun start(onGear: (Int) -> Unit) { starts++; listener = onGear }
        override fun stop() { stops++ }
        fun emit(gear: Int) = listener(gear)
    }
}
