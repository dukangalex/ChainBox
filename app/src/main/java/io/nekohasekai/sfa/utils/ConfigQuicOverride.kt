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
        // WebRTC/STUN often goes "direct" via CN rules; block discovery ports too
        injected.put(
            JSONObject()
                .put("network", "udp")
                .put("port", JSONArray().put(3478).put(19302).put(5349))
                .put("outbound", "block"),
        )
        val merged = JSONArray()
        for (i in 0 until injected.length()) merged.put(injected.get(i))
        for (i in 0 until oldRules.length()) merged.put(oldRules.get(i))
        route.put("rules", merged)
    }

    private fun ensureGeoipCnRuleSet(route: JSONObject): String? {
        val sets = route.optJSONArray("rule_set") ?: JSONArray().also { route.put("rule_set", it) }
        for (i in 0 until sets.length()) {
            val s = sets.optJSONObject(i) ?: continue
            val tag = s.optString("tag")
            val t = tag.lowercase()
            if (t.contains("geoip-cn") || t.contains("geoip_cn") || t == "cn" || t.endsWith("-cn")) {
                return tag
            }
        }
        val tag = "geoip-cn"
        sets.put(
            JSONObject()
                .put("type", "remote")
                .put("tag", tag)
                .put("format", "binary")
                .put("url", "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-cn.srs")
                .put("download_detour", "direct")
                .put("update_interval", "7d"),
        )
        return tag
    }

    private fun applyStrictRoute(root: JSONObject) {
        val inbounds = root.optJSONArray("inbounds") ?: return
        for (i in 0 until inbounds.length()) {
            val ib = inbounds.optJSONObject(i) ?: continue
            if (ib.optString("type") != "tun") continue
            ib.put("strict_route", true)
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
        val old = route.optJSONArray("rules") ?: JSONArray()
        val injected = JSONObject()
            .put("ip_version", 6)
            .put("outbound", "block")
        val merged = JSONArray().put(injected)
        for (i in 0 until old.length()) merged.put(old.get(i))
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
