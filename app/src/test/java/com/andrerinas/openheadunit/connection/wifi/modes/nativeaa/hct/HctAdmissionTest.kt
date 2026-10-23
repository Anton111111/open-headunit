package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

import org.junit.Assert.*
import org.junit.Test

class HctAdmissionTest {
    private val aa = listOf(HctAdmission.AA_MARKER)

    @Test fun `only standalone fresh AA indication admits a carrier attempt`() {
        val admission = HctAdmission()
        assertNull(admission.report(aa, 1, 2, 0, false))
        assertNull(admission.report(aa, null, null, 1, false))
        assertNull(admission.report(null, 1, 2, 2, false))
        assertNull(admission.report(listOf("00001101-0000-1000-8000-00805f9b34fb"), null, null, 3, true))
        assertNotNull(admission.report(aa, null, null, 4, true))
    }

    @Test fun `duplicates cannot extend expiry or steal current carrier ownership`() {
        val admission = HctAdmission()
        val ticket = admission.report(aa, null, null, 100, true)!!
        assertNull(admission.report(aa, null, null, 200, true))
        assertNull(admission.report(aa, 1, 2, 250, false))
        assertTrue(admission.fresh(ticket, 3100))
        assertFalse(admission.fresh(ticket, 3101))
        assertFalse(admission.fresh(ticket, 99))
        assertNull(admission.report(aa, null, null, 5000, true))
        assertFalse(admission.fresh(ticket, 5000))
    }

    @Test fun `disconnect withdrawal unknown states and service loss invalidate old ticket`() {
        val invalidations = listOf<(HctAdmission) -> Unit>(
            { it.report(null, 0, null, 20, false) },
            { it.report(null, -1, null, 20, false) },
            { it.report(null, 6, null, 20, false) },
            { it.report(null, null, 0, 20, false) },
            { it.report(null, null, 1, 20, false) },
            { it.report(null, null, 3, 20, false) },
            { it.report(emptyList(), null, null, 20, true) },
            { it.invalidate() }
        )
        invalidations.forEach { withdraw ->
            val admission = HctAdmission()
            val old = admission.report(aa, null, null, 10, true)!!
            withdraw(admission)
            assertFalse(admission.current(old))
            val next = admission.report(aa, null, null, 30, true)!!
            assertNotEquals(old.generation, next.generation)
        }
    }

    @Test fun `disconnect wins over marker in the same event`() {
        val admission = HctAdmission()
        assertNull(admission.report(aa, 0, 2, 0, true))
        assertNull(admission.report(aa, 1, 1, 0, true))
    }

    @Test fun `numeric addresses preserve leading zeroes and reject placeholders`() {
        assertEquals("00:01:02:03:04:05", HctAdmission.address(0x0102030405L))
        listOf(0L, -1L, 0xffffffffffffL, 0x1000000000000L, 0x020000000000L).forEach {
            assertNull(HctAdmission.address(it))
        }
    }

    @Test fun `ambiguous address reports are compared only to known Binder peer`() {
        assertTrue(HctAdmission.matchesAddress("001122334455", "00:11:22:33:44:55"))
        assertTrue(HctAdmission.matchesAddress(0x001122334455L.toString(), "00:11:22:33:44:55"))
        assertTrue(HctAdmission.matchesAddress("aa:bb:cc:dd:ee:ff", "AA:BB:CC:DD:EE:FF"))
        assertFalse(HctAdmission.matchesAddress("123", "00:11:22:33:44:55"))
        assertFalse(HctAdmission.matchesAddress(null, "00:11:22:33:44:55"))
    }
}
