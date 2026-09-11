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
    val direct: Boolean = false,
)

data class FlowLink(
    val fromId: String,
    val toId: String,
    val weight: Int = 1,
    val direct: Boolean = false,
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
    val direct: Boolean = false,
)

/**
 * Left-to-right live path with at most four columns:
 * source → matching rule → hop(s) / exit.
 * Remote hosts are not a fifth column — they crowd the labels.
 */
object TrafficFlowBuilder {
    private const val MAX_PER_COLUMN = 8
    internal const val MAX_COLUMN = 3

    fun build(
        samples: List<FlowSample>,
        path: ChainPath,
        hops: List<LiveHop> = emptyList(),
        chained: Boolean = path.chained,
        destinations: List<String> = emptyList(),
        running: Boolean = false,
    ): Pair<List<FlowNode>, List<FlowLink>> {
        if (samples.isNotEmpty()) {
            return fromSamples(samples, chained, path)
        }
        return fromHops(path, hops, chained)
    }

    private fun fromSamples(
        samples: List<FlowSample>,
        chained: Boolean,
        path: ChainPath,
    ): Pair<List<FlowNode>, List<FlowLink>> {
        val counts = LinkedHashMap<String, Int>()
        val links = LinkedHashMap<Pair<String, String>, Int>()
        val directIds = HashSet<String>()
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
            val hopTags = hopLabelsFor(sample, chained, path)
            val hopIds = hopTags.mapIndexed { index, tag ->
                val col = if (chained && hopTags.size >= 2) 2 + index else 2
                val id = idOf(col.coerceAtMost(MAX_COLUMN), tag)
                if (isDirectTag(tag)) directIds.add(id)
                id
            }
            bump(src)
            bump(rule)
            hopIds.forEach { bump(it) }
            link(src, rule)
            var prev = rule
            hopIds.forEach { hop ->
                link(prev, hop)
                prev = hop
            }
        }
        return finish(counts, links, directIds)
    }

    private fun hopLabelsFor(sample: FlowSample, chained: Boolean, path: ChainPath): List<String> {
        val fromChain = sample.chain
            .map { prettyHop(it) }
            .filter { it.isNotEmpty() }
            .distinct()
        val leaf = prettyHop(sample.outbound)
        val tags = when {
            fromChain.isNotEmpty() -> fromChain
            leaf.isNotEmpty() -> listOf(leaf)
            else -> emptyList()
        }
        if (tags.size == 1 && isDirectTag(tags.first())) return listOf("DIRECT")
        if (chained) {
            val visible = tags.filter { !isDirectTag(it) }
            val entry = visible.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: path.entryTag.trim().takeIf { it.isNotEmpty() }
            val landing = (if (visible.size >= 2) visible.last() else null)?.takeIf { it.isNotBlank() }
                ?: path.landingTag.trim().takeIf { it.isNotEmpty() }
            if (!entry.isNullOrBlank() && !landing.isNullOrBlank() && entry != landing) {
                return listOf(entry, landing)
            }
            if (!entry.isNullOrBlank()) return listOf(entry)
            if (!landing.isNullOrBlank()) return listOf(landing)
        }
        return tags.ifEmpty { listOf("proxy") }
    }

    private fun fromHops(
        path: ChainPath,
        hops: List<LiveHop>,
        chained: Boolean,
    ): Pair<List<FlowNode>, List<FlowLink>> {
        val labels = mutableListOf<String>()
        val directFlags = mutableListOf<Boolean>()
        fun addLabel(label: String, direct: Boolean = isDirectTag(label)) {
            labels += label
            directFlags += direct
        }
        addLabel("Device", false)
        addLabel("<final>", false)
        val live = hops.filter {
            it.role != ChainPathHop.Role.Device && it.role != ChainPathHop.Role.Destination
        }
        if (live.isNotEmpty()) {
            live.forEach { hop ->
                val title = prettyHop(hop.title).ifBlank {
                    prettyHop(hop.subtitle)
                }.ifBlank {
                    val raw = when (hop.role) {
                        ChainPathHop.Role.Entry -> path.entryTag
                        ChainPathHop.Role.Landing -> path.landingTag
                        ChainPathHop.Role.Exit -> path.profileName
                        else -> "proxy"
                    }
                    prettyHop(raw).ifBlank { if (isDirectTag(raw)) "DIRECT" else "proxy" }
                }
                addLabel(title)
            }
        } else if (chained) {
            addLabel(path.entryTag.ifBlank { "entry" })
            addLabel(path.landingTag.ifBlank { "landing" })
        } else {
            addLabel(
                path.hops.firstOrNull { it.role == ChainPathHop.Role.Exit }?.label
                    ?.ifBlank { path.profileName }
                    .orEmpty()
                    .ifBlank { "proxy" },
            )
        }
        val counts = LinkedHashMap<String, Int>()
        val links = LinkedHashMap<Pair<String, String>, Int>()
        val directIds = HashSet<String>()
        val ids = labels.mapIndexed { index, label ->
            val id = idOf(index.coerceAtMost(MAX_COLUMN), label)
            if (directFlags.getOrNull(index) == true) directIds.add(id)
            id
        }
        ids.forEach { counts[it] = 1 }
        for (i in 0 until ids.size - 1) {
            links[ids[i] to ids[i + 1]] = 1
        }
        return finish(counts, links, directIds)
    }

    private fun finish(
        counts: Map<String, Int>,
        links: Map<Pair<String, String>, Int>,
        directIds: Set<String>,
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
                val label = labelOf(id)
                nodes += FlowNode(
                    id = id,
                    label = label,
                    column = col,
                    weight = weight,
                    direct = id in directIds || isDirectTag(label),
                )
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
            FlowLink(
                fromId = from,
                toId = to,
                weight = weight,
                direct = from in directIds || to in directIds,
            )
        }
        val merged = LinkedHashMap<Pair<String, String>, Int>()
        val mergedDirect = HashMap<Pair<String, String>, Boolean>()
        flowLinks.forEach { link ->
            val key = link.fromId to link.toId
            merged[key] = (merged[key] ?: 0) + link.weight
            mergedDirect[key] = (mergedDirect[key] == true) || link.direct
        }
        return nodes to merged.map { (key, weight) ->
            FlowLink(key.first, key.second, weight, mergedDirect[key] == true)
        }
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
        if (value.isEmpty() || value == "final" || value == "<final>") return "<final>"
        extractAssigned(value)?.let { extracted ->
            val name = stripRuleName(extracted)
            if (name.isNotEmpty()) return name.take(18)
        }
        return stripRuleName(value).take(18).ifBlank { value.take(18) }
    }

    internal fun prettyHop(raw: String): String {
        val shown = ChainRuntimeCompiler.displayHopTag(raw).trim()
        val base = when {
            shown.isNotEmpty() -> shown
            else -> {
                val t = raw.trim()
                if (t.isEmpty()) return ""
                if (t.startsWith(ChainRuntimeCompiler.GENERATED_PREFIX)) return ""
                if (t == ChainRuntimeCompiler.LEGACY_CHAIN_TAG) return ""
                if (t.startsWith(ChainRuntimeCompiler.LEGACY_PREFIX)) {
                    t.removePrefix(ChainRuntimeCompiler.LEGACY_PREFIX)
                } else {
                    t
                }
            }
        }
        if (isDirectTag(base)) return "DIRECT"
        return shortenNodeName(base)
    }

    internal fun shortenNodeName(name: String, maxChars: Int = 22): String {
        var s = name.trim()
        if (s.isEmpty()) return s
        s = s.replaceFirst(Regex("^chainbox-(landing|entry|chain)-\\d+-"), "")
        s = s.replaceFirst(Regex("^chainbox-(landing|entry|chain)-"), "")
        val clipped = PROTO_TAIL.replaceFirst(s, "")
        if (clipped.length >= 2) s = clipped
        s = s.trim(' ', '-', '_', '[', ']')
        if (s.length > maxChars) s = s.take(maxChars - 1) + "…"
        return s.ifBlank { name.take(maxChars) }
    }

    internal fun prettyDest(raw: String): String {
        var value = raw.trim().ifBlank { "<unknown>" }
        if (value.endsWith(":443") || value.endsWith(":80")) {
            value = value.substringBeforeLast(':')
        }
        return if (value.length <= 22) value else value.take(19) + "…"
    }

    internal fun isDirectTag(tag: String): Boolean {
        val t = tag.trim()
        return t.equals("direct", ignoreCase = true) || t == "直连"
    }

    private fun extractAssigned(value: String): String? {
        val match = ASSIGNMENT.find(value) ?: return null
        return match.groupValues[2].trim()
    }

    private fun stripRuleName(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("[")) s = s.removePrefix("[").substringBefore(']').trim()
        s = s.trim().removeSurrounding("\"").removeSurrounding("'")
        s = s.substringBefore(',').trim().removeSurrounding("\"").removeSurrounding("'")
        s = s.substringAfterLast('/')
        s = s.substringAfterLast(':')
        s = s.removePrefix("geosite-").removePrefix("geoip-").removePrefix("category-")
        if (s.startsWith("rule_set", ignoreCase = true)) return ""
        return s.trim()
    }

    private fun idOf(column: Int, label: String): String = "$column|$label"
    private fun columnOf(id: String): Int = id.substringBefore('|').toIntOrNull() ?: 0
    private fun labelOf(id: String): String = id.substringAfter('|', id)

    private val ASSIGNMENT = Regex(
        """(?i)(rule_set|ruleset|geosite|geoip|domain_suffix|domain_keyword|domain|ip_cidr|ipcidr)\s*=\s*(.+)""",
    )
    private val PROTO_TAIL = Regex(
        """(?i)[-_\s\[]+(vless|vmess|trojan|hysteria2?|tuic|wireguard|shadowsocks|\bss\b|anytls).*""",
    )
}

