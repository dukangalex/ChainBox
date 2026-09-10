package io.nekohasekai.sfa.chain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainPathTest {

    @Test
    fun regularPathUsesDefaultOutbound() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = "节点选择",
            binding = null,
            landingProfileName = null,
        )
        assertFalse(path.chained)
        assertEquals(3, path.hops.size)
        assertEquals(ChainPathHop.Role.Device, path.hops[0].role)
        assertEquals(ChainPathHop.Role.Exit, path.hops[1].role)
        assertEquals("节点选择", path.hops[1].label)
        assertEquals("UOT", path.hops[1].detail)
        assertEquals(ChainPathHop.Role.Destination, path.hops[2].role)
        assertTrue(path.hops.none { it.highlighted })
    }

    @Test
    fun regularPathFallsBackToProfileName() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = null,
            binding = null,
            landingProfileName = null,
        )
        assertEquals("UOT", path.hops[1].label)
        assertFalse(path.chained)
    }

    @Test
    fun chainedPathHighlightsEntryAndLanding() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = "节点选择",
            binding = ChainBinding(1L, "proxy-select", 2L, "zgo"),
            landingProfileName = "VPS",
        )
        assertTrue(path.chained)
        assertEquals(4, path.hops.size)
        assertEquals(ChainPathHop.Role.Entry, path.hops[1].role)
        assertEquals("proxy-select", path.hops[1].label)
        assertTrue(path.hops[1].highlighted)
        assertEquals(ChainPathHop.Role.Landing, path.hops[2].role)
        assertEquals("zgo", path.hops[2].label)
        assertEquals("VPS", path.hops[2].detail)
        assertTrue(path.hops[2].highlighted)
        assertFalse(path.hops[0].highlighted)
        assertFalse(path.hops.last().highlighted)
    }

    @Test
    fun sameProfileLandingKeepsCurrentProfileAsDetail() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = "节点选择",
            binding = ChainBinding(1L, "节点选择", 1L, "jp-1"),
            landingProfileName = "UOT",
        )
        assertEquals("UOT", path.hops[2].detail)
        assertEquals("jp-1", path.landingTag)
    }

    @Test
    fun blankEntryFallsBackToDefaultOutbound() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = "自动选择",
            binding = ChainBinding(1L, "", 2L, "land"),
            landingProfileName = "other",
        )
        assertEquals("自动选择", path.hops[1].label)
        assertTrue(path.chained)
    }
}
