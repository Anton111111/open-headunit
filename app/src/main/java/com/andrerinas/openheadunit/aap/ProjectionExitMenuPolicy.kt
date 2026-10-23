package com.andrerinas.openheadunit.aap

/**
 * What the projection's exit menu offers, in the order it lists them.
 *
 * While this app is the unit's home its home screen is one tap away, so the menu leaves out what
 * the driver can reach from there: picture-in-picture and quick settings. It keeps what no other
 * screen does: stopping, the WiFi Direct "stay ready" stop, the background and the phone switch.
 * Pure, so every combination is a unit test rather than a head unit.
 */
object ProjectionExitMenuPolicy {

    enum class Option { STOP, END_STAY_ARMED, PIP, BACKGROUND, SETTINGS, SWITCH_PHONE }

    data class Input(
        /** This app holds the home role, not only the car launcher switch: a declined role leaves the factory home. */
        val isHomeApp: Boolean,
        /** Picture-in-picture works here: Android 8.0 and up, on a unit that declares the feature. */
        val pipWorks: Boolean,
        /** The phone connects through Android Auto's own wireless flow. */
        val nativeWireless: Boolean,
        /** The phone is reached through an external Bluetooth module, which has no phone switch here. */
        val externalBtModule: Boolean,
        /** The wireless network is a WiFi Direct group this app created for the session. */
        val wifiDirectGroup: Boolean,
    )

    fun options(input: Input): List<Option> {
        val short = input.isHomeApp
        val options = mutableListOf(Option.STOP)
        // A plain Stop removes the group, and the phone's saved profile then names one that is gone.
        if (input.nativeWireless && input.wifiDirectGroup) options.add(Option.END_STAY_ARMED)
        if (!short && input.pipWorks) options.add(Option.PIP)
        options.add(Option.BACKGROUND)
        if (!short) options.add(Option.SETTINGS)
        if (input.nativeWireless && !input.externalBtModule) options.add(Option.SWITCH_PHONE)
        return options
    }
}
