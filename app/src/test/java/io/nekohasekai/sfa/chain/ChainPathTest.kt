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

    @Test
    fun idleTopologyUsesPlannedHops() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = "节点选择",
            binding = ChainBinding(1L, "节点选择", 1L, "jp-1"),
            landingProfileName = "UOT",
        )
        val topology = LiveTopologyBuilder.fromPath(path, running = false)
        assertFalse(topology.running)
        assertTrue(topology.chained)
        assertEquals("节点选择", topology.hops[1].title)
        assertEquals("jp-1", topology.hops[2].title)
        assertFalse(topology.flowing)
    }

    @Test
    fun liveTopologyPrefersSelectedNodeOverGroupTag() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = "节点选择",
            binding = ChainBinding(1L, "节点选择", 1L, "自动选择"),
            landingProfileName = "UOT",
        )
        val topology = LiveTopologyBuilder.fromPath(
            path,
            running = true,
            groups = listOf(
                GroupHint("节点选择", selected = "hk-1", delays = mapOf("hk-1" to 42)),
                GroupHint("自动选择", selected = "jp-1", delays = mapOf("jp-1" to 88)),
            ),
        )
        assertTrue(topology.running)
        assertEquals("hk-1", topology.hops[1].title)
        assertEquals(42, topology.hops[1].delayMs)
        assertEquals("jp-1", topology.hops[2].title)
        assertEquals(88, topology.hops[2].delayMs)
    }

    @Test
    fun liveTopologyOverlaysConnectionChainAndDestinations() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = "节点选择",
            binding = ChainBinding(1L, "节点选择", 2L, "zgo"),
            landingProfileName = "VPS",
        )
        val topology = LiveTopologyBuilder.fromPath(
            path,
            running = true,
            groups = listOf(
                GroupHint("节点选择", selected = "hk-1"),
                GroupHint("zgo", selected = "us-1"),
            ),
            liveChain = listOf("chainbox-chain-1-2", "chainbox-entry-节点选择", "hk-2", "chainbox-landing-2-zgo", "us-9"),
            destinations = listOf("api.example.com:443", "1.1.1.1:443"),
            activeConnections = 4,
            flowing = true,
        )
        assertEquals("hk-2", topology.hops[1].title)
        assertEquals("us-9", topology.hops[2].title)
        assertEquals("api.example.com:443", topology.hops[3].title)
        assertEquals(4, topology.activeConnections)
        assertTrue(topology.flowing)
        assertEquals(listOf("api.example.com:443", "1.1.1.1:443"), topology.destinations)
    }

    @Test
    fun directModeShortCircuitsToDirectExit() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = "节点选择",
            binding = ChainBinding(1L, "节点选择", 1L, "jp-1"),
            landingProfileName = "UOT",
        )
        val topology = LiveTopologyBuilder.fromPath(
            path,
            running = true,
            mode = "direct",
            destinations = listOf("www.baidu.com"),
        )
        assertFalse(topology.chained)
        assertEquals("DIRECT", topology.hops[1].title)
        assertEquals("www.baidu.com", topology.hops[2].title)
    }

    @Test
    fun regularLivePathUsesSelectedExit() {
        val path = ChainPath.regular(profileName = "UOT", exitTag = "节点选择")
        val topology = LiveTopologyBuilder.fromPath(
            path,
            running = true,
            groups = listOf(GroupHint("节点选择", selected = "sg-1", delays = mapOf("sg-1" to 12))),
        )
        assertFalse(topology.chained)
        assertEquals("sg-1", topology.hops[1].title)
        assertEquals(12, topology.hops[1].delayMs)
    }
}
