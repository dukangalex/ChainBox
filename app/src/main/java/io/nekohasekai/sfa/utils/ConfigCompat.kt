package io.nekohasekai.sfa.utils

import org.json.JSONObject

/**
 * Clash/mihomo → sing-box field coercion applied on import, remote refresh
 * and runtime overlay. Keeps user nodes; only rewrites fields the kernel
 * cannot decode.
 *
 * sing-box `ShadowsocksOutboundOptions.plugin_opts` is a string. Clash writes
 * an object (`plugin-opts: { mode, host }`), which makes libbox fail with
 * "cannot unmarshal object into Go struct field ... of type string".
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
        val outs = root.optJSONArray("outbounds") ?: return content
        var changed = false
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (sanitizeOutbound(o)) changed = true
        }
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
