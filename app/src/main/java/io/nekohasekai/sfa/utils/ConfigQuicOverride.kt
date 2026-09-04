package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.database.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runtime config overrides driven by Profile Override switches.
 * Does not modify the profile file on disk — only the JSON passed to the core.
 */
object ConfigQuicOverride {
    private const val QUIC_MARKER = "_chainbox_quic"

    fun apply(content: String): String {
        if (!Settings.disableQuic && !Settings.strictRoute && !Settings.dnsProtect && !Settings.disableIpv6) {
            return content
        }
        return try {
            val root = JSONObject(content)
            if (Settings.disableQuic) applyQuic(root)
            if (Settings.strictRoute) applyStrictRoute(root)
            if (Settings.dnsProtect) applyDnsProtect(root)
            if (Settings.disableIpv6) applyDisableIpv6(root)
            root.toString()
        } catch (_: Exception) {
            content
        }
    }

    private fun applyQuic(root: JSONObject) {
        ensureBlockOutbound(root)
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val oldRules = route.optJSONArray("rules") ?: JSONArray()
        val kept = JSONArray()
        for (i in 0 until oldRules.length()) {
            val r = oldRules.optJSONObject(i) ?: continue
            if (r.optString(QUIC_MARKER).isNotEmpty()) continue
            kept.put(r)
        }
        val injected = JSONArray()
        if (Settings.excludeCnQuic) {
            injected.put(
                JSONObject()
                    .put("network", "udp")
                    .put("port", 443)
                    .put("geoip", JSONArray().put("cn"))
                    .put("outbound", "direct")
                    .put(QUIC_MARKER, "cn-direct"),
            )
        }
        injected.put(
            JSONObject()
                .put("network", "udp")
                .put("port", 443)
                .put("outbound", "block")
                .put(QUIC_MARKER, "block"),
        )
        val merged = JSONArray()
        for (i in 0 until injected.length()) merged.put(injected.get(i))
        for (i in 0 until kept.length()) merged.put(kept.get(i))
        route.put("rules", merged)
    }

    private fun applyStrictRoute(root: JSONObject) {
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        route.put("strict_route", true)
        val inbounds = root.optJSONArray("inbounds") ?: return
        for (i in 0 until inbounds.length()) {
            val ib = inbounds.optJSONObject(i) ?: continue
            if (ib.optString("type") != "tun") continue
            ib.put("strict_route", true)
            if (!ib.has("auto_route")) ib.put("auto_route", true)
        }
    }

    private fun applyDnsProtect(root: JSONObject) {
        val dns = root.optJSONObject("dns") ?: JSONObject().also { root.put("dns", it) }
        if (!dns.has("independent_cache")) {
            dns.put("independent_cache", true)
        }
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        if (!route.has("auto_detect_interface")) {
            route.put("auto_detect_interface", true)
        }
    }

    private fun applyDisableIpv6(root: JSONObject) {
        val dns = root.optJSONObject("dns") ?: JSONObject().also { root.put("dns", it) }
        dns.put("strategy", "ipv4_only")
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val rules = route.optJSONArray("rules") ?: JSONArray().also { route.put("rules", it) }
        var hasV6Block = false
        for (i in 0 until rules.length()) {
            val r = rules.optJSONObject(i) ?: continue
            if (r.optString("_chainbox_v6") == "block") {
                hasV6Block = true
                break
            }
        }
        if (!hasV6Block) {
            ensureBlockOutbound(root)
            val v6 = JSONObject()
                .put("ip_version", 6)
                .put("outbound", "block")
                .put("_chainbox_v6", "block")
            val merged = JSONArray().put(v6)
            for (i in 0 until rules.length()) merged.put(rules.get(i))
            route.put("rules", merged)
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
