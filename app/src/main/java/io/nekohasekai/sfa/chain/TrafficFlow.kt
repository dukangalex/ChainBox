package io.nekohasekai.sfa.chain

data class FlowSample(
    val source: String = "",
    val rule: String = "",
    val outbound: String = "",
    val chain: List<String> = emptyList(),
    val dest: String = "",
)

data class FlowNode(
    val id: String,
    val label: String,
    val column: Int,
    val weight: Int = 1,
)

data class FlowLink(
    val fromId: String,
    val toId: String,
    val weight: Int = 1,
)

data class PlacedNode(
    val node: FlowNode,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
)

data class PlacedRibbon(
    val columnFrom: Int,
    val columnTo: Int,
    val x0: Float,
    val x1: Float,
    val y0Top: Float,
    val y0Bottom: Float,
    val y1Top: Float,
    val y1Bottom: Float,
)

/**
 * Builds a left-to-right radiating path:
 * source → matching rule → hop(s) → destination.
 */
object TrafficFlowBuilder {
    private const val MAX_PER_COLUMN = 8

    fun build(
        samples: List<FlowSample>,
        path: ChainPath,
        hops: List<LiveHop> = emptyList(),
        chained: Boolean = path.chained,
        destinations: List<String> = emptyList(),
        running: Boolean = false,
    ): Pair<List<FlowNode>, List<FlowLink>> {
        if (samples.isNotEmpty()) {
            return fromSamples(samples, chained)
        }
        return fromHops(path, hops, chained, destinations, running)
    }

    private fun fromSamples(samples: List<FlowSample>, chained: Boolean): Pair<List<FlowNode>, List<FlowLink>> {
        val counts = LinkedHashMap<String, Int>()
        val links = LinkedHashMap<Pair<String, String>, Int>()
        fun bump(id: String, n: Int = 1) {
            counts[id] = (counts[id] ?: 0) + n
        }
        fun link(a: String, b: String, n: Int = 1) {
            if (a == b) return
            val key = a to b
            links[key] = (links[key] ?: 0) + n
        }
        samples.forEach { sample ->
            val src = idOf(0, prettySource(sample.source))
            val rule = idOf(1, prettyRule(sample.rule))
            val hopTags = sample.chain
                .map { ChainRuntimeCompiler.displayHopTag(it) }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            val hopIds = if (chained && hopTags.size >= 2) {
                listOf(idOf(2, hopTags.first()), idOf(3, hopTags.last()))
            } else {
                val leaf = hopTags.lastOrNull()
                    ?: ChainRuntimeCompiler.displayHopTag(sample.outbound).ifBlank { sample.outbound }
                listOf(idOf(2, leaf.ifBlank { "proxy" }))
            }
            val destCol = if (chained && hopTags.size >= 2) 4 else 3
            val dest = idOf(destCol, prettyDest(sample.dest))
            bump(src)
            bump(rule)
            hopIds.forEach { bump(it) }
            bump(dest)
            link(src, rule)
            var prev = rule
            hopIds.forEach { hop ->
                link(prev, hop)
                prev = hop
            }
            link(prev, dest)
        }
        return finish(counts, links)
    }

    private fun fromHops(
        path: ChainPath,
        hops: List<LiveHop>,
        chained: Boolean,
        destinations: List<String>,
        running: Boolean,
    ): Pair<List<FlowNode>, List<FlowLink>> {
        val labels = mutableListOf<String>()
        labels += "Device"
        labels += "<final>"
        val live = hops.filter {
            it.role != ChainPathHop.Role.Device && it.role != ChainPathHop.Role.Destination
        }
        if (live.isNotEmpty()) {
            live.forEach { hop ->
                labels += hop.title.ifBlank { hop.subtitle }.ifBlank {
                    when (hop.role) {
                        ChainPathHop.Role.Entry -> path.entryTag
                        ChainPathHop.Role.Landing -> path.landingTag
                        ChainPathHop.Role.Exit -> path.profileName
                        else -> "proxy"
                    }.ifBlank { "proxy" }
                }
            }
        } else if (chained) {
            labels += path.entryTag.ifBlank { "entry" }
            labels += path.landingTag.ifBlank { "landing" }
        } else {
            labels += path.hops.firstOrNull { it.role == ChainPathHop.Role.Exit }?.label
                ?.ifBlank { path.profileName }
                .orEmpty()
                .ifBlank { "proxy" }
        }
        val dest = when {
            destinations.isNotEmpty() -> destinations.first()
            running -> "waiting"
            else -> "Destination"
        }
        labels += dest
        val counts = LinkedHashMap<String, Int>()
        val links = LinkedHashMap<Pair<String, String>, Int>()
        val ids = labels.mapIndexed { index, label -> idOf(index, label) }
        ids.forEach { counts[it] = 1 }
        for (i in 0 until ids.size - 1) {
            links[ids[i] to ids[i + 1]] = 1
        }
        return finish(counts, links)
    }

