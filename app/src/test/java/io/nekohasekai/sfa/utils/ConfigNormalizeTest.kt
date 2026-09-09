package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigNormalizeTest {

    @Test
    fun webrtcRejectsStunPortsAndHostnames() {
        val rules = ConfigNormalize.webrtcRejectRules()
        assertEquals(3, rules.length())
        val udp = rules.getJSONObject(0)
        assertEquals("udp", udp.getString("network"))
        assertEquals("reject", udp.getString("action"))
        val udpPorts = (0 until udp.getJSONArray("port").length())
            .map { udp.getJSONArray("port").getInt(it) }
            .toSet()
        assertTrue(udpPorts.containsAll(setOf(3478, 19302, 5349)))
        val tcp = rules.getJSONObject(1)
        assertEquals("tcp", tcp.getString("network"))
        assertEquals("reject", tcp.getString("action"))
        val keywords = rules.getJSONObject(2).getJSONArray("domain_keyword")
        val keys = (0 until keywords.length()).map { keywords.getString(it) }.toSet()
        assertTrue(keys.contains("stun."))
        assertTrue(keys.contains("turn."))
    }

    @Test
    fun cnDomainListCoversCommonSuffixes() {
        val arr = ConfigNormalize.cnDomainSuffixArray()
        val values = (0 until arr.length()).map { arr.getString(it) }.toSet()
        assertTrue(values.contains("cn"))
        assertTrue(values.contains("qq.com"))
        assertTrue(values.contains("bilibili.com"))
        assertEquals(ConfigNormalize.CN_DOMAIN_SUFFIXES.size, arr.length())
    }
}
