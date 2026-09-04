package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.database.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runtime config overrides driven by Profile Override switches.
 * Does not modify the profile file on disk — only the JSON passed to the core.
 *
 * Important: never inject fields unknown to sing-box (strict JSON decoding).
 */
object ConfigQuicOverride {

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
        val injected = JSONArray()
        if (Settings.excludeCnQuic) {
            // Allow CN UDP/443 first, then block other QUIC
            injected.put(
                JSONObject()
                    .put("network", "udp")
                    .put("port", 443)
                    .put("geoip", JSONArray().put("cn"))
                    .put("outbound", "direct"),
            )
        }
        injected.put(
            JSONObject()
                .put("network", "udp")
                .put("port", 443)
                .put("outbound", "block"),
        )
        val merged = JSONArray()
        for (i in 0 until injected.length()) merged.put(injected.get(i))
        for (i in 0 until oldRules.length()) merged.put(oldRules.get(i))
        route.put("rules", merged)
    }

    /** strict_route is a TUN inbound field, not under route. */
    private fun applyStrictRoute(root: JSONObject) {
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
        ensureBlockOutbound(root)
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val rules = route.optJSONArray("rules") ?: JSONArray()
        val v6 = JSONObject()
            .put("ip_version", 6)
            .put("outbound", "block")
        val merged = JSONArray().put(v6)
        for (i in 0 until rules.length()) merged.put(rules.get(i))
        route.put("rules", merged)
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
