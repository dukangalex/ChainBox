package io.nekohasekai.sfa.chain

import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for ChainBox runtime chain materialization.
 * The source profile remains the user's configuration; only the runtime
 * overlay is rebuilt on every apply/update. Subscription refresh overwrites
 * the JSON file but does not clear per-profile ChainBindings. If the saved
 * entry tag disappeared, apply() falls back to resolveMainTag so the landing
 * binding stays in effect. The final outbound is always a native sing-box
 * `type: chain` outbound.
 *
 * Packet path: outbounds[0] is the entry (closest to the client), last is the
 * landing/exit (public IP). The kernel clones later hops with detour=previous
 * so IP checks show the landing node, not the front airport.
 * Chain hops are user-selected outbounds. Selector/urltest groups may contain
 * DIRECT as a UI choice; those members are stripped from the hop clone so the
 * generated chain never includes an unauthorized DIRECT/block/dns hop.
 * Connection failure is fail-closed (no silent DIRECT fallback).
 */
object ChainRuntimeCompiler {
    const val NATIVE_CHAIN_TYPE = "chain"
    const val GENERATED_PREFIX = "chainbox-chain-"
    const val LANDING_PREFIX = "chainbox-landing-"
    const val ENTRY_PREFIX = "chainbox-entry-"
    const val LEGACY_PREFIX = "ext-"
    const val LEGACY_CHAIN_TAG = "my-chain"

    private val forbiddenTypes = setOf("direct", "block", "dns", NATIVE_CHAIN_TYPE)
    private val forbiddenTags = setOf("direct", "block", "dns")
    private val groupTypes = setOf("selector", "urltest")

    data class ApplyRequest(
        val content: String,
        val currentProfileId: Long,
        val entryTag: String?,
        val landingProfileId: Long,
        val landingTag: String,
        val landingContent: String?,
    )

    data class Hop(
        val profileId: Long,
        val profileName: String,
        val tag: String,
        val type: String,
    )

    fun apply(req: ApplyRequest): String {
        require(req.landingTag.isNotEmpty()) { "未选择链式落地出口" }
        val root = JSONObject(req.content)
        val outs = cleanGeneratedOutbounds(root.optJSONArray("outbounds") ?: JSONArray())
        root.put("outbounds", outs)
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val routeFinal = route.optString("final").trim()
        val requested = req.entryTag?.trim().orEmpty()
        val main = when {
            requested.isNotEmpty() && find(outs, requested) != null -> requested
            else -> resolveMainTag(outs, routeFinal) ?: error("无法识别当前配置的链式入口，请到「工具 → 链式代理」手动选择入口")
        }

        val sameProfile = req.landingContent.isNullOrBlank() || req.landingProfileId == req.currentProfileId
        val landingMergedTag = if (sameProfile) {
            require(req.landingTag != main) { "入口与落地不能是同一个 outbound" }
            find(outs, req.landingTag) ?: error("落地 outbound 不存在：${req.landingTag}")
            prepareGroupHop(outs, req.landingTag, extraExclude = setOf(main), tagPrefix = "$LANDING_PREFIX${req.currentProfileId}-")
        } else {
            val landingRoot = JSONObject(req.landingContent)
            val landingOuts = landingRoot.optJSONArray("outbounds") ?: error("落地配置没有 outbounds")
            mergeLandingGraph(outs, landingOuts, req.landingProfileId, req.landingTag)
        }

        val entryHop = prepareGroupHop(
            outs,
            main,
            extraExclude = setOf(req.landingTag, landingMergedTag),
            tagPrefix = ENTRY_PREFIX,
        )

        val chainTag = "$GENERATED_PREFIX${req.currentProfileId}-${req.landingProfileId}"
        removeOutbound(outs, chainTag)
        val chain = JSONObject()
            .put("type", NATIVE_CHAIN_TYPE)
            .put("tag", chainTag)
            .put("outbounds", JSONArray().put(entryHop).put(landingMergedTag))
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

    fun isFinalLike(tag: String): Boolean {
        val t = tag.lowercase()
        return t.contains("漏网") || t.contains("final") || t.contains("剩余") ||
            t.contains("unmatched") || t == "match"
    }

    fun resolveMainTag(outs: JSONArray, routeFinal: String): String? {
        if (routeFinal.isNotEmpty() && !isFinalLike(routeFinal) && !routeFinal.startsWith(GENERATED_PREFIX)) {
            val o = find(outs, routeFinal)
            if (o != null && o.optString("type") in groupTypes && !isForbiddenHop(outs, o, routeFinal)) return routeFinal
        }
        var best: String? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            val tag = o.optString("tag").trim()
            val type = o.optString("type")
            if (tag.isEmpty() || isGeneratedTag(tag)) continue
            if (type !in groupTypes) continue
            if (isForbiddenHop(outs, o, tag)) continue
            var score = if (type == "urltest") 20 else 15
            val t = tag.lowercase()
            if (isFinalLike(tag)) score -= 80
            if (t.contains("proxy") || t.contains("select") || t.contains("节点") || t.contains("选择") || t.contains("自动")) score += 25
            if (score > bestScore) {
                bestScore = score
                best = tag
            }
        }
        return best
    }