object SankeyLayout {
    fun requiredHeight(
        nodes: List<FlowNode>,
        pad: Float,
        gapY: Float,
        minHeights: Map<String, Float>,
        floor: Float,
    ): Float {
        if (nodes.isEmpty()) return floor
        val byCol = nodes.groupBy { it.column }
        val needed = byCol.values.maxOf { col ->
            val mins = col.map { node -> (minHeights[node.id] ?: 16f).coerceAtLeast(16f) }
            pad * 2f + mins.sum() + gapY * (col.size - 1).coerceAtLeast(0)
        }
        return needed.coerceAtLeast(floor)
    }

    fun layout(
        nodes: List<FlowNode>,
        links: List<FlowLink>,
        width: Float,
        height: Float,
        nodeWidth: Float,
        pad: Float,
        minHeights: Map<String, Float> = emptyMap(),
        gapY: Float = 10f,
    ): Pair<List<PlacedNode>, List<PlacedRibbon>> {
        if (nodes.isEmpty() || width <= 0f || height <= 0f) {
            return emptyList<PlacedNode>() to emptyList()
        }
        val columns = nodes.groupBy { it.column }.toSortedMap()
        val nCols = columns.size.coerceAtLeast(1)
        val inner = (width - 2f * pad).coerceAtLeast(nodeWidth * nCols)
        val layerWidth = inner / nCols
        val placed = ArrayList<PlacedNode>(nodes.size)
        val byId = HashMap<String, PlacedNode>(nodes.size)
        columns.entries.forEachIndexed { index, (_, colNodes) ->
            val x = pad + index * layerWidth
            val mins = colNodes.map { node -> (minHeights[node.id] ?: 16f).coerceAtLeast(16f) }
            var y = pad
            colNodes.forEachIndexed { i, node ->
                val h = mins[i]
                val item = PlacedNode(node, x, y, nodeWidth, h)
                placed += item
                byId[node.id] = item
                y += h + gapY
            }
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
                direct = link.direct || from.node.direct || to.node.direct,
            )
            outCursor[from.node.id] = fy + fh
            inCursor[to.node.id] = ty + th
        }
        return placed to ribbons
    }
}
