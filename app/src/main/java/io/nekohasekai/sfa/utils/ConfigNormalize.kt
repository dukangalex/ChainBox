package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * Runtime overlay script: keep user nodes / selector groups, replace the rest
 * with a sing-box-compatible China template. Does not write the profile file.
 */
object ConfigNormalize {

    private val dropOutboundTypes = setOf("direct", "block", "dns", "chain")
    private val groupTypes = setOf("selector", "urltest")
    private val nodeTypes = setOf(
        "shadowsocks", "shadowsocks2022", "vmess", "vless", "trojan",
        "hysteria", "hysteria2", "tuic", "wireguard", "shadowtls", "anytls",
        "socks", "http", "naive", "ssh", "tor", "mieru",
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
            keptOuts.put(
                JSONObject()
                    .put("type", "selector")
                    .put("tag", proxyTag)
                    .put("outbounds", members),
            )
        }
        keptOuts.put(JSONObject().put("type", "direct").put("tag", "direct"))
        keptOuts.put(JSONObject().put("type", "block").put("tag", "block"))

        val out = JSONObject()
        out.put("log", JSONObject().put("level", src.optJSONObject("log")?.optString("level").orEmpty().ifBlank { "info" }).put("timestamp", true))
        out.put("dns", buildDns(proxyTag))
        out.put("inbounds", buildInbounds(src.optJSONArray("inbounds")))
        out.put("outbounds", keptOuts)
        if (src.has("endpoints")) out.put("endpoints", src.get("endpoints"))
        out.put("route", buildRoute(proxyTag))
        if (src.has("experimental")) out.put("experimental", src.get("experimental"))
        return out.toString()
    }

    private fun isLikelyNode(o: JSONObject): Boolean {
        if (o.optString("server").isNotBlank()) return true
        if (o.optString("server_port").isNotBlank() || o.optInt("server_port") > 0) return true
        return false
    }

    private fun pickProxyTag(src: JSONObject, groups: List<String>, nodes: List<String>): String {
        val fin = src.optJSONObject("route")?.optString("final").orEmpty().trim()
        if (fin.isNotEmpty() && (fin in groups || fin in nodes)) return fin
        fun score(tag: String): Int {
            val t = tag.lowercase()
            var s = 0
            if (t.contains("proxy") || t.contains("select") || t.contains("节点") || t.contains("自动")) s += 20
            if (t.contains("final") || t.contains("漏网")) s -= 50
            return s
        }
        return groups.maxByOrNull { score(it) } ?: "proxy"
    }

    private fun buildDns(proxyTag: String): JSONObject {
        val servers = JSONArray()
            .put(
                JSONObject()
                    .put("tag", "dns-remote")
                    .put("address", "https://1.1.1.1/dns-query")
                    .put("detour", proxyTag),
            )
            .put(
                JSONObject()
                    .put("tag", "dns-local")
                    .put("address", "https://223.5.5.5/dns-query")
                    .put("detour", "direct"),
            )
        val rules = JSONArray()
            .put(JSONObject().put("rule_set", "geosite-cn").put("server", "dns-local"))
            .put(JSONObject().put("outbound", "any").put("server", "dns-local"))
        return JSONObject()
            .put("servers", servers)
            .put("rules", rules)
            .put("final", "dns-remote")
            .put("strategy", "ipv4_only")
            .put("independent_cache", true)
    }

    private fun buildInbounds(src: JSONArray?): JSONArray {
        val result = JSONArray()
        var hasTun = false
        if (src != null) {
            for (i in 0 until src.length()) {
                val ib = src.optJSONObject(i) ?: continue
                if (ib.optString("type") == "tun") {
                    hasTun = true
                    if (!ib.has("sniff")) ib.put("sniff", true)
                    if (!ib.has("auto_route")) ib.put("auto_route", true)
                    result.put(ib)
                } else {
                    result.put(ib)
                }
            }
        }
        if (!hasTun) {
            result.put(
                JSONObject()
                    .put("type", "tun")
                    .put("tag", "tun-in")
                    .put("address", JSONArray().put("172.19.0.1/30"))
                    .put("auto_route", true)
                    .put("strict_route", false)
                    .put("sniff", true),
            )
        }
        return result
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
}
