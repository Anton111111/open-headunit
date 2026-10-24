package com.andrerinas.openheadunit.device

import android.content.Context
import android.content.SharedPreferences
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class DeviceProfilesTest {
    @Test
    fun `workarounds default to disabled and unknown model runs nothing`() {
        val store = mutableMapOf<String, Any>()
        val settings = settings(store)
        assertEquals("none", DeviceProfiles.selected(settings).id)
        assertTrue(DeviceProfiles.enabledHacks(settings).isEmpty())
        settings.modelKey = "adayo_haval"
        assertTrue(DeviceProfiles.enabledHacks(settings).isEmpty())
        settings.modelKey = "unknown-model"
        assertTrue(DeviceProfiles.enabledHacks(settings).isEmpty())
    }

    @Test
    fun `legacy Adayo preferences remain readable and independently switchable`() {
        val store = mutableMapOf<String, Any>(
            "device-specific-head-unit-model" to "adayo_haval",
            "device-specific-pause-projection-on-reverse" to true
        )
        val settings = settings(store)
        val profile = DeviceProfiles.selected(settings)
        val hack = profile.hacks.single()
        assertEquals("adayo_haval", profile.id)
        assertTrue(settings.isEnabled(hack))
        assertEquals(DeviceHackScope.PROJECTION_SESSION, DeviceProfiles.enabledHacks(settings).single().scope)
        assertEquals(listOf(hack.id), DeviceProfiles.enabledHacks(settings).map { it.id })
        settings.modelKey = "none"
        assertTrue(DeviceProfiles.enabledHacks(settings).isEmpty())
        settings.modelKey = "adayo_haval"
        assertTrue(settings.isEnabled(hack))
        settings.setEnabled(hack, false)
        assertFalse(store["device-specific-pause-projection-on-reverse"] as Boolean)
        assertTrue(DeviceProfiles.enabledHacks(settings).isEmpty())
    }

    @Test
    fun `registry has unique stable model hack and preference identifiers`() {
        val profiles = DeviceProfiles.all
        assertEquals(profiles.size, profiles.map { it.id }.distinct().size)
        val hacks = profiles.flatMap { it.hacks }
        assertEquals(hacks.size, hacks.map { it.id }.distinct().size)
        assertEquals(hacks.size, hacks.map { it.preferenceKey }.distinct().size)
    }

    private fun settings(store: MutableMap<String, Any>): DeviceSpecificHeadUnitSettings {
        val preferences = mock<SharedPreferences>()
        val editor = mock<SharedPreferences.Editor>()
        whenever(preferences.getString(any(), anyOrNull())).thenAnswer {
            store[it.arguments[0]] as String? ?: it.arguments[1]
        }
        whenever(preferences.getBoolean(any(), any())).thenAnswer {
            store[it.arguments[0]] as Boolean? ?: it.arguments[1]
        }
        whenever(preferences.edit()).thenReturn(editor)
        whenever(editor.putString(any(), any())).thenAnswer {
            store[it.arguments[0] as String] = it.arguments[1] as String
            editor
        }
        whenever(editor.putBoolean(any(), any())).thenAnswer {
            store[it.arguments[0] as String] = it.arguments[1] as Boolean
            editor
        }
        val context = mock<Context>()
        whenever(context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE))
            .thenReturn(preferences)
        return DeviceSpecificHeadUnitSettings(context)
    }
}
