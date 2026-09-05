package io.nekohasekai.sfa.utils

import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Re-apply persisted chain (landing detour) at service start/reload so subscription
 * updates do not drop the chain. Direction: 当前入口 → 落地 → 目标.
 */
object ConfigChainReapply {
    suspend fun apply(content: String): String {
        if (!Settings.chainEnabled) return content
        val landingId = Settings.chainLandingProfileId
        val landingTag = Settings.chainLandingTag.trim()
        if (landingId < 0 || landingTag.isEmpty()) return content

        return try {
            val landingProfile = ProfileManager.get(landingId) ?: return content
            val landingPath = landingProfile.typed.path
            if (!File(landingPath).isFile) return content

            val root = JSONObject(content)
            var outs = root.optJSONArray("outbounds") ?: JSONArray()

            val cleaned = JSONArray()
            for (i in 0 until outs.length()) {
                val o = outs.optJSONObject(i) ?: continue
                val tag = o.optString("tag")
                if (tag.startsWith("ext-")) continue
                if (o.has("detour") && o.optString("detour").startsWith("ext-")) {
                    o.remove("detour")
                }
                cleaned.put(o)
            }
            outs = cleaned

            val hop = Hop(landingId, landingTag)
            outs = mergeHopTree(outs, hop, landingPath)

            val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
            val routeFinal = route.optString("final").trim()
            val main = resolveCurrentMainTag(outs, routeFinal) ?: return content

            // 当前 → 落地 → 目标：入口叶子经 detour 走落地，final 保持入口组
            val mergedLanding = hop.mergedTag
            // Merge must have produced the landing root outbound
            var hasLanding = false
            for (i in 0 until outs.length()) {
                if (outs.optJSONObject(i)?.optString("tag") == mergedLanding) {
                    hasLanding = true
                    break
                }
            }
            if (!hasLanding) return content

            applyDetourToLeaves(outs, main, mergedLanding)
            root.put("outbounds", outs)
            if (routeFinal.isEmpty() || routeFinal.startsWith("ext-")) {
                route.put("final", main)
            }
            root.toString()
        } catch (_: Exception) {
            content
        }
    }

    private data class Hop(val profileId: Long, val tag: String) {
        val mergedTag: String get() = "ext-$profileId-$tag"
    }

    private fun resolveCurrentMainTag(outs: JSONArray, routeFinal: String): String? {
        fun scoreTag(tag: String, type: String): Int {
            var s = 0
            if (type == "urltest") s += 20
            if (type == "selector") s += 15
            val t = tag.lowercase()
            if (t.contains("漏网") || t.contains("final") || t.contains("剩余")) s -= 80
            if (t.contains("节点") || t.contains("选择") || t.contains("自动") || t.contains("proxy") || t.contains("select")) s += 25
            if (t.equals("global", true)) s += 5
            return s
        }
        data class Cand(val tag: String, val score: Int)
        val cands = mutableListOf<Cand>()
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            val tag = o.optString("tag").trim()
            val type = o.optString("type").trim()
            if (tag.isEmpty() || tag.startsWith("ext-")) continue
            if (type != "selector" && type != "urltest") continue
            cands.add(Cand(tag, scoreTag(tag, type)))
        }
        if (routeFinal.isNotEmpty() && !routeFinal.startsWith("ext-")) {
            for (i in 0 until outs.length()) {
                val o = outs.optJSONObject(i) ?: continue
                if (o.optString("tag") != routeFinal) continue
                val type = o.optString("type")
                if (type == "selector" || type == "urltest") {
                    if (scoreTag(routeFinal, type) >= 40) return routeFinal
                }
            }
        }
        return cands.maxByOrNull { it.score }?.tag
    }

    private fun findOutbound(outs: JSONArray, tag: String): JSONObject? {
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (o.optString("tag") == tag) return o
        }
        return null
    }

    private fun applyDetourToLeaves(outs: JSONArray, rootTag: String, detourTag: String) {
        val visiting = mutableSetOf<String>()
        fun walk(tag: String) {
            if (tag.isEmpty() || !visiting.add(tag)) return
            val o = findOutbound(outs, tag) ?: return
            val type = o.optString("type")
            if (type == "selector" || type == "urltest") {
                val members = o.optJSONArray("outbounds") ?: return
                for (i in 0 until members.length()) walk(members.optString(i))
                return
            }
            if (type == "direct" || type == "block" || type == "dns" || type == "chain") return
            o.put("detour", detourTag)
        }
        walk(rootTag)
    }

    private fun mergeHopTree(outs: JSONArray, hop: Hop, sourcePath: String): JSONArray {
        val srcRoot = try {
            JSONObject(File(sourcePath).readText())
        } catch (_: Exception) {
            return outs
        }
        val srcOuts = srcRoot.optJSONArray("outbounds") ?: return outs
        val visiting = mutableSetOf<String>()

        fun alreadyHas(tag: String): Boolean {
            for (i in 0 until outs.length()) {
                if (outs.optJSONObject(i)?.optString("tag") == tag) return true
            }
            return false
        }

        fun findSrc(tag: String): JSONObject? {
            for (i in 0 until srcOuts.length()) {
                val o = srcOuts.optJSONObject(i) ?: continue
                if (o.optString("tag") == tag) return o
            }
            return null
        }

        fun putClone(src: JSONObject, newTag: String) {
            if (alreadyHas(newTag)) return
            val clone = JSONObject(src.toString())
            clone.put("tag", newTag)
            if (clone.has("detour")) clone.remove("detour")
            val type = clone.optString("type")
            if (type == "selector" || type == "urltest") {
                val members = clone.optJSONArray("outbounds") ?: JSONArray()
                val mapped = JSONArray()
                for (i in 0 until members.length()) {
                    val m = members.optString(i)
                    if (m.isNotEmpty()) mapped.put("ext-${hop.profileId}-$m")
                }
                clone.put("outbounds", mapped)
                if (clone.has("default")) {
                    val d = clone.optString("default")
                    if (d.isNotEmpty()) clone.put("default", "ext-${hop.profileId}-$d")
                }
            }
            outs.put(clone)
        }

        fun mergeTag(tag: String) {
            if (tag.isEmpty() || tag == "direct" || tag == "block" || tag == "dns") return
            if (!visiting.add(tag)) return
            val src = findSrc(tag) ?: return
            val type = src.optString("type")
            if (type == "chain") return
            val newTag = "ext-${hop.profileId}-$tag"
            if (alreadyHas(newTag)) {
                visiting.remove(tag)
                return
            }
            if (type == "selector" || type == "urltest") {
                val members = src.optJSONArray("outbounds") ?: JSONArray()
                for (i in 0 until members.length()) mergeTag(members.optString(i))
                val def = src.optString("default")
                if (def.isNotEmpty()) mergeTag(def)
            }
            val detour = src.optString("detour")
            if (detour.isNotEmpty()) mergeTag(detour)
            putClone(src, newTag)
            visiting.remove(tag)
        }

        mergeTag(hop.tag)
        return outs
    }
}
