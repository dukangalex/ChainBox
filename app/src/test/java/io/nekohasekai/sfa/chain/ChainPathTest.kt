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
        assertTrue(topology.flowNodes.isNotEmpty())
        assertTrue(topology.flowLinks.isNotEmpty())
    }

    @Test
    fun trafficFlowUsesRulesAndSources() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = "节点选择",
            binding = ChainBinding(1L, "节点选择", 2L, "zgo"),
            landingProfileName = "VPS",
        )
        val (nodes, links) = TrafficFlowBuilder.build(
            samples = listOf(
                FlowSample(
                    source = "172.19.0.1:43210",
                    rule = "geosite-google",
                    outbound = "hk-1",
                    chain = listOf("hk-1", "us-9"),
                    dest = "hbjsjpcl.cloudflareaccess.com:443",
                ),
                FlowSample(
                    source = "172.19.0.1:43211",
                    rule = "geosite-telegram",
                    outbound = "hk-1",
                    chain = listOf("hk-1", "us-9"),
                    dest = "api.telegram.org:443",
                ),
            ),
            path = path,
            chained = true,
        )
        val labels = nodes.map { it.label }
        assertTrue(labels.any { it.contains("172.19.0.1") })
        assertTrue(labels.contains("google"))
        assertTrue(labels.contains("telegram"))
        assertTrue(labels.contains("hk-1"))
        assertTrue(labels.contains("us-9"))
        assertTrue(links.isNotEmpty())
        val (placed, ribbons) = SankeyLayout.layout(nodes, links, 400f, 200f, 64f, 4f)
        assertEquals(nodes.size, placed.size)
        assertTrue(ribbons.isNotEmpty())
    }

    @Test
    fun prettyRuleParsesRuleSetAssignment() {
        assertEquals("google", TrafficFlowBuilder.prettyRule("""rule_set=["geosite-google"]"""))
        assertEquals("github", TrafficFlowBuilder.prettyRule("rule_set=geosite-github"))
        assertEquals("cn", TrafficFlowBuilder.prettyRule("geoip-cn"))
        assertEquals("<final>", TrafficFlowBuilder.prettyRule(""))
    }

    @Test
    fun generatedChainTagIsHidden() {
        val path = ChainPathBuilder.build(
            profileName = "UOT",
            defaultOutboundTag = "节点选择",
            binding = ChainBinding(1L, "节点选择", 2L, "zgo"),
            landingProfileName = "VPS",
        )
        val (nodes, _) = TrafficFlowBuilder.build(
            samples = listOf(
                FlowSample(
                    source = "172.19.0.1:1",
                    rule = """rule_set=["geosite-google"]""",
                    outbound = "chainbox-chain-7-6",
                    chain = listOf("chainbox-chain-7-6"),
                    dest = "www.google.com:443",
                ),
            ),
            path = path,
            chained = true,
        )
        assertTrue(nodes.none { it.label.contains("chainbox", ignoreCase = true) })
        assertTrue(nodes.any { it.label == "节点选择" })
        assertTrue(nodes.any { it.label == "zgo" })
        assertTrue(nodes.any { it.label == "google" })
        assertTrue(nodes.any { it.label == "www.google.com" })
    }

    @Test
    fun directHopsUseDistinctFlag() {
        val path = ChainPath.regular(profileName = "UOT", exitTag = "节点选择")
        val (nodes, links) = TrafficFlowBuilder.build(
            samples = listOf(
                FlowSample(
                    source = "172.19.0.1:2",
                    rule = "geoip-cn",
                    outbound = "DIRECT",
                    chain = listOf("DIRECT"),
                    dest = "www.baidu.com:443",
                ),
            ),
            path = path,
            chained = false,
        )
        assertTrue(nodes.any { it.label == "DIRECT" && it.direct })
        assertTrue(links.any { it.direct })
        assertTrue(nodes.any { it.label == "cn" })
    }

    @Test
    fun trafficFlowOverflowKeepsLinks() {
        val path = ChainPath.regular(profileName = "UOT", exitTag = "节点选择")
        val samples = (0 until 12).map { i ->
            FlowSample(
                source = "172.19.0.1:40$i",
                rule = "geosite-site-$i",
                outbound = "hk-1",
                chain = listOf("hk-1"),
                dest = "host$i.example.com:443",
            )
        }
        val (nodes, links) = TrafficFlowBuilder.build(samples, path, chained = false)
        assertTrue(nodes.any { it.label.startsWith("+") })
        assertTrue(links.isNotEmpty())
        val ids = nodes.map { it.id }.toSet()
        assertTrue(links.all { it.fromId in ids && it.toId in ids })
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
