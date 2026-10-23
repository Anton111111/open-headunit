package com.andrerinas.openheadunit.device

import android.os.Handler
import android.os.Looper
import com.adayo.mcucommproxy.IMcuCommCallback
import com.adayo.mcucommproxy.McuCommManager
import com.andrerinas.openheadunit.utils.AppLog
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal class AdayoMcuGearSource : AdayoGearSource {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Adayo-MCU-gear").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastGear = AtomicInteger(-1)
    @Volatile private var active = false
    private var subscribed = false
    private var listener: ((Int) -> Unit)? = null
    private var stopped = false

    private val callback = IMcuCommCallback { data, length ->
        val gear = try {
            AdayoGearPacket.decode(data, length.code)
        } catch (exception: IllegalArgumentException) {
            AppLog.w("Adayo MCU: ignoring invalid status callback: ${exception.message}")
            return@IMcuCommCallback
        }
        if (gear < 0 || lastGear.getAndSet(gear) == gear) return@IMcuCommCallback

        AppLog.i("Adayo MCU: gear=$gear")
        mainHandler.post {
            if (active) listener?.invoke(gear)
        }
    }

    override fun start(onGear: (Int) -> Unit) {
        check(!stopped) { "Stopped Adayo gear source cannot be restarted" }
        if (active) return
        active = true
        listener = onGear
        worker.execute {
            if (!active) return@execute
            if (!AdayoMcuAccess.connect()) {
                AppLog.w("Adayo MCU: unable to connect to MCU communication service")
                return@execute
            }
            subscribed = AdayoMcuAccess.register(callback)
            if (!subscribed) {
                AppLog.e("Adayo MCU: failed to register status callback")
                return@execute
            }
            AppLog.i("Adayo MCU: listening for gear status")
        }
    }

    override fun stop() {
        if (stopped) return
        active = false
        stopped = true
        listener = null
        worker.execute {
            try {
                if (subscribed) {
                    if (AdayoMcuAccess.unregister(callback)) {
                        subscribed = false
                        AppLog.i("Adayo MCU: status callback unregistered")
                    } else {
                        AppLog.e("Adayo MCU: failed to unregister status callback")
                    }
                }
            } finally {
                worker.shutdown()
            }
        }
    }

    companion object {
        fun canAccessMcu(): Boolean = AdayoMcuAccess.canAccess()
    }
}

internal object AdayoGearPacket {
    fun decode(data: ByteArray?, length: Int): Int {
        require(data != null && length >= 2 && length <= data.size) {
            "Invalid MCU callback buffer/length"
        }
        if ((data[0].toInt() and 0xff) != 0x83 || data[1].toInt() != 0) return -1
        require(length >= 7) { "Truncated 0x8300 status packet" }
        return data[6].toInt() and 0xff
    }
}

private object AdayoMcuAccess {
    private const val STATUS_GROUP: Byte = 3
    private val lock = Any()
    private val registeredCallbacks = mutableSetOf<IMcuCommCallback>()
    private var libraryLoaded = false

    fun canAccess(): Boolean = synchronized(lock) {
        if (registeredCallbacks.isNotEmpty()) return@synchronized true
        val probe = IMcuCommCallback { _, _ -> }
        if (!connectLocked() || !registerLocked(probe)) return@synchronized false
        val unregistered = unregisterLocked(probe)
        if (!unregistered) AppLog.e("Adayo MCU: capability probe could not unregister its callback")
        unregistered
    }

    fun connect(): Boolean = synchronized(lock) { connectLocked() }

    fun register(callback: IMcuCommCallback): Boolean = synchronized(lock) {
        registerLocked(callback)
    }

    fun unregister(callback: IMcuCommCallback): Boolean =
        synchronized(lock) { unregisterLocked(callback) }

    private fun connectLocked(): Boolean {
        return try {
            if (!libraryLoaded) {
                System.loadLibrary("mcucommproxy_jni")
                libraryLoaded = true
            }
            McuCommManager.connectMcuCommServer()
        } catch (exception: SecurityException) {
            AppLog.e("Adayo MCU: access denied while connecting", exception)
            false
        } catch (error: LinkageError) {
            AppLog.e("Adayo MCU: JNI library or entry point unavailable", error)
            false
        }
    }

    private fun registerLocked(callback: IMcuCommCallback): Boolean {
        return try {
            val registered = McuCommManager.registMcuCommCallback(callback, STATUS_GROUP)
            if (registered) registeredCallbacks.add(callback)
            registered
        } catch (exception: SecurityException) {
            AppLog.e("Adayo MCU: access denied while registering callback", exception)
            false
        } catch (error: LinkageError) {
            AppLog.e("Adayo MCU: callback registration entry point unavailable", error)
            false
        }
    }

    private fun unregisterLocked(callback: IMcuCommCallback): Boolean {
        return try {
            val unregistered = McuCommManager.unregistMcuCommCallback(callback, STATUS_GROUP)
            if (unregistered) registeredCallbacks.remove(callback)
            unregistered
        } catch (exception: SecurityException) {
            AppLog.e("Adayo MCU: access denied while unregistering callback", exception)
            false
        } catch (error: LinkageError) {
            AppLog.e("Adayo MCU: callback unregistration entry point unavailable", error)
            false
        }
    }
}
