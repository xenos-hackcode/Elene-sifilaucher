package com.example.scifilauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class VpnProfile(val name: String, val configText: String)

private const val PREFS = "vpn_client_prefs"
private const val KEY_PROFILES = "profiles"
private const val KEY_ACTIVE = "active_profile_name"

/** Real WireGuard config profiles the user pastes in themselves from a provider they actually
 * trust (their own VPS, ProtonVPN's free tier, etc.) - deliberately NOT an auto-discovered list
 * of "free servers", which would mean routing all traffic through an unaccountable third party.
 * See combination.md/VpnClientActivity's own doc for the full reasoning. */
object VpnConfigStore {
    fun loadAll(context: Context): List<VpnProfile> {
        val raw = prefs(context).getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                VpnProfile(o.getString("name"), o.getString("config"))
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, profile: VpnProfile) {
        val list = loadAll(context).filterNot { it.name == profile.name }.toMutableList()
        list.add(0, profile)
        persist(context, list)
    }

    fun delete(context: Context, name: String) {
        persist(context, loadAll(context).filterNot { it.name == name })
        if (activeProfileName(context) == name) setActiveProfileName(context, null)
    }

    fun activeProfileName(context: Context): String? = prefs(context).getString(KEY_ACTIVE, null)

    fun setActiveProfileName(context: Context, name: String?) {
        prefs(context).edit().putString(KEY_ACTIVE, name).apply()
    }

    private fun persist(context: Context, list: List<VpnProfile>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("name", p.name)
                put("config", p.configText)
            })
        }
        prefs(context).edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
