package com.andrerinas.openheadunit.main.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLayoutPolicyTest {

    @Test
    fun `group position follows first and last`() {
        assertEquals(GroupPosition.SINGLE, GroupPosition.of(isFirst = true, isLast = true))
        assertEquals(GroupPosition.TOP, GroupPosition.of(isFirst = true, isLast = false))
        assertEquals(GroupPosition.MIDDLE, GroupPosition.of(isFirst = false, isLast = false))
        assertEquals(GroupPosition.BOTTOM, GroupPosition.of(isFirst = false, isLast = true))
    }

    @Test
    fun `short values sit inline`() {
        assertTrue(SettingsLayoutPolicy.showValueInline("USB"))
        assertTrue(SettingsLayoutPolicy.showValueInline("Auto: 213 (960x540 dp)"))
    }

    @Test
    fun `long, blank and multi-line values wrap under the title`() {
        assertFalse(SettingsLayoutPolicy.showValueInline("Run the system check to find the best settings."))
        assertFalse(SettingsLayoutPolicy.showValueInline(""))
        assertFalse(SettingsLayoutPolicy.showValueInline("   "))
        assertFalse(SettingsLayoutPolicy.showValueInline("On\nOff"))
    }

    @Test
    fun `inline limit is inclusive`() {
        val atLimit = "x".repeat(SettingsLayoutPolicy.MAX_INLINE_VALUE_LENGTH)
        assertTrue(SettingsLayoutPolicy.showValueInline(atLimit))
        assertFalse(SettingsLayoutPolicy.showValueInline(atLimit + "x"))
    }

    @Test
    fun `scope notice names what the connection choice hides`() {
        assertEquals(SettingsLayoutPolicy.ScopeNotice.NONE, SettingsLayoutPolicy.scopeNotice(showsUsb = true, showsWifi = true))
        assertEquals(SettingsLayoutPolicy.ScopeNotice.WIFI_HIDDEN, SettingsLayoutPolicy.scopeNotice(showsUsb = true, showsWifi = false))
        assertEquals(SettingsLayoutPolicy.ScopeNotice.USB_HIDDEN, SettingsLayoutPolicy.scopeNotice(showsUsb = false, showsWifi = true))
        assertEquals(SettingsLayoutPolicy.ScopeNotice.USB_AND_WIFI_HIDDEN, SettingsLayoutPolicy.scopeNotice(showsUsb = false, showsWifi = false))
    }

    @Test
    fun `active category is the last header at or above the first visible row`() {
        val headers = intArrayOf(1, 6, 10)
        assertEquals(0, SettingsLayoutPolicy.activeCategoryIndex(headers, 0))
        assertEquals(0, SettingsLayoutPolicy.activeCategoryIndex(headers, 5))
        assertEquals(1, SettingsLayoutPolicy.activeCategoryIndex(headers, 6))
        assertEquals(1, SettingsLayoutPolicy.activeCategoryIndex(headers, 9))
        assertEquals(2, SettingsLayoutPolicy.activeCategoryIndex(headers, 40))
    }

    @Test
    fun `no sections means no active category`() {
        assertEquals(-1, SettingsLayoutPolicy.activeCategoryIndex(IntArray(0), 3))
    }
}
