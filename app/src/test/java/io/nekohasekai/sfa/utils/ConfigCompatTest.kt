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
}
