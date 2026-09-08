package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigChinaDirectTest {

    @Test
    fun packsLanChinaDnsAndDomainRules() {
        val rules = ConfigChinaDirect.chinaRouteRules("direct", emptySet())
        val text = rules.toString()
        assertTrue(text.contains("ip_is_private"))
        assertTrue(text.contains("114.114.114.114/32"))
        assertTrue(text.contains("dns.alidns.com"))
        assertTrue(text.contains("qq.com"))
        assertTrue(text.contains("local"))
        assertFalse(text.contains("rule_set"))
    }

    @Test
    fun reusesExistingGeoipRuleSet() {
        val rules = ConfigChinaDirect.chinaRouteRules("direct", setOf("geoip-cn", "geosite-cn"))
        val text = rules.toString()
        assertTrue(text.contains("geoip-cn"))
        assertTrue(text.contains("geosite-cn"))
    }

    @Test
    fun createsDirectOutboundWhenMissing() {
        val root = JSONObject().put(
            "outbounds",
            JSONArray().put(JSONObject().put("type", "vless").put("tag", "node")),
        )
        ConfigChinaDirect.apply(root)
        val outs = root.getJSONArray("outbounds")
        val tags = (0 until outs.length()).map { outs.getJSONObject(it).optString("tag") }
        assertTrue(tags.contains("direct"))
        val routeRules = root.getJSONObject("route").getJSONArray("rules")
        assertTrue(routeRules.length() >= 5)
        val dns = root.getJSONObject("dns")
        assertEquals(ConfigChinaDirect.CN_DNS_TAG, dns.getJSONArray("rules").getJSONObject(0).getString("server"))
    }

    @Test
    fun unblocksHttpsDnsReject() {
        val dns = JSONObject().put(
            "rules",
            JSONArray()
                .put(JSONObject().put("query_type", JSONArray().put("HTTPS").put("SVCB")).put("action", "reject"))
                .put(JSONObject().put("domain_suffix", "cn").put("server", "local")),
        )
        val removed = ConfigChinaDirect.unblockHttpsQueries(dns)
        assertEquals(1, removed)
        assertEquals(1, dns.getJSONArray("rules").length())
    }

    @Test
    fun keepsMixedHttpsRuleWithDomains() {
        val dns = JSONObject().put(
            "rules",
            JSONArray().put(
                JSONObject()
                    .put("query_type", "HTTPS")
                    .put("domain", "example.com")
                    .put("action", "reject"),
            ),
        )
        assertEquals(0, ConfigChinaDirect.unblockHttpsQueries(dns))
        assertEquals(1, dns.getJSONArray("rules").length())
    }

    @Test
    fun unblocksHttpsRejectEvenWithClashMode() {
        val dns = JSONObject().put(
            "rules",
            JSONArray().put(
                JSONObject()
                    .put("query_type", JSONArray().put("HTTPS").put("SVCB"))
                    .put("action", "reject")
                    .put("clash_mode", "Rule"),
            ),
        )
        assertEquals(1, ConfigChinaDirect.unblockHttpsQueries(dns))
        assertEquals(0, dns.getJSONArray("rules").length())
    }

    @Test
    fun applyEchDnsInjectsDoHAndKeepsTlsEch() {
        val root = JSONObject()
            .put(
                "dns",
                JSONObject().put(
                    "rules",
                    JSONArray().put(
                        JSONObject().put("query_type", JSONArray().put("HTTPS")).put("action", "reject"),
                    ),
                ),
            )
            .put(
                "outbounds",
                JSONArray().put(
                    JSONObject()
                        .put("type", "vless")
                        .put("tag", "n")
                        .put("tls", JSONObject().put("ech", JSONObject().put("enabled", true))),
                ),
            )
        assertTrue(ConfigChinaDirect.applyEchDns(root))
        val dns = root.getJSONObject("dns")
        val first = dns.getJSONArray("rules").getJSONObject(0)
        assertEquals(ConfigChinaDirect.ECH_DNS_TAG, first.getString("server"))
        val servers = dns.getJSONArray("servers")
        val tags = (0 until servers.length()).map { servers.getJSONObject(it).optString("tag") }
        assertTrue(tags.contains(ConfigChinaDirect.ECH_DNS_TAG))
        assertEquals(
            true,
            root.getJSONArray("outbounds").getJSONObject(0).getJSONObject("tls").getJSONObject("ech").getBoolean("enabled"),
        )
    }
}
