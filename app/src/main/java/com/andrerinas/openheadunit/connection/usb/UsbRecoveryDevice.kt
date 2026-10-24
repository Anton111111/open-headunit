package com.andrerinas.openheadunit.connection.usb

import android.hardware.usb.UsbDevice
import android.os.Build

/** Captured while the handshake still owns USB permission, before failure delivery to Main. */
data class UsbRecoveryDevice(val path: String, val vendor: Int, val product: Int,
    val manufacturer: String?, val model: String?, val serial: String?) {
    /** Previously known values cannot silently change while permission/open is pending. */
    fun agreesWith(next: UsbRecoveryDevice): Boolean = path == next.path && vendor == next.vendor &&
        product == next.product && (manufacturer == null || manufacturer == next.manufacturer) &&
        (model == null || model == next.model) && (serial == null || serial == next.serial)

    /** Missing permission can hide a value; it is not evidence of a different device. */
    fun conflictsWith(next: UsbRecoveryDevice): Boolean = path != next.path || vendor != next.vendor ||
        product != next.product ||
        (manufacturer != null && next.manufacturer != null && manufacturer != next.manufacturer) ||
        (model != null && next.model != null && model != next.model) ||
        (serial != null && next.serial != null && serial != next.serial)

    companion object {
        fun capture(device: UsbDevice): UsbRecoveryDevice {
            // Android 10+ can withhold the serial without permission. Unknown is not an
            // identity match; the resolver needs other known fields or a later permission check.
            fun text(read: () -> String?): String? = try { read()?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
            val stringsAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
            return UsbRecoveryDevice(device.deviceName, device.vendorId, device.productId,
                if (stringsAvailable) text { device.manufacturerName } else null,
                if (stringsAvailable) text { device.productName } else null,
                if (stringsAvailable) text { device.serialNumber } else null)
        }
    }
}
