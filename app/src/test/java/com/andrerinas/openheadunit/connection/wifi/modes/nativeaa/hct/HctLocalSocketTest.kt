package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

import android.net.LocalSocket
import java.io.IOException
import java.io.ByteArrayInputStream
import java.io.SequenceInputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.*

class HctLocalSocketTest {
    @Test fun `replay stream EOF shuts down both socket directions before closing`() {
        val socket = mock(LocalSocket::class.java)
        `when`(socket.inputStream).thenReturn(ByteArrayInputStream(byteArrayOf(2)))
        val input = SequenceInputStream(ByteArrayInputStream(byteArrayOf(1)), HctLocalSocket.input(socket))
        assertEquals(1, input.read())
        assertEquals(2, input.read())
        verify(socket, never()).close()
        assertEquals(-1, input.read())
        inOrder(socket).apply {
            verify(socket).shutdownInput()
            verify(socket).shutdownOutput()
            verify(socket).close()
        }
    }

    @Test fun `shutdown precedes descriptor close so blocked readers and writers can exit`() {
        val socket = mock(LocalSocket::class.java)
        HctLocalSocket.close(socket)
        inOrder(socket).apply {
            verify(socket).shutdownInput()
            verify(socket).shutdownOutput()
            verify(socket).close()
        }
    }

    @Test fun `failed shutdown does not leak an unconnected descriptor`() {
        val socket = mock(LocalSocket::class.java)
        doThrow(IOException("not connected")).`when`(socket).shutdownInput()
        doThrow(IOException("not connected")).`when`(socket).shutdownOutput()
        HctLocalSocket.close(socket)
        verify(socket).close()
    }
}
