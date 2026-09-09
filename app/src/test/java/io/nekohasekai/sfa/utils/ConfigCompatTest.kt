package io.nekohasekai.sfa.utils

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigCompatTest {
    @Test
    fun pluginOptsObjectBecomesString() {
        val src = JSONObject()
            .put(
                "outbounds",
                JSONArray().put(
                    JSONObject()
                        .put("type", "shadowsocks")
                        .put("tag", "ss")
                        .put("plugin", "obfs-local")
                        .put("plugin-opts", JSONObject().put("mode", "http").put("host", "download.windowsupdate.com")),
                ),
            )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        val ss = out.getJSONArray("outbounds").getJSONObject(0)
        val opts = ss.getString("plugin_opts")
        assertTrue(opts.contains("obfs=http"))
        assertTrue(opts.contains("obfs-host=download.windowsupdate.com"))
        assertEquals(false, ss.has("plugin-opts"))
    }

    @Test
    fun sanitizeKeepsTlsEch() {
        val ech = JSONObject().put("enabled", true).put("query_server_name", "cover.example.com")
        val src = JSONObject().put(
            "outbounds",
            JSONArray().put(
                JSONObject()
                    .put("type", "vless")
                    .put("tag", "n")
                    .put("tls", JSONObject().put("enabled", true).put("server_name", "example.com").put("ech", ech)),
            ),
        )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        val kept = out.getJSONArray("outbounds").getJSONObject(0).getJSONObject("tls").getJSONObject("ech")
        assertEquals(true, kept.getBoolean("enabled"))
        assertEquals("cover.example.com", kept.getString("query_server_name"))
    }

    @Test
    fun stripsDnsDetourToEmptyDirect() {
        val src = JSONObject()
            .put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put(
                "dns",
                JSONObject().put(
                    "servers",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "udp")
                            .put("tag", "alidns")
                            .put("server", "223.5.5.5")
                            .put("detour", "direct"),
                    ),
                ),
            )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        val server = out.getJSONObject("dns").getJSONArray("servers").getJSONObject(0)
        assertEquals(false, server.has("detour"))
        assertEquals("223.5.5.5", server.getString("server"))
    }

    @Test
    fun keepsDnsDetourToProxy() {
        val src = JSONObject()
            .put(
                "outbounds",
                JSONArray()
                    .put(JSONObject().put("type", "direct").put("tag", "direct"))
                    .put(JSONObject().put("type", "vless").put("tag", "proxy")),
            )
            .put(
                "dns",
                JSONObject().put(
                    "servers",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "https")
                            .put("tag", "remote")
                            .put("server", "8.8.8.8")
                            .put("detour", "proxy"),
                    ),
                ),
            )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        val server = out.getJSONObject("dns").getJSONArray("servers").getJSONObject(0)
        assertEquals("proxy", server.getString("detour"))
    }

    @Test
    fun stripsDnsDetourWhenOutboundMissing() {
        val src = JSONObject()
            .put("outbounds", JSONArray().put(JSONObject().put("type", "vless").put("tag", "node")))
            .put(
                "dns",
                JSONObject().put(
                    "servers",
                    JSONArray().put(
                        JSONObject().put("tag", "local").put("address", "223.5.5.5").put("detour", "direct"),
                    ),
                ),
            )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        assertEquals(false, out.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).has("detour"))
    }

    @Test
    fun keepsDetourToBoundDirect() {
        val src = JSONObject()
            .put(
                "outbounds",
                JSONArray().put(
                    JSONObject().put("type", "direct").put("tag", "wlan").put("bind_interface", "wlan0"),
                ),
            )
            .put(
                "dns",
                JSONObject().put(
                    "servers",
                    JSONArray().put(
                        JSONObject().put("type", "udp").put("tag", "local").put("server", "1.1.1.1")
                            .put("detour", "wlan"),
                    ),
                ),
            )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        assertEquals(
            "wlan",
            out.getJSONObject("dns").getJSONArray("servers").getJSONObject(0).getString("detour"),
        )
    }

    @Test
    fun migratesLegacyFakeipObject() {
        val src = JSONObject()
            .put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put(
                "dns",
                JSONObject()
                    .put(
                        "servers",
                        JSONArray()
                            .put(JSONObject().put("tag", "remote").put("address", "8.8.8.8"))
                            .put(JSONObject().put("tag", "fakeip").put("address", "fakeip")),
                    )
                    .put(
                        "rules",
                        JSONArray().put(
                            JSONObject().put("query_type", JSONArray().put("A").put("AAAA"))
                                .put("server", "fakeip"),
                        ),
                    )
                    .put(
                        "fakeip",
                        JSONObject().put("enabled", true)
                            .put("inet4_range", "198.18.0.0/15")
                            .put("inet6_range", "fc00::/18"),
                    ),
            )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        val dns = out.getJSONObject("dns")
        assertEquals(false, dns.has("fakeip"))
        val servers = dns.getJSONArray("servers")
        assertEquals("udp", servers.getJSONObject(0).getString("type"))
        assertEquals("8.8.8.8", servers.getJSONObject(0).getString("server"))
        val fake = servers.getJSONObject(1)
        assertEquals("fakeip", fake.getString("type"))
        assertEquals("fakeip", fake.getString("tag"))
        assertEquals("198.18.0.0/15", fake.getString("inet4_range"))
        assertEquals("fc00::/18", fake.getString("inet6_range"))
        assertEquals(false, fake.has("address"))
    }

    @Test
    fun migratesHttpsDoHAddress() {
        val src = JSONObject().put(
            "dns",
            JSONObject().put(
                "servers",
                JSONArray().put(
                    JSONObject()
                        .put("tag", "doh")
                        .put("address", "https://dns.google/dns-query")
                        .put("address_resolver", "bootstrap"),
                ),
            ),
        )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        val s = out.getJSONObject("dns").getJSONArray("servers").getJSONObject(0)
        assertEquals("https", s.getString("type"))
        assertEquals("dns.google", s.getString("server"))
        assertEquals("bootstrap", s.getString("domain_resolver"))
        assertEquals(false, s.has("address"))
        assertEquals(false, s.has("path"))
    }

    @Test
    fun injectsFakeipServerWhenOnlyTopLevelObjectExists() {
        val src = JSONObject().put(
            "dns",
            JSONObject()
                .put("servers", JSONArray().put(JSONObject().put("address", "1.1.1.1")))
                .put("fakeip", JSONObject().put("enabled", true).put("inet4_range", "198.18.0.0/15")),
        )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        val servers = out.getJSONObject("dns").getJSONArray("servers")
        assertEquals(2, servers.length())
        val fake = servers.getJSONObject(1)
        assertEquals("fakeip", fake.getString("type"))
        assertEquals("fakeip", fake.getString("tag"))
        val rule = out.getJSONObject("dns").getJSONArray("rules").getJSONObject(0)
        assertEquals("fakeip", rule.getString("server"))
    }

    @Test
    fun migratesTypedRcodeServerToRuleAction() {
        val src = JSONObject().put(
            "dns",
            JSONObject()
                .put(
                    "servers",
                    JSONArray()
                        .put(JSONObject().put("type", "udp").put("tag", "remote").put("server", "8.8.8.8"))
                        .put(
                            JSONObject()
                                .put("type", "rcode")
                                .put("tag", "dns-block")
                                .put("rcode", "success"),
                        ),
                )
                .put(
                    "rules",
                    JSONArray().put(
                        JSONObject()
                            .put("domain_suffix", JSONArray().put("ads.example"))
                            .put("server", "dns-block"),
                    ),
                ),
        )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        val dns = out.getJSONObject("dns")
        val servers = dns.getJSONArray("servers")
        assertEquals(1, servers.length())
        assertEquals("remote", servers.getJSONObject(0).getString("tag"))
        val rule = dns.getJSONArray("rules").getJSONObject(0)
        assertEquals("predefined", rule.getString("action"))
        assertEquals("NOERROR", rule.getString("rcode"))
        assertEquals(false, rule.has("server"))
    }

    @Test
    fun migratesLegacyRcodeAddress() {
        val src = JSONObject().put(
            "dns",
            JSONObject()
                .put(
                    "servers",
                    JSONArray()
                        .put(JSONObject().put("tag", "remote").put("address", "1.1.1.1"))
                        .put(JSONObject().put("tag", "block").put("address", "rcode://refused")),
                )
                .put("final", "block"),
        )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        val dns = out.getJSONObject("dns")
        assertEquals(1, dns.getJSONArray("servers").length())
        assertEquals("remote", dns.getString("final"))
        val last = dns.getJSONArray("rules").getJSONObject(dns.getJSONArray("rules").length() - 1)
        assertEquals("predefined", last.getString("action"))
        assertEquals("REFUSED", last.getString("rcode"))
    }

    @Test
    fun migratesPredefinedServerType() {
        val src = JSONObject().put(
            "dns",
            JSONObject().put(
                "servers",
                JSONArray()
                    .put(JSONObject().put("type", "https").put("tag", "doh").put("server", "dns.google"))
                    .put(
                        JSONObject()
                            .put("type", "predefined")
                            .put("tag", "nx")
                            .put("responses", JSONArray().put(JSONObject().put("rcode", "NXDOMAIN"))),
                    ),
            ).put(
                "rules",
                JSONArray().put(JSONObject().put("domain", JSONArray().put("blocked.test")).put("server", "nx")),
            ),
        )
        val out = JSONObject(ConfigCompat.sanitize(src.toString()))
        val servers = out.getJSONObject("dns").getJSONArray("servers")
        assertEquals(1, servers.length())
        assertEquals("doh", servers.getJSONObject(0).getString("tag"))
        val rule = out.getJSONObject("dns").getJSONArray("rules").getJSONObject(0)
        assertEquals("predefined", rule.getString("action"))
        assertEquals("NXDOMAIN", rule.getString("rcode"))
    }
}
