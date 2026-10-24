package com.andrerinas.openheadunit.connection

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/** Re-resolves the targeted enumeration after teardown and open without holding lifecycle locks. */
internal interface UsbRecoveryAdmission : ConnectionAdmission {
    fun resolve(manager: UsbManager): UsbDevice?
}
