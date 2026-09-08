package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigNormalizeTest {

    @Test
    fun webrtcRejectsStunPorts() {
        val rules = ConfigNormalize.webrtcRejectRules()
        val ports = (0 until rules.length()).map { rules.getJSONObject(it).optInt("port") }.toSet()
        assertEquals(setOf(3478, 19302, 5349), ports)
        for (i in 0 until rules.length()) {
            val r = rules.getJSONObject(i)
            assertEquals("udp", r.getString("network"))
            assertEquals("reject", r.getString("action"))
        }
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
