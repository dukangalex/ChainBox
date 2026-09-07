package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * Runtime overlay: keep the user's nodes/groups, replace DNS / route / inbounds
 * with a sing-box 1.12+ template. Legacy inbound fields (`sniff`,
 * `sniff_override_destination`, `domain_strategy`) are never written — they
 * were removed in sing-box 1.13 and must live as route rule actions instead.
 */
object ConfigNormalize {

    private val dropOutboundTypes = setOf("direct", "block", "dns", "chain")
    private val groupTypes = setOf("selector", "urltest")
    private val nodeTypes = setOf(
        "shadowsocks", "shadowsocks2022", "vmess", "vless", "trojan",
        "hysteria", "hysteria2", "tuic", "wireguard", "shadowtls", "anytls",
        "socks", "http", "naive", "ssh", "tor", "mieru",
    )
    private val legacyInboundFields = listOf(
        "sniff",
        "sniff_override_destination",
        "sniff_timeout",
        "domain_strategy",
        "inbound_sniffing",
    )

    fun apply(content: String): String {
        val src = JSONObject(content)
        val keptOuts = JSONArray()
        val nodeTags = mutableListOf<String>()
        val groupTags = mutableListOf<String>()
        val srcOuts = src.optJSONArray("outbounds") ?: JSONArray()
        for (i in 0 until srcOuts.length()) {
            val o = srcOuts.optJSONObject(i) ?: continue
            val type = o.optString("type").trim()
            val tag = o.optString("tag").trim()
            if (tag.isEmpty()) continue
            when {
                type in dropOutboundTypes -> {}
                type in groupTypes -> {
                    keptOuts.put(JSONObject(o.toString()))
                    groupTags.add(tag)
                }
                type in nodeTypes || isLikelyNode(o) -> {
                    keptOuts.put(JSONObject(o.toString()))
                    nodeTags.add(tag)
                }
            }
        }
        if (nodeTags.isEmpty() && groupTags.isEmpty()) {
            error("规范化失败：配置里没有可保留的节点")
        }
        val proxyTag = pickProxyTag(src, groupTags, nodeTags)
        if (groupTags.isEmpty()) {
            val members = JSONArray()
            nodeTags.forEach { members.put(it) }
            keptOuts.put(JSONObject().put("type", "selector").put("tag", proxyTag).put("outbounds", members))
        }
        if (findTag(keptOuts, "direct") == null) {
            keptOuts.put(JSONObject().put("type", "direct").put("tag", "direct"))
        }
        if (findTag(keptOuts, "block") == null) {
            keptOuts.put(JSONObject().put("type", "block").put("tag", "block"))
        }

        val out = JSONObject()
        val logLevel = src.optJSONObject("log")?.optString("level").orEmpty().ifBlank { "info" }
        out.put("log", JSONObject().put("level", logLevel).put("timestamp", true))
        out.put("dns", buildDns(proxyTag))
        out.put("inbounds", buildInbounds())
        out.put("outbounds", keptOuts)
        if (src.has("endpoints")) out.put("endpoints", src.get("endpoints"))
        out.put("route", buildRoute(proxyTag))
        if (src.has("experimental")) out.put("experimental", src.get("experimental"))
        if (src.has("clash_api")) out.put("clash_api", src.get("clash_api"))
        return out.toString()
    }

    private fun isLikelyNode(o: JSONObject): Boolean =
        o.optString("server").isNotBlank() || o.optInt("server_port") > 0

    private fun pickProxyTag(src: JSONObject, groups: List<String>, nodes: List<String>): String {
        val fin = src.optJSONObject("route")?.optString("final").orEmpty().trim()
        if (fin.isNotEmpty() && (fin in groups || fin in nodes) && !isFinalLike(fin)) return fin
        fun score(tag: String): Int {
            val t = tag.lowercase()
            var s = 0
            if (t.contains("proxy") || t.contains("select") || t.contains("节点") || t.contains("选择") || t.contains("自动")) s += 20
            if (isFinalLike(tag)) s -= 50
            return s
        }
        return groups.maxByOrNull { score(it) } ?: "proxy"
    }

    private fun isFinalLike(tag: String): Boolean {
        val t = tag.lowercase()
        return t.contains("漏网") || t.contains("final") || t.contains("剩余") || t.contains("unmatched")
    }

    private fun dnsServer(tag: String, host: String, detour: String): JSONObject =
        JSONObject()
            .put("type", "https")
            .put("tag", tag)
            .put("server", host)
            .put("path", "/dns-query")
            .put("detour", detour)

    private fun buildDns(proxyTag: String): JSONObject {
        val servers = JSONArray()
            .put(dnsServer("dns-remote", "1.1.1.1", proxyTag))
            .put(dnsServer("dns-local", "223.5.5.5", "direct"))
        val rules = JSONArray().put(JSONObject().put("rule_set", "geosite-cn").put("server", "dns-local"))
        return JSONObject()
            .put("servers", servers)
            .put("rules", rules)
            .put("final", "dns-remote")
            .put("strategy", "ipv4_only")
            .put("independent_cache", true)
    }

    /**
     * Always emit a 1.13-safe TUN + local mixed inbound. Do not copy the
     * subscription's inbounds: those commonly still carry removed sniff fields.
     * Sniffing is done via route action instead (see [buildRoute]).
     */
    fun buildInbounds(): JSONArray {
        val tun = JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put("address", JSONArray().put("172.19.0.1/30"))
            .put("mtu", 9000)
            .put("auto_route", true)
            .put("strict_route", false)
        val mixed = JSONObject()
            .put("type", "mixed")
            .put("tag", "mixed-in")
            .put("listen", "127.0.0.1")
            .put("listen_port", 2080)
        stripLegacyInboundFields(tun)
        stripLegacyInboundFields(mixed)
        return JSONArray().put(tun).put(mixed)
    }

    fun stripLegacyInboundFields(inbound: JSONObject) {
        for (field in legacyInboundFields) inbound.remove(field)
    }

    private fun buildRoute(proxyTag: String): JSONObject {
        val ruleSet = JSONArray()
            .put(remoteSet("geoip-cn", "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-cn.srs"))
            .put(remoteSet("geosite-cn", "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-cn.srs"))
        val rules = JSONArray()
            .put(JSONObject().put("action", "sniff"))
            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
            .put(JSONObject().put("ip_is_private", true).put("outbound", "direct"))
        for (p in intArrayOf(3478, 19302, 5349)) {
            rules.put(JSONObject().put("network", "udp").put("port", p).put("outbound", "block"))
        }
        rules.put(JSONObject().put("rule_set", JSONArray().put("geosite-cn").put("geoip-cn")).put("outbound", "direct"))
        return JSONObject()
            .put("rule_set", ruleSet)
            .put("rules", rules)
            .put("final", proxyTag)
            .put("auto_detect_interface", true)
    }

    private fun remoteSet(tag: String, url: String): JSONObject =
        JSONObject()
            .put("type", "remote")
            .put("tag", tag)
            .put("format", "binary")
            .put("url", url)
            .put("download_detour", "direct")
            .put("update_interval", "7d")

    private fun findTag(outs: JSONArray, tag: String): JSONObject? {
        for (i in 0 until outs.length()) if (outs.optJSONObject(i)?.optString("tag") == tag) return outs.optJSONObject(i)
        return null
    }
}
