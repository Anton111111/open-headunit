package com.andrerinas.openheadunit.device

import android.content.Context
import com.andrerinas.openheadunit.utils.Settings

interface ProjectionDevicePlugin {
    val configurationKey: String

    fun start(onReverseGearChanged: (Boolean) -> Unit)

    fun stop()
}

class DeviceSpecificHeadUnitSettings(context: Context) {
    private val preferences = context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)

    var model: Model
        get() = Model.fromKey(preferences.getString(KEY_MODEL, null))
        set(value) {
            preferences.edit().putString(KEY_MODEL, value.key).apply()
        }

    var pauseProjectionOnReverse: Boolean
        get() = preferences.getBoolean(KEY_PAUSE_ON_REVERSE, false)
        set(value) {
            preferences.edit().putBoolean(KEY_PAUSE_ON_REVERSE, value).apply()
        }

    enum class Model(val key: String) {
        NONE("none"),
        ADAYO_HAVAL("adayo_haval");

        companion object {
            fun fromKey(key: String?): Model =
                values().firstOrNull { it.key == key } ?: NONE
        }
    }

    companion object {
        private const val KEY_MODEL = "device-specific-head-unit-model"
        private const val KEY_PAUSE_ON_REVERSE = "device-specific-pause-projection-on-reverse"
    }
}

object DeviceSpecificHeadUnitPlugins {
    fun canAccessMcu(): Boolean = AdayoHavalProjectionPlugin.canAccessMcu()

    fun createProjectionPlugin(context: Context): ProjectionDevicePlugin? {
        val settings = DeviceSpecificHeadUnitSettings(context)
        return if (settings.model == DeviceSpecificHeadUnitSettings.Model.ADAYO_HAVAL &&
            settings.pauseProjectionOnReverse
        ) {
            AdayoHavalProjectionPlugin()
        } else {
            null
        }
    }
}
