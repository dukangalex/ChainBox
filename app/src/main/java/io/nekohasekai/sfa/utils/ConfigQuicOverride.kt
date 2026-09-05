package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.database.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runtime config overrides: normalize → chain reapply → network switches.
 * Does not modify profile files on disk.
 */
object ConfigQuicOverride {

    suspend fun apply(content: String): String {
        var out = content
        try {
            if (Settings.configNormalize) {
                out = ConfigNormalize.apply(out)
            }
            if (Settings.chainEnabled) {
                out = ConfigChainReapply.apply(out)
            }
            if (Settings.disableQuic || Settings.strictRoute || Settings.dnsProtect || Settings.disableIpv6) {
                val root = JSONObject(out)
                if (Settings.disableQuic) applyQuic(root)
                if (Settings.strictRoute) applyStrictRoute(root)
                if (Settings.dnsProtect) applyDnsProtect(root)
                if (Settings.disableIpv6) applyDisableIpv6(root)
                out = root.toString()
            }
        } catch (_: Exception) {
            return content
        }
        return out
    }

    private fun applyQuic(root: JSONObject) {
        ensureBlockOutbound(root)
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val oldRules = route.optJSONArray("rules") ?: JSONArray()
        val injected = JSONArray()
        if (Settings.excludeCnQuic) {
            // Never use deprecated "geoip" field (removed in sing-box 1.12).
            // Prefer existing rule_set tags; otherwise ensure a geoip-cn rule_set.
            val cnTag = ensureGeoipCnRuleSet(route)
            if (cnTag != null) {
                injected.put(
                    JSONObject()
                        .put("network", "udp")
                        .put("port", 443)
                        .put("rule_set", JSONArray().put(cnTag))
                        .put("outbound", "direct"),
                )
            }
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

    /**
     * Find or create a rule_set tag usable for China IP matching.
     * Returns null only if we cannot attach any CN matcher (should be rare).
     */
    private fun ensureGeoipCnRuleSet(route: JSONObject): String? {
        val sets = route.optJSONArray("rule_set") ?: JSONArray().also { route.put("rule_set", it) }
        // Prefer an existing tag that looks like geoip-cn
        for (i in 0 until sets.length()) {
            val s = sets.optJSONObject(i) ?: continue
            val tag = s.optString("tag").trim()
            val t = tag.lowercase()
            if (t == "geoip-cn" || t == "geoip:cn" || t.contains("geoip-cn") ||
                (t.contains("geoip") && t.endsWith("cn"))
            ) {
                return tag
            }
        }
        // Create a standard remote rule_set (binary) used by most modern configs
        val tag = "geoip-cn"
        sets.put(
            JSONObject()
                .put("type", "remote")
                .put("tag", tag)
                .put("format", "binary")
                .put(
                    "url",
                    "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-cn.srs",
                )
                .put("download_detour", "direct")
                .put("update_interval", "1d"),
        )
        return tag
    }

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
