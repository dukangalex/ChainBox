package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * Clash/mihomo → sing-box field coercion applied on import, remote refresh
 * and runtime overlay. Keeps user nodes; only rewrites fields the kernel
 * cannot decode.
 *
 * sing-box `ShadowsocksOutboundOptions.plugin_opts` is a string. Clash writes
 * an object (`plugin-opts: { mode, host }`), which makes libbox fail with
 * "cannot unmarshal object into Go struct field ... of type string".
 *
 * sing-box 1.12+ also rejects DNS `detour` pointing at an empty `direct`
 * outbound. Those detours are stripped so the kernel uses the default
 * (direct) dialer instead of refusing to start.
 *
 * sing-box 1.14 removes `dns.fakeip` and legacy `servers[].address`. Import
 * and startup migrate them to typed servers so old subscriptions still load.
 */
object ConfigCompat {
    fun sanitize(content: String): String {
        val trimmed = content.trim()
        if (trimmed.isEmpty() || trimmed[0] != '{') return content
        val root = try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            return content
        }
        var changed = false
        val outs = root.optJSONArray("outbounds")
        if (outs != null) {
            for (i in 0 until outs.length()) {
                val o = outs.optJSONObject(i) ?: continue
                if (sanitizeOutbound(o)) changed = true
            }
        }
        if (migrateLegacyDns(root)) changed = true
        if (stripBrokenDnsDetours(root)) changed = true
        return if (changed) root.toString() else content
    }

    fun sanitizeOutbound(o: JSONObject): Boolean {
        var changed = false
        if (o.has("plugin-opts") && !o.has("plugin_opts")) {
            o.put("plugin_opts", o.get("plugin-opts"))
            o.remove("plugin-opts")
            changed = true
        }
        if (!o.has("plugin_opts")) return changed
        val raw = o.get("plugin_opts")
        if (raw is String) return changed
        if (raw is JSONObject) {
            o.put("plugin_opts", objectToPluginOpts(o.optString("plugin"), raw))
            return true
        }
        o.remove("plugin_opts")
        return true
    }

    /**
     * Convert `dns.fakeip` + `servers[].address` to sing-box 1.12 typed
     * servers. 1.14 rejects leftover `dns.fakeip` with
     * "legacy DNS fakeip options are deprecated...".
     */
    fun migrateLegacyDns(root: JSONObject): Boolean {
        val dns = root.optJSONObject("dns") ?: return false
        var changed = false
        var inet4: String? = null
        var inet6: String? = null
        var fakeipEnabled = false
        val fakeipObj = dns.optJSONObject("fakeip")
        if (fakeipObj != null) {
            fakeipEnabled = fakeipObj.optBoolean("enabled", true)
            inet4 = fakeipObj.optString("inet4_range").trim().ifEmpty { null }
            inet6 = fakeipObj.optString("inet6_range").trim().ifEmpty { null }
            if (fakeipEnabled) {
                inet4 = inet4 ?: "198.18.0.0/15"
                inet6 = inet6 ?: "fc00::/18"
            }
            dns.remove("fakeip")
            changed = true
        }
        val servers = dns.optJSONArray("servers") ?: JSONArray().also {
            if (fakeipEnabled) dns.put("servers", it)
        }
        var hasFakeipServer = false
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            if (migrateLegacyServer(server, inet4, inet6)) changed = true
            if (server.optString("type").equals("fakeip", true)) {
                hasFakeipServer = true
                if (inet4 != null && server.optString("inet4_range").isBlank()) {
                    server.put("inet4_range", inet4)
                    changed = true
                }
                if (inet6 != null && server.optString("inet6_range").isBlank()) {
                    server.put("inet6_range", inet6)
                    changed = true
                }
            }
        }
        if (fakeipEnabled && !hasFakeipServer) {
            val fake = JSONObject().put("type", "fakeip").put("tag", "fakeip")
            if (inet4 != null) fake.put("inet4_range", inet4)
            if (inet6 != null) fake.put("inet6_range", inet6)
            servers.put(fake)
            hasFakeipServer = true
            changed = true
        }
        if (hasFakeipServer && fakeipEnabled) ensureFakeipRule(dns)
        return changed
    }

    private fun ensureFakeipRule(dns: JSONObject) {
        val rules = dns.optJSONArray("rules") ?: JSONArray().also { dns.put("rules", it) }
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            if (rule.optString("server") == "fakeip") return
        }
        val types = JSONArray().put("A").put("AAAA")
        val injected = JSONObject().put("query_type", types).put("server", "fakeip")
        val merged = JSONArray().put(injected)
        for (i in 0 until rules.length()) merged.put(rules.get(i))
        dns.put("rules", merged)
    }

    internal fun migrateLegacyServer(server: JSONObject, inet4: String?, inet6: String?): Boolean {
        var changed = false
        if (server.has("address_resolver")) {
            if (!server.has("domain_resolver")) {
                server.put("domain_resolver", server.get("address_resolver"))
            }
            server.remove("address_resolver")
            changed = true
        }
        val type = server.optString("type").trim()
        if (type.isNotEmpty() && !type.equals("legacy", true)) {
            if (server.has("address")) {
                server.remove("address")
                changed = true
            }
            return changed
        }
        if (!server.has("address")) return changed
        val address = server.optString("address").trim()
        server.remove("address")
        when {
            address.equals("local", true) -> server.put("type", "local")
            address.equals("fakeip", true) -> {
                server.put("type", "fakeip")
                if (inet4 != null && server.optString("inet4_range").isBlank()) {
                    server.put("inet4_range", inet4)
                }
                if (inet6 != null && server.optString("inet6_range").isBlank()) {
                    server.put("inet6_range", inet6)
                }
            }
            address.startsWith("tcp://", true) ->
                applyHost(server, "tcp", address.substring(6))
            address.startsWith("tls://", true) ->
                applyHost(server, "tls", address.substring(6))
            address.startsWith("quic://", true) ->
                applyHost(server, "quic", address.substring(7))
            address.startsWith("udp://", true) ->
                applyHost(server, "udp", address.substring(6))
            address.startsWith("https://", true) ->
                applyUrl(server, "https", address.substring(8))
            address.startsWith("h3://", true) ->
                applyUrl(server, "h3", address.substring(5))
            address.startsWith("dhcp://", true) -> {
                server.put("type", "dhcp")
                val iface = address.substring(7).trim()
                if (iface.isNotEmpty() && !iface.equals("auto", true)) {
                    server.put("interface", iface)
                }
            }
            address.startsWith("rcode://", true) -> {
                server.put("type", "rcode")
                server.put("rcode", address.substring(8).trim())
            }
            else -> applyHost(server, "udp", address)
        }
        return true
    }

    private fun applyUrl(server: JSONObject, type: String, restRaw: String) {
        var rest = restRaw
        val hash = rest.indexOf('#')
        if (hash >= 0) rest = rest.substring(0, hash)
        val slash = rest.indexOf('/')
        val hostPort = if (slash >= 0) rest.substring(0, slash) else rest
        val path = if (slash >= 0) rest.substring(slash) else ""
        applyHost(server, type, hostPort)
        if (path.isNotBlank() && path != "/" && path != "/dns-query") {
            server.put("path", path)
        }
    }

    internal fun applyHost(server: JSONObject, type: String, hostPortRaw: String) {
        server.put("type", type)
        val hp = hostPortRaw.trim()
        if (hp.startsWith("[")) {
            val end = hp.indexOf(']')
            if (end > 0) {
                server.put("server", hp.substring(1, end))
                if (end + 1 < hp.length && hp[end + 1] == ':') {
                    hp.substring(end + 2).toIntOrNull()?.let { server.put("server_port", it) }
                }
                return
            }
        }
        val last = hp.lastIndexOf(':')
        val first = hp.indexOf(':')
        if (last > 0 && last == first) {
            val port = hp.substring(last + 1).toIntOrNull()
            if (port != null) {
                server.put("server", hp.substring(0, last))
                server.put("server_port", port)
                return
            }
        }
        server.put("server", hp)
    }

    /**
     * Drop DNS detours that would crash sing-box 1.12+:
     * - target outbound does not exist
     * - target is a `direct` outbound with no bind/override (empty direct)
     * Missing detour = default dialer = direct connect, which is what
     * `detour: "direct"` was trying to express.
     */
    fun stripBrokenDnsDetours(root: JSONObject): Boolean {
        val dns = root.optJSONObject("dns") ?: return false
        val servers = dns.optJSONArray("servers") ?: return false
        val outs = root.optJSONArray("outbounds") ?: JSONArray()
        val tags = mutableSetOf<String>()
        val emptyDirect = mutableSetOf<String>()
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            val tag = o.optString("tag").trim()
            if (tag.isEmpty()) continue
            tags.add(tag)
            if (o.optString("type").equals("direct", true) && isEmptyDirect(o)) {
                emptyDirect.add(tag)
            }
        }
        var changed = false
        for (i in 0 until servers.length()) {
            val server = servers.optJSONObject(i) ?: continue
            val detour = server.optString("detour").trim()
            if (detour.isEmpty()) continue
            if (detour !in tags || detour in emptyDirect) {
                server.remove("detour")
                changed = true
            }
        }
        return changed
    }

    internal fun isEmptyDirect(o: JSONObject): Boolean {
        if (!o.optString("type").equals("direct", true)) return false
        val marked = o.opt("routing_mark")
        if (marked is Number && marked.toInt() != 0) return false
        if (marked is String && marked.isNotBlank() && marked != "0") return false
        val keys = listOf(
            "override_address",
            "bind_interface",
            "inet4_bind_address",
            "inet6_bind_address",
            "inet4_address",
            "inet6_address",
        )
        if (keys.any { key -> o.optString(key).isNotBlank() }) return false
        val port = o.opt("override_port")
        return !(port is Number && port.toInt() != 0)
    }

    internal fun objectToPluginOpts(plugin: String, obj: JSONObject): String {
        val isObfs = plugin.contains("obfs", ignoreCase = true)
        val parts = mutableListOf<String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.opt(k) ?: continue
            when (v) {
                is Boolean -> {
                    if (v) parts.add(if (isObfs && k == "tls") "obfs=tls" else k)
                }
                else -> {
                    val value = v.toString()
                    parts.add(
                        when {
                            isObfs && k == "mode" -> "obfs=$value"
                            isObfs && (k == "host" || k == "obfs-host") -> "obfs-host=$value"
                            isObfs && (k == "uri" || k == "obfs-uri") -> "obfs-uri=$value"
                            else -> "$k=$value"
                        },
                    )
                }
            }
        }
        return parts.joinToString(";")
    }
}
