package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

import android.net.LocalSocket
import java.io.FilterInputStream
import java.io.InputStream

/** Physical shutdown shared by invalidation, watchdog, link close and final cleanup. */
internal object HctLocalSocket {
    // SequenceInputStream closes a source at EOF. Its socket source must wake both
    // directions before closing the descriptor, just like an explicit carrier shutdown.
    fun input(socket: LocalSocket): InputStream = object : FilterInputStream(socket.inputStream) {
        override fun close() { HctLocalSocket.close(socket) }
    }

    fun close(socket: LocalSocket) {
        // AOSP LocalSocketImpl uses recvmsg/Os.close rather than TCP's blocked-thread
        // interruption machinery. Closing a descriptor from another thread need not wake
        // an outstanding read; shutdown releases that read before we give up the descriptor.
        // A not-yet-connected or already-closed socket may reject shutdown: still close it.
        runCatching { socket.shutdownInput() }
        runCatching { socket.shutdownOutput() }
        runCatching { socket.close() }
    }
}