    fun listSelectableHops(content: String, profileId: Long, profileName: String): List<Hop> {
        val outs = JSONObject(content).optJSONArray("outbounds") ?: return emptyList()
        return buildList {
            for (i in 0 until outs.length()) {
                val o = outs.optJSONObject(i) ?: continue
                val tag = o.optString("tag").trim()
                val type = o.optString("type").trim()
                if (tag.isEmpty() || isGeneratedTag(tag) || type in forbiddenTypes) continue
                add(Hop(profileId, profileName, tag, type))
            }
        }
    }

    private fun prepareGroupHop(outs: JSONArray, tag: String, extraExclude: Set<String>, tagPrefix: String): String {
        val original = find(outs, tag) ?: error("outbound 不存在：$tag")
        val type = original.optString("type")
        require(type !in forbiddenTypes) { "不能使用 $type 作为链式跳板：$tag" }
        if (type !in groupTypes) {
            require(original.optString("detour").isBlank()) { "outbound 含 detour，无法安全嵌入 Chain：$tag" }
            return tag
        }
        val members = original.optJSONArray("outbounds") ?: error("分组没有 outbounds：$tag")
        val mapped = JSONArray()
        for (i in 0 until members.length()) {
            val member = members.optString(i).trim()
            if (member.isEmpty() || member in forbiddenTags || member in extraExclude) continue
            val child = find(outs, member)
            if (child != null && child.optString("type") in forbiddenTypes) continue
            mapped.put(member)
        }
        require(mapped.length() > 0) { "分组过滤 DIRECT/落地后没有可用代理：$tag。请另选入口或落地。" }
        val needsClone = mapped.length() != members.length()
        if (!needsClone) return tag
        val newTag = tagPrefix + tag
        val clone = JSONObject(original.toString()).put("tag", newTag).put("outbounds", mapped)
        if (clone.has("default")) {
            val d = clone.optString("default")
            if (d.isBlank() || d in forbiddenTags || d in extraExclude) clone.remove("default")
        }
        removeOutbound(outs, newTag)
        outs.put(clone)
        return newTag
    }

    private fun isForbiddenHop(outs: JSONArray, o: JSONObject, tag: String): Boolean {
        val type = o.optString("type")
        if (type in forbiddenTypes || tag in forbiddenTags) return true
        if (type !in groupTypes) return false
        val members = o.optJSONArray("outbounds") ?: return true
        var usable = 0
        for (i in 0 until members.length()) {
            val member = members.optString(i).trim()
            if (member.isEmpty() || member in forbiddenTags) continue
            val child = find(outs, member)
            if (child != null && child.optString("type") in forbiddenTypes) continue
            usable++
        }
        return usable == 0
    }

    private fun isGeneratedTag(tag: String): Boolean =
        tag == LEGACY_CHAIN_TAG ||
            tag.startsWith(GENERATED_PREFIX) ||
            tag.startsWith(LANDING_PREFIX) ||
            tag.startsWith(ENTRY_PREFIX) ||
            tag.startsWith(LEGACY_PREFIX)

    private fun cleanGeneratedOutbounds(source: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until source.length()) {
            val o = source.optJSONObject(i) ?: continue
            val tag = o.optString("tag")
            if (isGeneratedTag(tag)) continue
            if (o.optString("detour").startsWith(LEGACY_PREFIX)) o.remove("detour")
            out.put(o)
        }
        return out
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
            require(type !in setOf("direct", "block", "dns")) { "落地不能使用 $type：$tag" }
            require(original.optString("detour").isBlank()) { "落地 outbound 含 detour，无法安全嵌入 Chain：$tag" }

            val newTag = "$LANDING_PREFIX$profileId-$tag"
            merged[tag] = newTag
            val clone = JSONObject(original.toString()).put("tag", newTag)
            if (type in groupTypes) {
                val members = original.optJSONArray("outbounds") ?: error("落地分组没有 outbounds：$tag")
                val mapped = JSONArray()
                for (i in 0 until members.length()) {
                    val member = members.optString(i)
                    if (member in forbiddenTags) continue
                    val child = find(src, member)
                    if (child != null && child.optString("type") in forbiddenTypes) continue
                    mapped.put(merge(member))
                }
                require(mapped.length() > 0) { "落地分组过滤后没有可用代理：$tag" }
                clone.put("outbounds", mapped)
                if (clone.has("default")) {
                    val d = clone.optString("default")
                    if (d.isNotBlank() && d !in forbiddenTags) clone.put("default", merge(d))
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
