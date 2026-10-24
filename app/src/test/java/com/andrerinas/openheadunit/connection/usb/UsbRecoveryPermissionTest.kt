package com.andrerinas.openheadunit.connection.usb

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import com.andrerinas.openheadunit.utils.AppLog
import org.junit.Test
import org.mockito.Mockito.*

class UsbRecoveryPermissionTest {
    @Test fun recoveryResultsNeverFallThroughToTheOrdinaryPermissionHandler() {
        val old = AppLog.LOGGER
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) = Unit
        }
        try {
            val listener = mock(UsbReceiver.Listener::class.java)
            val device = mock(UsbDevice::class.java)
            val intent = mock(Intent::class.java)
            val data = mock(Uri::class.java)
            `when`(intent.action).thenReturn(UsbReceiver.ACTION_USB_DEVICE_PERMISSION)
            `when`(intent.data).thenReturn(data)
            `when`(data.scheme).thenReturn("ohu-usb-recovery")
            `when`(data.schemeSpecificPart).thenReturn("expired-token")
            @Suppress("DEPRECATION")
            `when`(intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)).thenReturn(device)
            `when`(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)).thenReturn(true)
            UsbReceiver(listener).onReceive(mock(Context::class.java), intent)
            verify(listener).onUsbRecoveryPermission("expired-token", true, device)
            verifyNoMoreInteractions(listener)
        } finally { AppLog.LOGGER = old }
    }
}
