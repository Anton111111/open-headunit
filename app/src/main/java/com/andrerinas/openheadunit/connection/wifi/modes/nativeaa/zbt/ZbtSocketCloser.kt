package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import java.io.Closeable
import java.net.Socket

/**
 * A [Closeable] that closes [socket] by calling [Socket.close] directly.
 *
 * `java.net.Socket` implements `Closeable` only from API 19 (android-4.4_r1); on API 16–18 it is a
 * plain class, so handing a Socket to anything typed `Closeable` fails at run time there even though
 * it compiles against a newer android.jar. Every ZBT path that stores a socket as a close handle —
 * an opening's resource, a channel's `onClose` — goes through this adapter and never casts.
 */
internal fun socketCloser(socket: Socket): Closeable = object : Closeable {
    override fun close() {
        socket.close()
    }
}
