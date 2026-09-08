package io.nekohasekai.sfa.chain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainRuntimeCompilerTest {

    private fun profile(finalTag: String = "漏网之鱼"): String {
        return JSONObject()
            .put(
                "outbounds",
                JSONArray()
                    .put(node("hk-1"))
                    .put(node("jp-1"))
                    .put(
                        JSONObject()
                            .put("type", "selector")
                            .put("tag", "节点选择")
                            .put("outbounds", JSONArray().put("hk-1").put("jp-1")),
                    )
                    .put(
                        JSONObject()
                            .put("type", "selector")
                            .put("tag", "漏网之鱼")
                            .put("outbounds", JSONArray().put("节点选择").put("direct")),
                    )
                    .put(JSONObject().put("type", "direct").put("tag", "direct")),
            )
            .put("route", JSONObject().put("final", finalTag))
            .toString()
    }

    private fun node(tag: String) = JSONObject()
        .put("type", "vless")
        .put("tag", tag)
        .put("server", "example.com")
        .put("server_port", 443)

    @Test
    fun resolveMainTagSkipsFinalLikeRoute() {
        val outs = JSONObject(profile()).getJSONArray("outbounds")
        val tag = ChainRuntimeCompiler.resolveMainTag(outs, "漏网之鱼")
        assertEquals("节点选择", tag)
    }

    @Test
    fun sameProfileChainStripsDirectAndStarts() {
        val compiled = ChainRuntimeCompiler.apply(
            ChainRuntimeCompiler.ApplyRequest(
                content = profile(),
                currentProfileId = 1L,
                entryTag = "漏网之鱼",
                landingProfileId = 1L,
                landingTag = "jp-1",
                landingContent = null,
            ),
        )
        val root = JSONObject(compiled)
        val outs = root.getJSONArray("outbounds")
        val chain = (0 until outs.length()).map { outs.getJSONObject(it) }
            .first { it.optString("type") == "chain" }
        assertEquals("chain", chain.optString("type"))
        assertFalse(chain.has("fail_closed"))
        val hops = chain.getJSONArray("outbounds")
        assertEquals(2, hops.length())
        val entryTag = hops.getString(0)
        val entry = (0 until outs.length()).map { outs.getJSONObject(it) }
            .first { it.optString("tag") == entryTag }
        val members = (0 until entry.getJSONArray("outbounds").length()).map {
            entry.getJSONArray("outbounds").getString(it)
        }
        assertFalse(members.contains("direct"))
        assertTrue(members.contains("节点选择"))
        assertEquals("jp-1", hops.getString(1))
        assertTrue(root.getJSONObject("route").getString("final").startsWith("chainbox-chain-"))
    }

    @Test
    fun autoEntryDoesNotLockToFinalGroup() {
        val outs = JSONObject(profile()).getJSONArray("outbounds")
        assertNotEquals("漏网之鱼", ChainRuntimeCompiler.resolveMainTag(outs, "漏网之鱼"))
        assertTrue(ChainRuntimeCompiler.isFinalLike("漏网之鱼"))
        assertTrue(ChainRuntimeCompiler.isFinalLike("final"))
    }

    @Test
    fun crossProfileLandingKeepsSeparateTags() {
        val landing = JSONObject()
            .put(
                "outbounds",
                JSONArray()
                    .put(node("zgo-node"))
                    .put(
                        JSONObject()
                            .put("type", "urltest")
                            .put("tag", "zgo")
                            .put("outbounds", JSONArray().put("zgo-node")),
                    ),
            )
            .toString()
        val compiled = ChainRuntimeCompiler.apply(
            ChainRuntimeCompiler.ApplyRequest(
                content = profile("节点选择"),
                currentProfileId = 11L,
                entryTag = "节点选择",
                landingProfileId = 99L,
                landingTag = "zgo",
                landingContent = landing,
            ),
        )
        val root = JSONObject(compiled)
        val outs = root.getJSONArray("outbounds")
        val tags = (0 until outs.length()).map { outs.getJSONObject(it).optString("tag") }
        assertTrue(tags.any { it.startsWith("chainbox-landing-99-") })
        val chain = (0 until outs.length()).map { outs.getJSONObject(it) }
            .first { it.optString("type") == "chain" }
        val hops = chain.getJSONArray("outbounds")
        assertEquals("节点选择", hops.getString(0))
        assertEquals("chainbox-landing-99-zgo", hops.getString(1))
    }
}
