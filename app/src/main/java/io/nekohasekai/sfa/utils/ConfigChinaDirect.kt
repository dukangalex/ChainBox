package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * Runtime "China direct" overlay. Does not rewrite the subscription file.
 *
 * Force-applies six bypasses whenever the switch is on, even if the
 * profile has no matching geoip/geosite/dns/route sections:
 * 1. China IPs (reuse geoip-cn if present, else domain/IP fallbacks)
 * 2. China domains
 * 3. China public DNS IPs
 * 4. China public DNS domains
 * 5. LAN IPs
 * 6. LAN domains
 */
object ConfigChinaDirect {
    const val CN_DNS_TAG = "chainbox-cn-dns"

    val LAN_DOMAIN_SUFFIXES: List<String> = listOf(
        "local", "lan", "localhost", "home.arpa", "localdomain",
        "internal", "intranet", "private", "local.lan",
    )

    val CHINA_DNS_IPS: List<String> = listOf(
        "114.114.114.114/32", "114.114.115.115/32",
        "223.5.5.5/32", "223.6.6.6/32",
        "180.76.76.76/32",
        "119.29.29.29/32", "119.28.28.28/32",
        "1.2.4.8/32", "210.2.4.8/32",
        "117.50.10.10/32", "117.50.11.11/32", "117.50.22.22/32",
        "1.12.12.12/32", "120.53.53.53/32",
        "101.226.4.6/32", "123.125.81.6/32",
        "202.96.134.133/32", "202.96.128.86/32",
    )

    val CHINA_DNS_DOMAINS: List<String> = listOf(
        "dns.alidns.com", "doh.pub", "dns.pub", "dot.pub",
        "d.alidns.com", "dns.qq.com", "pdns.aliyun.com",
        "dns.hichina.com", "onedns.net",
    )

    val CHINA_DNS_DOMAIN_SUFFIXES: List<String> = listOf(
        "alidns.com", "dnspod.com", "dnspod.cn", "doh.pub", "dns.pub",
        "360.cn", "onedns.net", "hichina.com",
    )

    fun apply(root: JSONObject) {
        val outs = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
        val direct = findOrCreateDirect(outs)
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val injected = chinaRouteRules(direct, existingRuleSetTags(route))
        val old = route.optJSONArray("rules") ?: JSONArray()
        val merged = JSONArray()
        for (i in 0 until injected.length()) merged.put(injected.get(i))
        for (i in 0 until old.length()) merged.put(old.get(i))
        route.put("rules", merged)
        applyCnDns(root, direct)
    }

    fun chinaRouteRules(directTag: String, existingRuleSets: Set<String>): JSONArray {
        val rules = JSONArray()
        rules.put(
            JSONObject()
                .put("ip_is_private", true)
                .put("outbound", directTag),
        )
        rules.put(
            JSONObject()
                .put("domain_suffix", toArray(LAN_DOMAIN_SUFFIXES))
                .put("domain", JSONArray().put("localhost"))
                .put("outbound", directTag),
        )
        rules.put(
            JSONObject()
                .put("ip_cidr", toArray(CHINA_DNS_IPS))
                .put("outbound", directTag),
        )
        rules.put(
            JSONObject()
                .put("domain", toArray(CHINA_DNS_DOMAINS))
                .put("domain_suffix", toArray(CHINA_DNS_DOMAIN_SUFFIXES))
                .put("outbound", directTag),
        )
        val geoip = existingRuleSets.filter { tag ->
            val t = tag.lowercase()
            t.contains("geoip-cn") || t.contains("geoip_cn") || t == "cn-ip" || t == "china-ip"
        }
        val geosite = existingRuleSets.filter { tag ->
            val t = tag.lowercase()
            t.contains("geosite-cn") || t.contains("geosite_cn") || t == "cn" || t == "china"
        }
        if (geoip.isNotEmpty()) {
            rules.put(JSONObject().put("rule_set", toArray(geoip)).put("outbound", directTag))
        }
        if (geosite.isNotEmpty()) {
            rules.put(JSONObject().put("rule_set", toArray(geosite)).put("outbound", directTag))
        }
        rules.put(
            JSONObject()
                .put("domain_suffix", ConfigNormalize.cnDomainSuffixArray())
                .put("outbound", directTag),
        )
        return rules
    }

    fun findOrCreateDirect(outs: JSONArray): String {
        val preferred = listOf("direct", "DIRECT", "直连", "🚀 直连", "🎯 全球直连")
        for (tag in preferred) {
            val o = find(outs, tag)
            if (o != null && o.optString("type").equals("direct", true)) return tag
        }
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (!o.optString("type").equals("direct", true)) continue
            val tag = o.optString("tag").trim()
            if (tag.isEmpty()) continue
            val lower = tag.lowercase()
            if (lower == "dns" || lower == "block" || lower == "reject") continue
            return tag
        }
        val tag = "direct"
        if (find(outs, tag) == null) {
            outs.put(JSONObject().put("type", "direct").put("tag", tag))
        }
        return tag
    }

    private fun applyCnDns(root: JSONObject, directTag: String) {
        val dns = root.optJSONObject("dns") ?: JSONObject().also { root.put("dns", it) }
        val servers = dns.optJSONArray("servers") ?: JSONArray().also { dns.put("servers", it) }
        if (!serverExists(servers, CN_DNS_TAG)) {
            val typed = (0 until servers.length()).any { servers.optJSONObject(it)?.has("type") == true }
            if (typed) {
                servers.put(
                    JSONObject()
                        .put("type", "udp")
                        .put("tag", CN_DNS_TAG)
                        .put("server", "223.5.5.5")
                        .put("server_port", 53)
                        .put("detour", directTag),
                )
            } else {
                servers.put(
                    JSONObject()
                        .put("tag", CN_DNS_TAG)
                        .put("address", "223.5.5.5")
                        .put("detour", directTag),
                )
            }
        }
        val old = dns.optJSONArray("rules") ?: JSONArray()
        val dnsRule = JSONObject()
            .put("domain_suffix", ConfigNormalize.cnDomainSuffixArray())
            .put("server", CN_DNS_TAG)
        val merged = JSONArray().put(dnsRule)
        for (i in 0 until old.length()) merged.put(old.get(i))
        dns.put("rules", merged)
    }

    private fun existingRuleSetTags(route: JSONObject): Set<String> {
        val arr = route.optJSONArray("rule_set") ?: return emptySet()
        val out = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val tag = item.optString("tag").trim()
            if (tag.isNotEmpty()) out.add(tag)
        }
        return out
    }

    private fun serverExists(servers: JSONArray, tag: String): Boolean {
        for (i in 0 until servers.length()) {
            if (servers.optJSONObject(i)?.optString("tag") == tag) return true
        }
        return false
    }

    private fun find(outs: JSONArray, tag: String): JSONObject? {
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (o.optString("tag") == tag) return o
        }
        return null
    }

    private fun toArray(items: Collection<String>): JSONArray {
        val a = JSONArray()
        items.forEach { a.put(it) }
        return a
    }
}
