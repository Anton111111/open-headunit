package com.andrerinas.openheadunit.aap

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.PermissionChecker
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.AppComponent
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.self.SelfLauncherManager
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherManager
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.*

/** Exercises the actual Service decision, rather than preselecting a mocked claim result. */
class MicrophoneForegroundClaimTest {
    @Test fun `service refresh preserves capture ownership but stop prevents late reclaim`() {
        val sdk = Build.VERSION::class.java.getDeclaredField("SDK_INT").apply { isAccessible = true }
        val oldSdk = sdk.getInt(null)
        // SDK_INT is final in the host Android stub; restore its original value after this test.
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(unsafe, sdk)
        val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java).invoke(unsafe, sdk)
        val writeInt = unsafeClass.getMethod("putIntVolatile", Any::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        fun setSdk(value: Int) { writeInt.invoke(unsafe, base, offset, value) }
        val level = AppLog::class.java.getDeclaredField("cachedLogLevel").apply { isAccessible = true }
        val oldLevel = level.getInt(null)
        val resources = mutableListOf<AutoCloseable>()
        try {
            setSdk(34)
            level.setInt(null, Int.MAX_VALUE)
            resources.add(Mockito.mockStatic(SystemClock::class.java))
            val permission = Mockito.mockStatic(PermissionChecker::class.java).also { resources.add(it) }
            var allowed = true
            permission.`when`<Int> { PermissionChecker.checkSelfPermission(any(), eq(Manifest.permission.RECORD_AUDIO)) }
                .thenAnswer { if (allowed) PermissionChecker.PERMISSION_GRANTED else PermissionChecker.PERMISSION_DENIED }
            resources.add(Mockito.mockStatic(PendingIntent::class.java))
            resources.add(Mockito.mockConstruction(Intent::class.java, Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)))
            val notification = mock<Notification>()
            resources.add(Mockito.mockConstruction(NotificationCompat.Builder::class.java,
                Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)) { builder, _ ->
                whenever(builder.build()).thenReturn(notification)
            })
            val settings = mock<Settings>()
            whenever(settings.useHeadUnitMicrophone).thenReturn(true)
            val component = mock<AppComponent>()
            val commManager = mock<CommManager>()
            whenever(component.commManager).thenReturn(commManager)
            val app = mock<App>()
            App::class.java.getDeclaredField("component\$delegate").apply { isAccessible = true }
                .set(app, lazyOf(component))
            val service = mock<AapService>()
            whenever(service.applicationContext).thenReturn(app)
            whenever(service.getString(any())).thenReturn("Microphone test")
            AapService::class.java.getDeclaredField("settings\$delegate").apply { isAccessible = true }
                .set(service, lazyOf(settings))
            val promote = AapService::class.java.getDeclaredMethod("promoteForMicrophone").apply { isAccessible = true }
            fun claim() = promote.invoke(service) as Boolean
            val command = mock<Intent>()
            whenever(command.action).thenReturn(AapService.ACTION_CONNECT_SOCKET)
            doCallRealMethod().whenever(service).onStartCommand(anyOrNull(), any(), any())
            fun refresh() {
                assertEquals(android.app.Service.START_STICKY, service.onStartCommand(command, 0, 1))
            }
            val demote = AapService::class.java.getDeclaredMethod("demoteAfterMicrophone").apply { isAccessible = true }
            val baseMask = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            val capturing = baseMask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

            setSdk(28)
            refresh()
            assertTrue(claim())
            demote.invoke(service)
            verify(service).startForeground(eq(1), same(notification))
            verify(service, never()).startForeground(any(), any(), any())

            for (sdkInt in listOf(29, 30, 34, 35, 36)) {
                setSdk(sdkInt)
                clearInvocations(service)
                refresh()
                verify(service).startForeground(eq(1), same(notification), eq(baseMask))

                for (deniedBySetting in listOf(true, false)) {
                    clearInvocations(service)
                    allowed = deniedBySetting
                    whenever(settings.useHeadUnitMicrophone).thenReturn(!deniedBySetting)
                    assertFalse(claim())
                    verify(service, never()).startForeground(any(), any(), any())

                    allowed = true
                    whenever(settings.useHeadUnitMicrophone).thenReturn(true)
                    assertTrue(claim())
                    verify(service).startForeground(eq(1), same(notification), eq(capturing))

                    // A setting/permission change does not transfer the capture worker's ownership
                    // to an unrelated service command; its release remains the closing boundary.
                    clearInvocations(service)
                    allowed = false
                    whenever(settings.useHeadUnitMicrophone).thenReturn(false)
                    refresh()
                    verify(service).startForeground(eq(1), same(notification), eq(capturing))

                    clearInvocations(service)
                    demote.invoke(service)
                    refresh()
                    verify(service, times(2)).startForeground(eq(1), same(notification), eq(baseMask))
                }

                allowed = true
                whenever(settings.useHeadUnitMicrophone).thenReturn(true)
                doThrow(SecurityException("background microphone")).whenever(service)
                    .startForeground(eq(1), same(notification), eq(capturing))
                assertFalse(claim())
                clearInvocations(service)
                refresh()
                verify(service).startForeground(eq(1), same(notification), eq(baseMask))
                doNothing().whenever(service).startForeground(eq(1), same(notification), eq(capturing))
            }

            setSdk(34)
            assertTrue(claim())
            whenever(commManager.connectionState).thenReturn(
                MutableStateFlow<CommManager.ConnectionState>(CommManager.ConnectionState.Disconnected()))
            AapService::class.java.getDeclaredField("wifiLauncherManager").apply { isAccessible = true }
                .set(service, mock<WifiLauncherManager>())
            AapService::class.java.getDeclaredField("selfLauncherManager").apply { isAccessible = true }
                .set(service, mock<SelfLauncherManager>())
            val oldUserStop = AapService.destroyIsUserStop
            try {
                whenever(command.action).thenReturn(AapService.ACTION_STOP_SERVICE)
                assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(command, 0, 2))
                verify(service).stopForeground(true)
                verify(service).stopSelf()
                clearInvocations(service)

                // Recorder cleanup runs on its lifecycle FIFO after the service removes its
                // notification. Releasing that capture must not put the stopped service back.
                demote.invoke(service)
                verify(service, never()).startForeground(any(), any(), any())
                assertFalse(claim())
                whenever(command.action).thenReturn(AapService.ACTION_CONNECT_SOCKET)
                refresh()
                verify(service, never()).startForeground(any(), any(), any())
            } finally {
                AapService.destroyIsUserStop = oldUserStop
            }
        } finally {
            resources.asReversed().forEach { it.close() }
            setSdk(oldSdk)
            level.setInt(null, oldLevel)
        }
    }
}