    private fun finish(
        counts: Map<String, Int>,
        links: Map<Pair<String, String>, Int>,
    ): Pair<List<FlowNode>, List<FlowLink>> {
        val byCol = counts.entries.groupBy { columnOf(it.key) }
        val kept = mutableSetOf<String>()
        val overflowOf = HashMap<Int, String>()
        val nodes = mutableListOf<FlowNode>()
        byCol.keys.sorted().forEach { col ->
            val ranked = byCol[col].orEmpty().sortedByDescending { it.value }
            val head = ranked.take(MAX_PER_COLUMN)
            head.forEach { (id, weight) ->
                kept.add(id)
                nodes += FlowNode(id = id, label = labelOf(id), column = col, weight = weight)
            }
            val rest = ranked.drop(MAX_PER_COLUMN)
            if (rest.isNotEmpty()) {
                val extra = rest.sumOf { it.value }
                val id = idOf(col, "+${rest.size}")
                kept.add(id)
                overflowOf[col] = id
                nodes += FlowNode(id = id, label = "+${rest.size}", column = col, weight = extra)
            }
        }
        val flowLinks = links.mapNotNull { (pair, weight) ->
            val from = if (pair.first in kept) pair.first else overflowOf[columnOf(pair.first)]
            val to = if (pair.second in kept) pair.second else overflowOf[columnOf(pair.second)]
            if (from == null || to == null || from !in kept || to !in kept) return@mapNotNull null
            FlowLink(from, to, weight)
        }
        val merged = LinkedHashMap<Pair<String, String>, Int>()
        flowLinks.forEach { link ->
            val key = link.fromId to link.toId
            merged[key] = (merged[key] ?: 0) + link.weight
        }
        return nodes to merged.map { (key, weight) -> FlowLink(key.first, key.second, weight) }
    }

    internal fun prettySource(raw: String): String {
        val value = raw.trim().ifBlank { "<unknown>" }
        val host = when {
            value.startsWith("[") -> value.substringBefore(']').removePrefix("[")
            value.count { it == ':' } == 1 -> value.substringBefore(':')
            else -> value
        }
        return host.ifBlank { "<unknown>" }
    }

    internal fun prettyRule(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return "<final>"
        val lower = value.lowercase()
        val tag = value.substringAfterLast(':').substringAfterLast('/').trim().ifBlank { value }
        val short = tag
            .removePrefix("geosite-")
            .removePrefix("geoip-")
            .removePrefix("category-")
        return when {
            "geosite" in lower || "geoip" in lower || "rule_set" in lower || "ruleset" in lower ->
                "RuleSet: $short"
            "domain_suffix" in lower || "domainsuffix" in lower -> "DomainSuffix: $short"
            "domain_keyword" in lower -> "Keyword: $short"
            "ip_cidr" in lower || "ipcidr" in lower -> "IP: $short"
            else -> short.take(22)
        }
    }

    internal fun prettyDest(raw: String): String {
        val value = raw.trim().ifBlank { "<unknown>" }
        return if (value.length <= 24) value else value.take(21) + "…"
    }

    private fun idOf(column: Int, label: String): String = "$column|$label"
    private fun columnOf(id: String): Int = id.substringBefore('|').toIntOrNull() ?: 0
    private fun labelOf(id: String): String = id.substringAfter('|', id)
}

