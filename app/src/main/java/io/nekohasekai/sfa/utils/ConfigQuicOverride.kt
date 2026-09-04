package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.database.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runtime config override: inject QUIC block rules when Settings.disableQuic is on.
 * Does not modify the profile file on disk — only the JSON passed to the core.
 */
object ConfigQuicOverride {
    private const val MARKER = "_chainbox_quic"

    fun apply(content: String): String {
        if (!Settings.disableQuic) return content
        return try {
            val root = JSONObject(content)
            ensureBlockOutbound(root)
            val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
            val oldRules = route.optJSONArray("rules") ?: JSONArray()
            val newRules = JSONArray()

            // drop previous injected rules if any (idempotent)
            for (i in 0 until oldRules.length()) {
                val r = oldRules.optJSONObject(i) ?: continue
                if (r.optString(MARKER).isNotEmpty()) continue
                newRules.put(r)
            }

            val injected = JSONArray()
            if (Settings.excludeCnQuic) {
                injected.put(
                    JSONObject()
                        .put("network", "udp")
                        .put("port", 443)
                        .put("geoip", JSONArray().put("cn"))
                        .put("outbound", "direct")
                        .put(MARKER, "cn-direct"),
                )
            }
            injected.put(
                JSONObject()
                    .put("network", "udp")
                    .put("port", 443)
                    .put("outbound", "block")
                    .put(MARKER, "block"),
            )

            val merged = JSONArray()
            for (i in 0 until injected.length()) merged.put(injected.get(i))
            for (i in 0 until newRules.length()) merged.put(newRules.get(i))
            route.put("rules", merged)
            root.toString()
        } catch (_: Exception) {
            content
        }
    }

    private fun ensureBlockOutbound(root: JSONObject) {
        val outs = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (o.optString("type") == "block" || o.optString("tag") == "block") return
        }
        outs.put(JSONObject().put("type", "block").put("tag", "block"))
    }
}
