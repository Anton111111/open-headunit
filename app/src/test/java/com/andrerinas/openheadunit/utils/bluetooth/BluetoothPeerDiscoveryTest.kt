package com.andrerinas.openheadunit.utils.bluetooth

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.BluetoothWakePolicy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.BluetoothWakePolicy.BondReading
import com.andrerinas.openheadunit.utils.bluetooth.BluetoothPeerInventory.Entry
import com.andrerinas.openheadunit.utils.bluetooth.BluetoothPeerInventory.Reading
import com.andrerinas.openheadunit.utils.bluetooth.BluetoothPeerInventory.Source
import com.andrerinas.openheadunit.utils.bluetooth.BluetoothPeerInventory.Status
import org.junit.Assert.*
import org.junit.Test

class BluetoothPeerDiscoveryTest {
    private val phone = "12:34:56:78:9A:BC"
    private fun inventory(vararg readings: Reading) = BluetoothPeerInventory(readings.toList())

    @Test fun `factory cache can explain an empty Android list without creating a wake target`() {
        val result = inventory(
            Reading(Source.ANDROID, Status.READABLE),
            HctPairedDeviceCache.parse("0123456789ABCMy phone")
        )
        assertEquals(phone, result.manufacturerOnly.single().address)
        assertEquals("My phone", result.peers.single().name)
        assertFalse(result.peers.single().visibleToAndroid)
        assertTrue(result.hasManufacturerPairing(phone.lowercase()))
        assertFalse(BluetoothWakePolicy.shouldForget(BondReading.NOT_BONDED, result.manufacturerPairing(phone)))
        assertFalse(BluetoothWakePolicy.mayPoke(BondReading.NOT_BONDED))
    }

    @Test fun `standard pairing wins when sources overlap regardless of provider order`() {
        val android = Reading(Source.ANDROID, Status.READABLE, listOf(Entry(phone, "Current name")))
        val vendor = HctPairedDeviceCache.parse("0123456789abcOld name")
        for (readings in listOf(listOf(android, vendor), listOf(vendor, android))) {
            val result = BluetoothPeerInventory(readings)
            assertEquals(1, result.peers.size)
            assertEquals("Current name", result.peers.single().name)
            assertTrue(result.peers.single().visibleToAndroid)
            assertEquals(setOf(Source.ANDROID, Source.HCT_PAIR_CACHE), result.peers.single().sources)
            assertTrue(result.manufacturerOnly.isEmpty())
        }
    }

    @Test fun `a manufacturer name fills missing metadata without supplying an Android bond`() {
        val vendor = HctPairedDeviceCache.parse("0123456789ABCName")
        val paired = inventory(Reading(Source.ANDROID, Status.READABLE, listOf(Entry(phone, null))), vendor)
        assertEquals("Name", paired.peers.single().name)
        assertTrue(paired.peers.single().visibleToAndroid)
        val unreadable = inventory(Reading(Source.ANDROID, Status.UNAVAILABLE), vendor)
        assertFalse(unreadable.peers.single().visibleToAndroid)
    }

    @Test fun `missing empty denied and malformed sources are different`() {
        assertEquals(Status.ABSENT, HctPairedDeviceCache.parse(null).status)
        assertEquals(Status.READABLE, HctPairedDeviceCache.parse("").status)
        assertEquals(Status.UNAVAILABLE, HctPairedDeviceCache.read { throw SecurityException() }.status)
        assertEquals(Status.UNAVAILABLE, HctPairedDeviceCache.read { error("provider unavailable") }.status)
        assertEquals(Status.MALFORMED, HctPairedDeviceCache.parse("unknown encoding").status)
    }

    @Test fun `manufacturer read failures preserve preferences but cannot permit a socket`() {
        for (reading in listOf(
            HctPairedDeviceCache.read { throw SecurityException() },
            HctPairedDeviceCache.parse("unsupported layout"),
            HctPairedDeviceCache.parse("0123456789ABCName\nbad row")
        )) {
            val result = inventory(reading)
            val other = "AA:BB:CC:DD:EE:FF"
            assertNull(result.manufacturerPairing(other))
            assertFalse(BluetoothWakePolicy.shouldForget(BondReading.NOT_BONDED, result.manufacturerPairing(other)))
            assertFalse(BluetoothWakePolicy.mayPoke(BondReading.NOT_BONDED))
            assertTrue(BluetoothWakePolicy.shouldForget(BondReading.MALFORMED, result.manufacturerPairing(other)))
        }
    }

    @Test fun `factory unpair is picked up on the next read without a process cache`() {
        var raw: String? = "0123456789ABCName"
        assertTrue(inventory(HctPairedDeviceCache.read { raw }).hasManufacturerPairing(phone))
        raw = ""
        val result = inventory(HctPairedDeviceCache.read { raw })
        assertFalse(result.hasManufacturerPairing(phone))
        assertTrue(BluetoothWakePolicy.shouldForget(BondReading.NOT_BONDED, result.manufacturerPairing(phone)))
    }

    @Test fun `unavailable manufacturer source does not hide standard devices`() {
        val result = inventory(
            Reading(Source.ANDROID, Status.READABLE, listOf(Entry(phone, "Phone"))),
            HctPairedDeviceCache.read { throw SecurityException() }
        )
        assertTrue(result.peers.single().visibleToAndroid)
        assertTrue(result.manufacturerOnly.isEmpty())
        assertTrue(BluetoothWakePolicy.mayPoke(BondReading.BONDED))
    }

    @Test fun `parser accepts recorded framing and deduplicates but does not guess other encodings`() {
        val result = HctPairedDeviceCache.parse(
            "0123456789ABCOne\r\n1123456789abcDuplicate\n\nxAABBCCDDEEFFTwo\n123456789ABC\n" +
                "0GG3456789ABCWrong hex\n0020000000000Masked\n0000000000000Zero\n0FFFFFFFFFFFFBroadcast"
        )
        assertEquals(Status.READABLE, result.status)
        assertEquals(listOf(phone, "AA:BB:CC:DD:EE:FF"), result.entries.map { it.address })
        assertEquals(5, result.rejectedRows)
        assertEquals("One", result.entries.first().name)
    }

    @Test fun `oversized values and row counts are rejected as incomplete knowledge`() {
        assertEquals(Status.MALFORMED, HctPairedDeviceCache.parse("x".repeat(65_537)).status)
        assertEquals(Status.MALFORMED,
            HctPairedDeviceCache.parse(List(257) { "0123456789ABCPhone" }.joinToString("\n")).status)
        assertEquals(Status.READABLE,
            HctPairedDeviceCache.parse(List(256) { "0123456789ABCPhone" }.joinToString("\n")).status)
    }

    @Test fun `control characters cannot spoof rows and diagnostics omit personal information`() {
        val result = inventory(HctPairedDeviceCache.parse("0123456789ABC\u202EPrivate\u0000 name"))
        assertEquals("Private name", result.peers.single().name)
        assertFalse(result.diagnostic().contains("Private"))
        assertFalse(result.diagnostic().contains(phone))
        assertTrue(result.diagnostic().contains("manufacturer-only=1"))
    }

    @Test fun `manufacturer cache does not lend Android eligibility to unrelated peers`() {
        val result = inventory(
            Reading(Source.ANDROID, Status.READABLE, listOf(Entry(phone, "Phone"))),
            HctPairedDeviceCache.parse("0AABBCCDDEEFFOther phone")
        )
        assertEquals(1, result.peers.count { it.visibleToAndroid })
        assertEquals("AA:BB:CC:DD:EE:FF", result.manufacturerOnly.single().address)
        assertNull(result.peer("not a MAC"))
    }
}
