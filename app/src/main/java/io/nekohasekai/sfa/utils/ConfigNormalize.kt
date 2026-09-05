package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * Conservative runtime normalize for common sing-box config issues.
 * Does not rewrite subscription files on disk.
 */
object ConfigNormalize {
    fun apply(content: String): String {
        return try {
            val root = JSONObject(content)
            ensureBasicOutbounds(root)
            sanitizeRuleSets(root)
            sanitizeRouteFinal(root)
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
}
