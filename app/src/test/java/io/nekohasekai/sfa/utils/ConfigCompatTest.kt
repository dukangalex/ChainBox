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
}
