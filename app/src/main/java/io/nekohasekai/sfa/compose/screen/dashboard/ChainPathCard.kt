package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.chain.FlowLink
import io.nekohasekai.sfa.chain.FlowNode
import io.nekohasekai.sfa.chain.LiveTopology
import io.nekohasekai.sfa.chain.PlacedRibbon
import io.nekohasekai.sfa.chain.SankeyLayout

@Composable
fun ChainPathCard(
    topology: LiveTopology,
    onOpenChainBuilder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = topology.running
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        onClick = onOpenChainBuilder,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.AltRoute,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.chain_path_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.width(6.dp))
                StatusPill(topology)
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = onOpenChainBuilder,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.OpenInNew,
                        contentDescription = stringResource(R.string.chain_builder),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            val device = stringResource(R.string.chain_path_device)
            val dest = stringResource(R.string.chain_path_destination)
            val waiting = stringResource(R.string.chain_path_waiting_dest)
            val unknown = stringResource(R.string.chain_path_unknown)
            val finalRule = stringResource(R.string.chain_path_final)
            val localizedNodes = remember(topology.flowNodes, device, dest, waiting, unknown, finalRule) {
                topology.flowNodes.map { node ->
                    node.copy(
                        label = when (node.label) {
                            "Device", "" -> device
                            "Destination" -> dest
                            "waiting" -> waiting
                            "<unknown>" -> unknown
                            "<final>" -> finalRule
                            else -> node.label
                        },
                    )
                }
            }

            TrafficSankey(
                nodes = localizedNodes,
                links = topology.flowLinks,
                flowing = topology.flowing && running,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (running) 276.dp else 196.dp),
            )

            if (running && (topology.destinations.isNotEmpty() || topology.activeConnections > 0)) {
                val destText = if (topology.destinations.isNotEmpty()) {
                    topology.destinations.take(3).joinToString(" · ")
                } else {
                    stringResource(R.string.chain_path_destination)
                }
                Text(
                    text = stringResource(
                        R.string.chain_path_live_summary,
                        topology.activeConnections,
                        destText,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(topology: LiveTopology) {
    val (label, emphasized) = when {
        !topology.running -> stringResource(R.string.chain_path_idle) to false
        topology.mode.equals("direct", ignoreCase = true) ->
            stringResource(R.string.chain_path_mode_direct) to false
        topology.chained -> stringResource(R.string.chain_path_live_chained) to true
        else -> stringResource(R.string.chain_path_live_regular) to false
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (emphasized) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (emphasized) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun TrafficSankey(
    nodes: List<FlowNode>,
    links: List<FlowLink>,
    flowing: Boolean,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val dark = isSystemInDarkTheme()
    val labelColor = if (dark) Color.White else Color(0xFF1F2937)
    val labelStyle = TextStyle(
        fontSize = 8.sp,
        fontWeight = FontWeight.Medium,
        color = labelColor,
        lineHeight = 10.sp,
    )
    val phase by rememberInfiniteTransition(label = "sankey").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    val source = Color(0xFF5B6CFF)
    val rule = Color(0xFF2FBF71)
    val hop = Color(0xFFF0B429)
    val dest = Color(0xFFE57373)
    Canvas(modifier = modifier) {
        if (nodes.isEmpty()) return@Canvas
        val nodeWidth = 56.dp.toPx()
        val (placed, ribbons) = SankeyLayout.layout(
            nodes = nodes,
            links = links,
            width = size.width,
            height = size.height,
            nodeWidth = nodeWidth,
            pad = 3.dp.toPx(),
        )
        fun columnColor(col: Int, last: Int): Color = when {
            col <= 0 -> source
            col == last -> dest
            col == 1 && last >= 3 -> rule
            else -> hop
        }
        fun pastel(c: Color): Color {
            return if (dark) {
                lerp(c, Color.Black, 0.18f).copy(alpha = 0.92f)
            } else {
                lerp(c, Color.White, 0.52f)
            }
        }
        val lastCol = nodes.maxOf { it.column }
        ribbons.forEach { ribbon ->
            val fromC = columnColor(ribbon.columnFrom, lastCol)
            val toC = columnColor(ribbon.columnTo, lastCol)
            val path = ribbonPath(ribbon)
            drawPath(
                path = path,
                brush = Brush.horizontalGradient(
                    colors = listOf(fromC.copy(alpha = 0.28f), toC.copy(alpha = 0.30f)),
                    startX = ribbon.x0,
                    endX = ribbon.x1,
                ),
            )
        }
        if (flowing) {
            ribbons.forEach { ribbon ->
                val fromC = columnColor(ribbon.columnFrom, lastCol)
                val toC = columnColor(ribbon.columnTo, lastCol)
                val dx = (ribbon.x1 - ribbon.x0) * 0.48f
                val y0 = (ribbon.y0Top + ribbon.y0Bottom) / 2f
                val y1 = (ribbon.y1Top + ribbon.y1Bottom) / 2f
                val dots = 3
                for (k in 0 until dots) {
                    val t = (phase + k / dots.toFloat()) % 1f
                    val p = cubicPoint(
                        t,
                        ribbon.x0, y0,
                        ribbon.x0 + dx, y0,
                        ribbon.x1 - dx, y1,
                        ribbon.x1, y1,
                    )
                    val glow = lerp(fromC, toC, t)
                    drawCircle(glow.copy(alpha = 0.22f), radius = 7.dp.toPx(), center = p)
                    drawCircle(glow.copy(alpha = 0.70f), radius = 2.6.dp.toPx(), center = p)
                    drawCircle(Color.White.copy(alpha = 0.90f), radius = 1.2.dp.toPx(), center = p)
                }
            }
        }
        placed.forEach { node ->
            val ink = columnColor(node.node.column, lastCol)
            val fill = pastel(ink)
            val radius = (node.h / 2f).coerceAtMost(8.dp.toPx())
            val round = CornerRadius(radius, radius)
            drawRoundRect(
                color = fill,
                topLeft = Offset(node.x, node.y),
                size = Size(node.w, node.h),
                cornerRadius = round,
            )
            drawRoundRect(
                color = ink.copy(alpha = if (dark) 0.55f else 0.42f),
                topLeft = Offset(node.x, node.y),
                size = Size(3.dp.toPx(), node.h),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            )
            drawRoundRect(
                color = ink.copy(alpha = 0.22f),
                topLeft = Offset(node.x, node.y),
                size = Size(node.w, node.h),
                cornerRadius = round,
                style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round),
            )
            if (node.h < 11f) return@forEach
            val padX = 5.dp.toPx()
            val layout = textMeasurer.measure(
                text = node.node.label,
                style = labelStyle,
                maxLines = if (node.h >= 28f) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            val tx = node.x + padX
            val ty = node.y + ((node.h - layout.size.height) / 2f).coerceAtLeast(1f)
            drawText(layout, topLeft = Offset(tx, ty))
        }
    }
}

private fun ribbonPath(ribbon: PlacedRibbon): Path {
    val dx = (ribbon.x1 - ribbon.x0) * 0.48f
    return Path().apply {
        moveTo(ribbon.x0, ribbon.y0Top)
        cubicTo(
            ribbon.x0 + dx, ribbon.y0Top,
            ribbon.x1 - dx, ribbon.y1Top,
            ribbon.x1, ribbon.y1Top,
        )
        lineTo(ribbon.x1, ribbon.y1Bottom)
        cubicTo(
            ribbon.x1 - dx, ribbon.y1Bottom,
            ribbon.x0 + dx, ribbon.y0Bottom,
            ribbon.x0, ribbon.y0Bottom,
        )
        close()
    }
}

private fun cubicPoint(
    t: Float,
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    x3: Float,
    y3: Float,
): Offset {
    val u = 1f - t
    val uu = u * u
    val uuu = uu * u
    val tt = t * t
    val ttt = tt * t
    return Offset(
        uuu * x0 + 3f * uu * t * x1 + 3f * u * tt * x2 + ttt * x3,
        uuu * y0 + 3f * uu * t * y1 + 3f * u * tt * y2 + ttt * y3,
    )
}
