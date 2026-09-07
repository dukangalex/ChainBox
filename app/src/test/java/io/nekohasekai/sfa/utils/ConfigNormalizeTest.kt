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
    fun applyUsesRouteSniffActionInsteadOfInboundSniff() {
        val src = JSONObject()
            .put(
                "outbounds",
                JSONArray().put(
                    JSONObject()
                        .put("type", "vless")
                        .put("tag", "n1")
                        .put("server", "1.1.1.1")
                        .put("server_port", 443),
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
        val dns0 = out.getJSONObject("dns").getJSONArray("servers").getJSONObject(0)
        assertEquals("https", dns0.getString("type"))
        assertFalse(dns0.has("address"))
        assertTrue(out.getJSONArray("outbounds").length() >= 3)
    }
}
