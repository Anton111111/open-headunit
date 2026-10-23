package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import com.andrerinas.openheadunit.aap.protocol.proto.Wireless
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ExternalModuleCarrier
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.HandshakeLink
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.bluetooth.BluetoothPeerInventory
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.SequenceInputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/**
 * The HCT service accepts the phone's AA RFCOMM connection; goc_rfcom carries its bytes.
 * In the inspected AVIN A10 and related HCT QCM6125 A13 fscsdkbw123 binaries, FIFO13 carries
 * phone input to this socket, and FIFO14 carries socket output to aap_send_data_packet().
 * This route uses neither the module UART nor Zlink start/disable commands.
 *
 * This is a shared, single-client socket, not an ownership API. A10 queues behind an existing
 * client; A13 replaces it. Open only for one actual handoff after a fresh AA event, never as
 * discovery, and do not repeatedly reclaim it if another client takes it back. These firmware
 * contracts do not establish socket/SELinux access on every HCT build.
 */
@RequiresApi(21)
internal class HctAaCarrier(
    private val context: Context,
    private val openingRequest: () -> Wireless.WifiVersionRequest,
    private val serve: suspend (HandshakeLink) -> Unit,
    private val mayServe: () -> Boolean,
    private val acceptPeer: (String) -> Boolean,
    private val onPhoneEvidence: () -> Unit,
    private val onUnavailable: (HctWakePolicy.Result?) -> Unit
) : ExternalModuleCarrier {
    @Volatile private var stopped = false
    private val active = AtomicReference<LocalSocket?>()
    private val wakes = Channel<String>(Channel.CONFLATED)
    @Volatile private var selectedMac: String? = null
    @Volatile private var activePeer: String? = null
    @Volatile private var observer: HctServiceObserver? = null

    // Automatic credential refresh must not repeatedly reconnect a manufacturer's HFP profile.
    override val sendsWake = false
    override fun requestWake(userAsked: Boolean) {
        AppLog.i("NativeAA: [HCT] waiting for a fresh manufacturer AA connection")
    }

    fun requestWakeFor(mac: String) {
        if (!stopped) {
            selectedMac = BluetoothPeerInventory.normalizeAddress(mac)
                ?: return
            if (activePeer != null && activePeer != selectedMac) closeSocket()
            wakes.trySend(selectedMac ?: return)
        }
    }

    suspend fun run() = coroutineScope {
        val status = HctServiceObserver(context) { closeSocket() }
        observer = status
        try {
            withContext(Dispatchers.Main.immediate) { if (!stopped) status.start() }
            if (stopped) return@coroutineScope
            withTimeout(3_000L) { status.awaitService() }
            val wakeJob = launch(Dispatchers.IO) {
                for (mac in wakes) {
                    if (stopped || !mayServe()) continue
                    val result = status.requestWake(mac) { mayServe() && selectedMac == mac }
                    AppLog.i("NativeAA: [HCT] selected-phone wake: $result")
                    if (result != HctWakePolicy.Result.REQUESTED) onUnavailable(result)
                }
            }
            try {
                AppLog.i("NativeAA: [HCT] observing AA reports; no local socket opened yet")
                for (ticket in status.arrivals) {
                    if (stopped || !isActive) break
                    // A selection dialog can briefly hold the handoff. Retain the live event
                    // within its original deadline instead of losing it on the first poll.
                    while (!stopped && isActive && !mayServe() &&
                        status.admission.fresh(ticket, SystemClock.elapsedRealtime())) delay(50)
                    if (stopped || !mayServe() || !status.admission.fresh(ticket, SystemClock.elapsedRealtime())) continue
                    try {
                        serveOnce(status, ticket)
                    } catch (error: CancellationException) {
                        if (stopped || !isActive) throw error
                        onUnavailable(null)
                    } catch (error: Exception) {
                        AppLog.w("NativeAA: [HCT] handoff stopped: ${error.message}; no automatic socket retry")
                        onUnavailable(null)
                    } finally {
                        closeSocket()
                    }
                }
            } finally {
                wakeJob.cancel()
                wakes.close()
            }
        } finally {
            closeSocket()
            withContext(NonCancellable + Dispatchers.Main.immediate) { status.close() }
            observer = null
        }
    }

    private suspend fun serveOnce(status: HctServiceObserver, ticket: HctAdmission.Ticket) = coroutineScope {
        val mac = status.phone(ticket)
        if (!acceptPeer(mac)) throw IOException("HCT: phone was not selected for this handoff")
        activePeer = mac
        status.watchAddress(mac)
        val socket = LocalSocket()
        try {
            // Create the descriptor before publishing it so stop/invalidation can close it.
            socket.inputStream
            active.set(socket)
            checkCurrent(status, ticket, mac)
            if (!status.admission.fresh(ticket, SystemClock.elapsedRealtime())) {
                throw IOException("HCT: AA event expired before socket handoff")
            }
            // AOSP LocalSocket's connect(timeout) is not implemented. Nonblocking connect
            // fails immediately on a full A10 backlog instead of occupying an IO worker.
            val fd = socket.fileDescriptor
            val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
            Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
            socket.connect(LocalSocketAddress("goc_rfcom", LocalSocketAddress.Namespace.RESERVED))
            Os.fcntlInt(fd, OsConstants.F_SETFL, flags)
            if (socket.peerCredentials.uid !in 0 until android.os.Process.FIRST_APPLICATION_UID) {
                throw IOException("HCT: local AA socket is not owned by a system daemon")
            }
            // SO_TIMEOUT bounds reads only. A separate dispatcher must close a blocked write
            // too; cancellation of the coroutine doing blocking stream IO alone is insufficient.
            val watchdog = launch(Dispatchers.Default) {
                delay(OPENING_TIMEOUT_MS)
                runCatching { socket.close() }
            }
            val opening = try {
                checkCurrent(status, ticket, mac)
                if (status.phone(ticket) != mac) throw IOException("HCT: peer changed before handoff")
                val ack = status.expectAddressAck()
                // The daemon treats its first read as a twelve-byte MAC, with no length prefix.
                // Keep this write separate. SPC2 is only a consumption hint (also used by iAP),
                // so WPP must still be validated before delivering Wi-Fi credentials.
                socket.outputStream.write(mac.replace(":", "").toByteArray(Charsets.US_ASCII))
                socket.outputStream.flush()
                withTimeout(1_000L) { ack.await() }
                checkCurrent(status, ticket, mac)
                HctOpening.exchange(
                    socket.inputStream, socket.outputStream, openingRequest(),
                    SystemClock.elapsedRealtime() + 3_000L,
                    SystemClock::elapsedRealtime, socket::setSoTimeout
                ).also {
                    checkCurrent(status, ticket, mac)
                    if (status.phone(ticket) != mac) throw IOException("HCT: peer changed during opening")
                }
            } finally {
                watchdog.cancel()
            }
            checkCurrent(status, ticket, mac)
            socket.soTimeout = 0
            onPhoneEvidence()
            AppLog.i("NativeAA: [HCT] WPP 1.7 accepted; continuing Wi-Fi handoff")
            val link = object : HandshakeLink {
                override val input = SequenceInputStream(ByteArrayInputStream(opening), socket.inputStream)
                override val output = socket.outputStream
                override val peerName: String? = null
                override val peerAddress = mac
                override val radioLabel = "HCT local AA"
                override val persistPeerForAutoStart = false
                override val peerReportedPresent = true
                override val retransmitsWhileSilent = false
                override val versionRequestAlreadySent = true
                override fun close() { socket.close() }
            }
            serve(link)
        } finally {
            activePeer = null
            active.compareAndSet(socket, null)
            runCatching { socket.close() }
        }
    }

    private fun checkCurrent(status: HctServiceObserver, ticket: HctAdmission.Ticket, mac: String) {
        if (stopped || !mayServe() || !status.admission.current(ticket) ||
            !acceptPeer(mac)) {
            throw IOException("HCT: AA handoff is no longer current")
        }
    }

    private fun closeSocket() { active.getAndSet(null)?.let { runCatching { it.close() } } }

    override fun close() {
        stopped = true
        closeSocket()
        wakes.close()
        observer?.close()
    }

    companion object { private const val OPENING_TIMEOUT_MS = 5_000L }
}
