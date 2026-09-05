package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * Runtime-only config hygiene for typical China subscription JSON.
 * - strip deprecated geoip/geosite fields
 * - drop broken rule_set entries
 * - ensure direct/block outbounds exist
 * - inject high-priority anti-leak rules (WebRTC/STUN/TURN)
 * Does NOT rewrite profile files on disk.
 */
object ConfigNormalize {

    fun apply(content: String): String {
        return try {
            val root = JSONObject(content)
            ensureBasicOutbounds(root)
            stripDeprecatedGeoDatabases(root)
            sanitizeRuleSets(root)
            sanitizeRouteFinal(root)
            injectChinaAntiLeakRules(root)
            hardenDns(root)
            root.toString()
        } catch (_: Exception) {
            content
        }
    }

    private fun ensureBasicOutbounds(root: JSONObject) {
        val outs = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
        fun hasTypeOrTag(type: String, tag: String): Boolean {
            for (i in 0 until outs.length()) {
                val o = outs.optJSONObject(i) ?: continue
                if (o.optString("type") == type || o.optString("tag") == tag) return true
            }
            return false
        }
        if (!hasTypeOrTag("direct", "direct")) {
            outs.put(JSONObject().put("type", "direct").put("tag", "direct"))
        }
        if (!hasTypeOrTag("block", "block")) {
            outs.put(JSONObject().put("type", "block").put("tag", "block"))
        }
    }

    private fun stripDeprecatedGeoDatabases(root: JSONObject) {
        val route = root.optJSONObject("route")
        if (route != null) {
            sanitizeRulesArray(route.optJSONArray("rules"))
            route.remove("geoip")
            route.remove("geosite")
        }
        val dns = root.optJSONObject("dns")
        if (dns != null) {
            sanitizeRulesArray(dns.optJSONArray("rules"))
        }
    }

    private fun sanitizeRulesArray(rules: JSONArray?) {
        if (rules == null) return
        for (i in 0 until rules.length()) {
            val r = rules.optJSONObject(i) ?: continue
            r.remove("geoip")
            r.remove("geosite")
            r.remove("geo_ip")
            r.remove("geo_site")
        }
    }

    private fun sanitizeRuleSets(root: JSONObject) {
        val route = root.optJSONObject("route") ?: return
        val sets = route.optJSONArray("rule_set") ?: return
        val kept = JSONArray()
        for (i in 0 until sets.length()) {
            val s = sets.optJSONObject(i) ?: continue
            val tag = s.optString("tag").trim()
            val type = s.optString("type").trim()
            if (tag.isEmpty()) continue
            when (type) {
                "local" -> {
                    if (s.optString("path").isBlank()) continue
                    kept.put(s)
                }
                "remote" -> {
                    if (s.optString("url").isBlank()) continue
                    if (!s.has("update_interval")) s.put("update_interval", "1d")
                    kept.put(s)
                }
                else -> {
                    if (s.optString("path").isNotBlank() || s.optString("url").isNotBlank()) {
                        if (type.isEmpty()) {
                            if (s.optString("url").isNotBlank()) s.put("type", "remote")
                            else s.put("type", "local")
                        }
                        kept.put(s)
                    }
                }
            }
        }
        route.put("rule_set", kept)
    }

    private fun sanitizeRouteFinal(root: JSONObject) {
        val route = root.optJSONObject("route") ?: return
        val fin = route.optString("final").trim()
        if (fin.isEmpty()) return
        val outs = root.optJSONArray("outbounds") ?: return
        for (i in 0 until outs.length()) {
            if (outs.optJSONObject(i)?.optString("tag") == fin) return
        }
        route.remove("final")
    }

    /**
     * China-network oriented anti-leak rules (prepended, highest priority):
     * Block classic STUN/TURN ports so WebRTC cannot learn the real ISP IP via
     * domestic STUN (Bilibili / Xiaomi / Mango etc. are often matched as
     * "CN direct" by subscription rules).
     */
    private fun injectChinaAntiLeakRules(root: JSONObject) {
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val old = route.optJSONArray("rules") ?: JSONArray()
        for (i in 0 until old.length()) {
            val r = old.optJSONObject(i) ?: continue
            if (r.optString("outbound") != "block") continue
            if (r.optString("network") != "udp") continue
            val port = r.opt("port") ?: continue
            val hits = when (port) {
                is Int -> port == 3478
                is JSONArray -> (0 until port.length()).any { port.optInt(it) == 3478 }
                else -> false
            }
            if (hits) return
        }
        val injected = JSONArray()
        injected.put(
            JSONObject()
                .put("network", "udp")
                .put("port", JSONArray().put(3478).put(19302).put(5349))
                .put("outbound", "block"),
        )
        val merged = JSONArray()
        for (i in 0 until injected.length()) merged.put(injected.get(i))
        for (i in 0 until old.length()) merged.put(old.get(i))
        route.put("rules", merged)
    }

    private fun hardenDns(root: JSONObject) {
        val dns = root.optJSONObject("dns") ?: return
        if (!dns.has("independent_cache")) {
            dns.put("independent_cache", true)
        }
    }
}
