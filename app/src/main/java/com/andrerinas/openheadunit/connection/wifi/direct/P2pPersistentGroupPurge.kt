package com.andrerinas.openheadunit.connection.wifi.direct

import android.annotation.SuppressLint
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import com.andrerinas.openheadunit.utils.AppLog
import java.lang.reflect.Proxy

/**
 * Deletes this unit's own stored P2P group profiles, the only way to rename the group below API 29.
 *
 * The two-argument createGroup reinvokes whatever profile the platform kept, so a fresh name and
 * passphrase are minted only once no profile of ours is left. Both calls are hidden API, reached by
 * reflection, and refused from Android 11 where the app names the group itself instead.
 */
object P2pPersistentGroupPurge {

    /** A listener that never answers must not hold the create up; the group matters more. */
    private const val ANSWER_TIMEOUT_MS = 3000L

    private const val LISTENER_CLASS = "android.net.wifi.p2p.WifiP2pManager\$PersistentGroupInfoListener"

    /**
     * Deletes every stored profile this unit owns, then reports a short verdict for the log.
     * [onDone] always runs exactly once, whatever the platform answered.
     */
    @SuppressLint("MissingPermission")
    fun purge(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        ownAddress: String?,
        handler: Handler,
        onDone: (verdict: String) -> Unit,
    ) {
        var finished = false
        val timeout = Runnable {
            if (!finished) {
                finished = true
                onDone("unavailable (no answer in ${ANSWER_TIMEOUT_MS / 1000}s)")
            }
        }
        val finish = { verdict: String ->
            if (!finished) {
                finished = true
                handler.removeCallbacks(timeout)
                onDone(verdict)
            }
        }

        try {
            val listenerClass = Class.forName(LISTENER_CLASS)
            val request = manager.javaClass.getMethod(
                "requestPersistentGroupInfo",
                WifiP2pManager.Channel::class.java,
                listenerClass,
            )
            val listener = Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { proxy, method, args ->
                when (method.name) {
                    "onPersistentGroupInfoAvailable" -> {
                        deleteOurs(manager, channel, ownAddress, args?.getOrNull(0), finish)
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "P2pPersistentGroupPurge"
                    else -> null
                }
            }
            handler.postDelayed(timeout, ANSWER_TIMEOUT_MS)
            request.invoke(manager, channel, listener)
        } catch (t: Throwable) {
            handler.removeCallbacks(timeout)
            finish("unavailable (${t.javaClass.simpleName}: ${t.message})")
        }
    }

    /** Never a profile we did not own: a phone's own saved group is not ours to forget. */
    @SuppressLint("MissingPermission")
    private fun deleteOurs(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        ownAddress: String?,
        groupList: Any?,
        finish: (String) -> Unit,
    ) {
        var unknownIds = 0
        val ours = try {
            val list = groupList?.javaClass?.getMethod("getGroupList")?.invoke(groupList) as? Collection<*>
            val ids = ArrayList<Int>()
            for (group in list.orEmpty()) {
                if (group !is WifiP2pGroup || !isOurs(group, ownAddress)) continue
                // Each deletion needs this owned profile's real ID; zero is a valid profile.
                // An unreadable entry must not suppress cleanup of other identifiable profiles.
                val netId = WifiDirectCompat.getGroupNetworkId(group)
                when {
                    netId == null -> unknownIds++
                    P2pGroupIdentityPolicy.isPersistentNetworkId(netId) -> ids.add(netId)
                }
            }
            ids
        } catch (t: Throwable) {
            finish("unavailable (${t.javaClass.simpleName}: ${t.message})")
            return
        }
        if (ours.isEmpty()) {
            finish(if (unknownIds > 0) "unavailable (network id missing for $unknownIds owned profiles)"
                else "no profile of ours")
            return
        }

        val delete = try {
            manager.javaClass.getMethod(
                "deletePersistentGroup",
                WifiP2pManager.Channel::class.java,
                Int::class.javaPrimitiveType,
                WifiP2pManager.ActionListener::class.java,
            )
        } catch (t: Throwable) {
            finish("unavailable (${t.javaClass.simpleName}: ${t.message})")
            return
        }

        var answered = 0
        var purged = 0
        var refusal: String? = null
        val report = {
            answered++
            if (answered == ours.size) {
                val verdict = if (purged > 0) "purged $purged of ${ours.size}" else "refused (${refusal ?: "unknown"})"
                finish(verdict + if (unknownIds > 0) "; network id unavailable for $unknownIds owned profiles" else "")
            }
        }
        for (netId in ours) {
            val listener = object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    purged++
                    report()
                }

                override fun onFailure(reason: Int) {
                    if (refusal == null) refusal = reasonName(reason)
                    report()
                }
            }
            try {
                delete.invoke(manager, channel, netId, listener)
            } catch (t: Throwable) {
                AppLog.d("P2pPersistentGroupPurge: deletePersistentGroup(netId=$netId) threw: ${t.message}")
                if (refusal == null) refusal = t.javaClass.simpleName
                report()
            }
        }
    }

    private fun isOurs(group: WifiP2pGroup, ownAddress: String?): Boolean {
        if (group.isGroupOwner) return true
        val owner = group.owner?.deviceAddress ?: return false
        return ownAddress != null && owner.equals(ownAddress, ignoreCase = true)
    }

    private fun reasonName(reason: Int): String = when (reason) {
        WifiP2pManager.ERROR -> "ERROR"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        WifiP2pManager.BUSY -> "BUSY"
        else -> "reason $reason"
    }
}
