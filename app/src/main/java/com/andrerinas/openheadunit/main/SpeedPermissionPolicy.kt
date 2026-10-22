package com.andrerinas.openheadunit.main

/**
 * What the speed switch in Customization does once the location permission request comes back.
 *
 * After a denial, Android stops showing the dialog when the user picked "don't ask again" (or,
 * from Android 11, after the second denial): the request then fails at once and
 * shouldShowRequestPermissionRationale is false. Asking again would look like a dead switch, so
 * that case points to the app's system settings instead.
 */
object SpeedPermissionPolicy {

    enum class Outcome {
        /** Granted: turn the speed on. */
        SHOW_SPEED,
        /** Denied, but the system will still ask next time: just say why the switch went off. */
        EXPLAIN,
        /** Denied and the system will not ask again: offer the app's system settings. */
        OPEN_APP_SETTINGS,
    }

    fun afterRequest(granted: Boolean, canAskAgain: Boolean): Outcome = when {
        granted -> Outcome.SHOW_SPEED
        canAskAgain -> Outcome.EXPLAIN
        else -> Outcome.OPEN_APP_SETTINGS
    }
}
