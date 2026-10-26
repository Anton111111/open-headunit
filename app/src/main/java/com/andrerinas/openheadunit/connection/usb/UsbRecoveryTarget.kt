package com.andrerinas.openheadunit.connection.usb

import com.andrerinas.openheadunit.connection.ConnectionAdmission

/** Identifies a recovery candidate without treating an AOA VID/PID as a physical identity. */
internal class UsbRecoveryTarget(val original: UsbRecoveryDevice, before: List<UsbRecoveryDevice>,
    relatedPaths: Set<String> = emptySet()) {
    // New permission can reveal more fields, but must never erase or replace known evidence.
    private var expected = original
    private val otherPaths = before.filter { it.path != original.path }.map { it.path }.toSet()
    // Duplicate descriptors/serials exist on real dongles. Their disappearance later does not
    // make an originally ambiguous identity trustworthy.
    @Volatile private var ambiguous = before.count { compatible(it) } != 1
    // A second Android accessory can re-enumerate too. Remember its descriptors as well as
    // its old address; disappearance must not turn its new address into our sole candidate.
    // Include connectable normal-mode phones: AOA can replace all of their descriptors.
    private val companions = before.filter { it.path != original.path &&
        (it.path in relatedPaths || UsbDeviceIdentityPolicy.isInAccessoryMode(it.vendor, it.product) ||
            (original.manufacturer != null && original.model != null &&
                it.manufacturer == original.manufacturer && it.model == original.model)) }

    @Synchronized fun observe(devices: List<UsbRecoveryDevice>) {
        if (devices.count { compatible(it) } > 1 || companions.any { previous ->
                devices.none { previous.agreesWith(it) }
            }) ambiguous = true
    }

    private val hasIdentity = original.serial != null ||
        (original.manufacturer != null && original.model != null)
    val canFollowEnumeration: Boolean get() = !ambiguous && hasIdentity
    val canRetryUnchangedWithoutIdentity: Boolean get() = !ambiguous && !hasIdentity

    @Synchronized fun noteDetach(path: String, vendor: Int, product: Int) {
        if (companions.any { it.path == path && it.vendor == vendor && it.product == product }) ambiguous = true
    }

    /**
     * A detach callback can hold a previous observer while a new candidate is installed.
     * Its shared identity invalidation must still precede that candidate's publication.
     * Lock order: lifecycle -> target identity -> candidate observation -> arbiter.
     */
    fun guard(candidate: ConnectionAdmission): ConnectionAdmission = ConnectionAdmission { action ->
        synchronized(this) { !ambiguous && candidate.run(action) }
    }

    @Synchronized fun retainKnownFields(candidate: UsbRecoveryDevice) {
        if (!compatible(candidate)) return
        expected = expected.copy(manufacturer = expected.manufacturer ?: candidate.manufacturer,
            model = expected.model ?: candidate.model, serial = expected.serial ?: candidate.serial)
    }

    private fun compatible(device: UsbRecoveryDevice): Boolean =
        expected.vendor == device.vendor &&
            (expected.product == device.product ||
                (UsbDeviceIdentityPolicy.isInAccessoryMode(expected.vendor, expected.product) &&
                    UsbDeviceIdentityPolicy.isInAccessoryMode(device.vendor, device.product))) &&
            (expected.manufacturer == null || device.manufacturer == expected.manufacturer) &&
            (expected.model == null || device.model == expected.model) &&
            (expected.serial == null || device.serial == null || device.serial == expected.serial)

    /** A missing serial may only be provisional: Android 10+ can withhold it until permission. */
    @Synchronized fun select(devices: List<UsbRecoveryDevice>, permissionGranted: (String) -> Boolean): UsbRecoveryDevice? {
        observe(devices)
        if (ambiguous) return null
        val matches = devices.filter { compatible(it) }
        val candidate = matches.singleOrNull() ?: return null
        if (candidate.path in otherPaths) return null
        if (candidate.path != expected.path && devices.any { it.path == expected.path }) return null
        if (expected.serial != null) {
            if (candidate.serial == expected.serial) return candidate
            // Ask only this unambiguous descriptor match for permission, then verify the serial.
            if (!permissionGranted(candidate.path)) return candidate
            return null
        }
        // Without serial evidence, require both strings and a unique descriptor match. This
        // supports fixed/missing-serial dongles, not an assertion of hardware uniqueness.
        if (expected.manufacturer == null || expected.model == null) return null
        return candidate
    }
}
