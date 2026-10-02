package com.example.scifilauncher

import android.content.Context
import android.util.Log
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.io.BufferedReader
import java.io.StringReader

private const val TAG = "WireGuardVpn"

/** Thin wrapper around the official WireGuard-android library's GoBackend - it owns the real
 * VpnService lifecycle internally (see GoBackend$VpnService in the manifest), so this class
 * never has to touch VpnService.Builder/onDestroy/etc. itself, sidestepping the exact class of
 * "stopService() doesn't actually tear it down" bug TrackerBlockVpnService had. */
object WireGuardVpnManager {
    private var backend: GoBackend? = null
    private var currentTunnel: SimpleTunnel? = null

    private class SimpleTunnel(private val tunnelName: String) : Tunnel {
        override fun getName(): String = tunnelName
        override fun onStateChange(newState: Tunnel.State) {
            Log.d(TAG, "Tunnel '$tunnelName' state changed to $newState")
        }
    }

    private fun backend(context: Context): GoBackend {
        val existing = backend
        if (existing != null) return existing
        val created = GoBackend(context.applicationContext)
        backend = created
        return created
    }

    fun currentState(context: Context): Tunnel.State {
        val tunnel = currentTunnel ?: return Tunnel.State.DOWN
        return runCatching { backend(context).getState(tunnel) }.getOrDefault(Tunnel.State.DOWN)
    }

    fun activeTunnelName(): String? = currentTunnel?.name

    /** Parses [configText] (standard WireGuard .conf format - [Interface]/[Peer] sections) and
     * brings the tunnel up. Any previously active tunnel is torn down first - only one VPN
     * connection makes sense at a time. Returns a human-readable error on failure, null on
     * success. */
    fun connect(context: Context, profileName: String, configText: String): String? {
        val config = runCatching {
            Config.parse(BufferedReader(StringReader(configText)))
        }.getOrElse { e ->
            Log.e(TAG, "Config.parse failed", e)
            return "Couldn't parse that config: ${e.message ?: "invalid format"}"
        }

        disconnect(context)

        val tunnel = SimpleTunnel(profileName)
        return runCatching {
            backend(context).setState(tunnel, Tunnel.State.UP, config)
            currentTunnel = tunnel
            null
        }.getOrElse { e ->
            Log.e(TAG, "setState(UP) failed", e)
            "Couldn't connect: ${e.message ?: "unknown error"}"
        }
    }

    fun disconnect(context: Context) {
        val tunnel = currentTunnel ?: return
        runCatching { backend(context).setState(tunnel, Tunnel.State.DOWN, null) }
            .onFailure { Log.e(TAG, "setState(DOWN) failed", it) }
        currentTunnel = null
    }
}
