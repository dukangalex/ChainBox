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
