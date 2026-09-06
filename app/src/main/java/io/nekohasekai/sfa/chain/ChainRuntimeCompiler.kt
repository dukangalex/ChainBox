package io.nekohasekai.sfa.chain

import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Single source of truth for ChainBox runtime chain materialization.
 * The source profile remains the user's configuration; only the runtime
 * overlay is rebuilt on every apply/update. The final outbound is always a
 * native sing-box `type: chain` outbound.
 */
object ChainRuntimeCompiler {
    private const val NATIVE_CHAIN_TYPE = "chain"
    private const val GENERATED_PREFIX = "chainbox-chain-"
    private const val LANDING_PREFIX = "chainbox-landing-"
    private const val LEGACY_PREFIX = "ext-"
    private const val LEGACY_CHAIN_TAG = "my-chain"

    suspend fun apply(content: String, currentProfileId: Long = Settings.selectedProfile): String {
        if (!Settings.chainEnabled) return content
        val bound = Settings.chainBoundProfileId
        if (bound >= 0L && bound != currentProfileId) return content
        if (bound < 0L) Settings.chainBoundProfileId = currentProfileId

        val landingId = Settings.chainLandingProfileId
        val landingTag = Settings.chainLandingTag.trim()
        require(landingId >= 0L && landingTag.isNotEmpty()) { "未选择链式出口" }
        require(landingId != currentProfileId) { "链式入口与落地不能是同一配置" }

        val root = JSONObject(content)
        val outs = cleanGeneratedOutbounds(root.optJSONArray("outbounds") ?: JSONArray())
        root.put("outbounds", outs)
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val routeFinal = route.optString("final").trim()
        val main = resolveMainTag(outs, routeFinal) ?: error("无法识别当前配置的主出站")
        validateEntryGraph(outs, main)

        val landingProfile = ProfileManager.get(landingId) ?: error("出口配置不存在或已被删除")
        val landingRoot = JSONObject(File(landingProfile.typed.path).readText())
        val landingOuts = landingRoot.optJSONArray("outbounds") ?: error("出口配置没有 outbounds")
        val landingMergedTag = mergeLandingGraph(outs, landingOuts, landingId, landingTag)

        val chainTag = "$GENERATED_PREFIX$currentProfileId-$landingId"
        removeOutbound(outs, chainTag)
        val chain = JSONObject()
            .put("type", NATIVE_CHAIN_TYPE)
            .put("tag", chainTag)
            .put("outbounds", JSONArray().put(main).put(landingMergedTag))
        outs.put(chain)
        route.put("final", chainTag)
        root.put("outbounds", outs)
        return root.toString()
    }

    fun clear(content: String, restoreFinal: String?): String {
        val root = JSONObject(content)
        val cleaned = cleanGeneratedOutbounds(root.optJSONArray("outbounds") ?: JSONArray())
        root.put("outbounds", cleaned)
        val route = root.optJSONObject("route")
        if (route != null) {
            val final = route.optString("final")
            if (final.startsWith(GENERATED_PREFIX) || final == LEGACY_CHAIN_TAG || final.startsWith(LEGACY_PREFIX)) {
                if (!restoreFinal.isNullOrBlank()) route.put("final", restoreFinal) else route.remove("final")
            }
        }
        return root.toString()
    }

