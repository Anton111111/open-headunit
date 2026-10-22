package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.direct.GroupIdentityStability
import org.junit.Assert.*
import org.junit.Test

class WppCredentialStoreTest {
    private val network = NativeNetworkCredentials("DIRECT-AA", "12345678", "192.168.49.1", "02:11:22:33:44:55")

    @Test fun `replacement carries its own withdrawal generation and identity`() {
        val store = WppCredentialStore()
        store.publish(network)
        val old = store.current
        store.withdraw()
        store.publish(network.copy(identity = GroupIdentityStability.RENAMED))
        val fresh = store.current
        assertEquals(0L, old.withdrawals)
        assertEquals(GroupIdentityStability.UNPROVEN, old.credentials!!.identity)
        assertEquals(1L, fresh.withdrawals)
        assertEquals(GroupIdentityStability.RENAMED, fresh.credentials!!.identity)
    }

    @Test fun `withdrawal after selecting Type 3 remains visible to settling`() {
        val store = WppCredentialStore()
        store.publish(network)
        val sent = store.current
        store.withdraw()
        assertNull(store.current.credentials)
        assertNotEquals(sent.withdrawals, store.current.withdrawals)
        store.publish(network)
        assertNotEquals(sent.withdrawals, store.current.withdrawals)
    }

    @Test fun `ordinary redelivery does not withdraw the advertised network`() {
        val store = WppCredentialStore()
        store.publish(network)
        val sent = store.current
        store.publish(network.copy())
        assertEquals(sent, store.current)
    }
}