object SankeyLayout {
    fun layout(
        nodes: List<FlowNode>,
        links: List<FlowLink>,
        width: Float,
        height: Float,
        nodeWidth: Float,
        pad: Float,
    ): Pair<List<PlacedNode>, List<PlacedRibbon>> {
        if (nodes.isEmpty() || width <= 0f || height <= 0f) {
            return emptyList<PlacedNode>() to emptyList()
        }
        val columns = nodes.groupBy { it.column }.toSortedMap()
        val colKeys = columns.keys.toList()
        val nCols = colKeys.size.coerceAtLeast(1)
        val lastCol = colKeys.last()
        val shares = colKeys.map { col ->
            when {
                col == 0 -> 0.78f
                col == 1 && lastCol >= 3 -> 1.26f
                col == lastCol -> 1.20f
                else -> 1f
            }
        }
        val shareSum = shares.sum().coerceAtLeast(0.01f)
        val gapX = if (nCols <= 1) {
            0f
        } else {
            ((width - 2f * pad) * 0.045f).coerceIn(8f, 16f)
        }
        val budget = (width - 2f * pad - gapX * (nCols - 1)).coerceAtLeast(nodeWidth)
        val widths = shares.map { share -> (share / shareSum * budget).coerceAtLeast(nodeWidth * 0.55f) }
        val placed = ArrayList<PlacedNode>(nodes.size)
        val byId = HashMap<String, PlacedNode>(nodes.size)
        var x = pad
        columns.entries.forEachIndexed { index, (_, colNodes) ->
            val w = widths.getOrElse(index) { nodeWidth }
            val total = colNodes.sumOf { it.weight }.coerceAtLeast(1)
            val gapY = 4f
            val n = colNodes.size
            val usable = (height - 2f * pad - gapY * (n - 1).coerceAtLeast(0)).coerceAtLeast(16f)
            val minH = 16f
            val raw = colNodes.map { (usable * it.weight / total).coerceAtLeast(minH) }
            val overflow = raw.sum() - usable
            val heights = if (overflow > 0f) {
                val shrinkable = raw.map { (it - minH).coerceAtLeast(0f) }
                val shrinkSum = shrinkable.sum()
                if (shrinkSum > 0f) {
                    raw.mapIndexed { i, h -> h - overflow * shrinkable[i] / shrinkSum }
                } else {
                    List(n) { usable / n }
                }
            } else {
                raw
            }
            var y = pad
            colNodes.forEachIndexed { i, node ->
                val h = heights[i]
                val item = PlacedNode(node, x, y, w, h)
                placed += item
                byId[node.id] = item
                y += h + gapY
            }
            x += w + gapX
        }
        val outgoing = links.groupBy { it.fromId }
        val incoming = links.groupBy { it.toId }
        val outCursor = HashMap<String, Float>()
        val inCursor = HashMap<String, Float>()
        val ribbons = ArrayList<PlacedRibbon>(links.size)
        links.forEach { link ->
            val from = byId[link.fromId] ?: return@forEach
            val to = byId[link.toId] ?: return@forEach
            val fromTotal = outgoing[from.node.id]?.sumOf { it.weight }?.coerceAtLeast(1) ?: 1
            val toTotal = incoming[to.node.id]?.sumOf { it.weight }?.coerceAtLeast(1) ?: 1
            val fh = (from.h * link.weight / fromTotal).coerceAtLeast(2f)
            val th = (to.h * link.weight / toTotal).coerceAtLeast(2f)
            val fy = outCursor.getOrPut(from.node.id) { from.y }
            val ty = inCursor.getOrPut(to.node.id) { to.y }
            ribbons += PlacedRibbon(
                columnFrom = from.node.column,
                columnTo = to.node.column,
                x0 = from.x + from.w,
                x1 = to.x,
                y0Top = fy,
                y0Bottom = fy + fh,
                y1Top = ty,
                y1Bottom = ty + th,
            )
            outCursor[from.node.id] = fy + fh
            inCursor[to.node.id] = ty + th
        }
        return placed to ribbons
    }
}
