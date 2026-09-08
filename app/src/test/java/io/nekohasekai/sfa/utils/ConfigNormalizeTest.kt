package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigNormalizeTest {

    @Test
    fun inboundsHaveNoLegacySniffFields() {
        val inbounds = ConfigNormalize.buildInbounds()
        assertEquals(2, inbounds.length())
        for (i in 0 until inbounds.length()) {
            val ib = inbounds.getJSONObject(i)
            assertFalse(ib.has("sniff"))
            assertFalse(ib.has("sniff_override_destination"))
            assertFalse(ib.has("domain_strategy"))
        }
        assertEquals("tun", inbounds.getJSONObject(0).getString("type"))
        assertEquals("mixed", inbounds.getJSONObject(1).getString("type"))
    }

    @Test
    fun applyUsesRouteSniffAndLocalDnsWithoutGithub() {
        val src = JSONObject()
            .put(
                "outbounds",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "vless")
                            .put("tag", "n1")
                            .put("server", "node.example.com")
                            .put("server_port", 443),
                    )
                    .put(
                        JSONObject()
                            .put("type", "shadowsocks")
                            .put("tag", "ss1")
                            .put("server", "ss.example.com")
                            .put("server_port", 8388)
                            .put("plugin", "obfs-local")
                            .put(
                                "plugin_opts",
                                JSONObject().put("mode", "tls").put("host", "www.bing.com"),
                            ),
                    ),
            )
            .put(
                "inbounds",
                JSONArray().put(
                    JSONObject()
                        .put("type", "mixed")
                        .put("tag", "mixed-old")
                        .put("sniff", true)
                        .put("sniff_override_destination", true),
                ),
            )
        val out = JSONObject(ConfigNormalize.apply(src.toString()))
        val inbounds = out.getJSONArray("inbounds")
        for (i in 0 until inbounds.length()) {
            assertFalse(inbounds.getJSONObject(i).has("sniff"))
        }
        val rules = out.getJSONObject("route").getJSONArray("rules")
        assertEquals("sniff", rules.getJSONObject(0).getString("action"))
        assertFalse(out.getJSONObject("route").has("rule_set"))
        val dns0 = out.getJSONObject("dns").getJSONArray("servers").getJSONObject(0)
        assertEquals("https", dns0.getString("type"))
        assertFalse(dns0.has("address"))
        assertTrue(dns0.has("detour"))
        assertFalse(dns0.getString("detour").equals("direct", ignoreCase = true))
        val dnsLocal = out.getJSONObject("dns").getJSONArray("servers").getJSONObject(1)
        assertEquals("dns-local", dnsLocal.getString("tag"))
        assertEquals("udp", dnsLocal.getString("type"))
        assertFalse(dnsLocal.has("detour"))
        val ss = (0 until out.getJSONArray("outbounds").length())
            .map { out.getJSONArray("outbounds").getJSONObject(it) }
            .first { it.optString("tag") == "ss1" }
        assertTrue(ss.get("plugin_opts") is String)
        assertTrue(ss.getString("plugin_opts").contains("obfs=tls"))
        val dnsRules = out.getJSONObject("dns").getJSONArray("rules")
        val firstDnsRule = dnsRules.getJSONObject(0).getJSONArray("domain")
        assertTrue((0 until firstDnsRule.length()).any { firstDnsRule.getString(it) == "node.example.com" })
        var sawReject = false
        for (i in 0 until rules.length()) {
            val r = rules.getJSONObject(i)
            if (r.optString("action") == "reject" && r.optInt("port") == 3478) sawReject = true
        }
        assertTrue(sawReject)
        assertTrue(out.getJSONArray("outbounds").length() >= 3)
    }
}