    private fun cleanGeneratedOutbounds(source: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until source.length()) {
            val o = source.optJSONObject(i) ?: continue
            val tag = o.optString("tag")
            if (tag == LEGACY_CHAIN_TAG || tag.startsWith(GENERATED_PREFIX) || tag.startsWith(LANDING_PREFIX) || tag.startsWith(LEGACY_PREFIX)) continue
            if (o.optString("detour").startsWith(LEGACY_PREFIX)) o.remove("detour")
            out.put(o)
        }
        return out
    }

    private fun resolveMainTag(outs: JSONArray, routeFinal: String): String? {
        if (routeFinal.isNotEmpty()) {
            val o = find(outs, routeFinal)
            if (o != null && o.optString("type") in listOf("selector", "urltest")) return routeFinal
        }
        var best: String? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            val tag = o.optString("tag").trim()
            val type = o.optString("type")
            if (tag.isEmpty() || tag.startsWith(GENERATED_PREFIX) || type !in listOf("selector", "urltest")) continue
            var score = if (type == "urltest") 20 else 15
            val t = tag.lowercase()
            if (t.contains("final") || t.contains("漏网") || t.contains("剩余")) score -= 80
            if (t.contains("proxy") || t.contains("select") || t.contains("节点") || t.contains("选择") || t.contains("自动")) score += 25
            if (score > bestScore) { bestScore = score; best = tag }
        }
        return best
    }

    private fun validateEntryGraph(outs: JSONArray, rootTag: String) {
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun walk(tag: String) {
            require(tag.isNotBlank()) { "Chain 入口包含空 outbound" }
            if (tag in visiting) error("Chain 入口拓扑存在循环：$tag")
            if (!visited.add(tag)) return
            val o = find(outs, tag) ?: error("Chain 入口引用不存在的 outbound：$tag")
            val type = o.optString("type")
            if (type == "direct" || type == "block" || type == "dns" || type == NATIVE_CHAIN_TYPE) {
                error("Chain 入口包含不可作为前置代理的 outbound：$tag ($type)")
            }
            visiting.add(tag)
            if (type == "selector" || type == "urltest") {
                val members = o.optJSONArray("outbounds") ?: error("分组没有 outbounds：$tag")
                require(members.length() > 0) { "Chain 入口分组为空：$tag" }
                for (i in 0 until members.length()) walk(members.optString(i))
            }
            visiting.remove(tag)
        }
        walk(rootTag)
    }

    private fun mergeLandingGraph(dst: JSONArray, src: JSONArray, profileId: Long, rootTag: String): String {
        val visiting = mutableSetOf<String>()
        val merged = mutableMapOf<String, String>()

        fun merge(tag: String): String {
            require(tag.isNotBlank()) { "落地 outbound 为空" }
            if (tag in visiting) error("落地配置拓扑存在循环：$tag")
            merged[tag]?.let { return it }
            visiting.add(tag)
            val original = find(src, tag) ?: error("落地配置引用不存在的 outbound：$tag")
            val type = original.optString("type")
            require(type != NATIVE_CHAIN_TYPE) { "不允许把已有 Chain 作为落地 Chain 的子链：$tag" }
            require(type != "direct" && type != "block" && type != "dns") { "落地不能使用 $type：$tag" }
            require(original.optString("detour").isBlank()) { "落地 outbound 含 detour，无法安全嵌入 Chain：$tag" }

            val newTag = "$LANDING_PREFIX$profileId-$tag"
            merged[tag] = newTag
            val clone = JSONObject(original.toString()).put("tag", newTag)
            if (type == "selector" || type == "urltest") {
                val members = original.optJSONArray("outbounds") ?: error("落地分组没有 outbounds：$tag")
                val mapped = JSONArray()
                for (i in 0 until members.length()) {
                    val member = members.optString(i)
                    if (member == "direct" || member == "block" || member == "dns") continue
                    mapped.put(merge(member))
                }
                require(mapped.length() > 0) { "落地分组过滤后没有可用代理：$tag" }
                clone.put("outbounds", mapped)
                if (clone.has("default")) {
                    val d = clone.optString("default")
                    if (d.isNotBlank() && d != "direct" && d != "block" && d != "dns") clone.put("default", merge(d))
                    else clone.remove("default")
                }
            }
            if (find(dst, newTag) == null) dst.put(clone)
            visiting.remove(tag)
            return newTag
        }
        return merge(rootTag)
    }

    private fun find(outs: JSONArray, tag: String): JSONObject? {
        for (i in 0 until outs.length()) if (outs.optJSONObject(i)?.optString("tag") == tag) return outs.optJSONObject(i)
        return null
    }

    private fun removeOutbound(outs: JSONArray, tag: String) {
        for (i in outs.length() - 1 downTo 0) if (outs.optJSONObject(i)?.optString("tag") == tag) outs.remove(i)
    }
}
