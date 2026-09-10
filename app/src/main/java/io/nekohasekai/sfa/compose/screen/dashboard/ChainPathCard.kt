package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.chain.ChainPathHop
import io.nekohasekai.sfa.chain.LiveHop
import io.nekohasekai.sfa.chain.LiveTopology

@Composable
fun ChainPathCard(
    topology: LiveTopology,
    onOpenChainBuilder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chained = topology.chained
    val running = topology.running
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                chained && running -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f)
                else -> MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        onClick = onOpenChainBuilder,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.AltRoute,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.chain_path_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatusPill(topology)
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = onOpenChainBuilder,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.OpenInNew,
                        contentDescription = stringResource(R.string.chain_builder),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                topology.hops.forEachIndexed { index, hop ->
                    if (index > 0) {
                        FlowArrow(
                            flowing = topology.flowing && running,
                            highlighted = hop.highlighted || topology.hops[index - 1].highlighted,
                        )
                    }
                    LiveHopChip(hop = hop, running = running)
                }
            }

            if (running && (topology.destinations.isNotEmpty() || topology.activeConnections > 0)) {
                Spacer(modifier = Modifier.height(6.dp))
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
            } else if (!running) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        if (chained) R.string.chain_path_hint_chained else R.string.chain_path_hint_regular,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
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
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun LiveHopChip(hop: LiveHop, running: Boolean) {
    val role = hopRoleLabel(hop.role)
    val title = hop.title.ifBlank {
        when (hop.role) {
            ChainPathHop.Role.Device -> role
            ChainPathHop.Role.Destination ->
                if (running) stringResource(R.string.chain_path_waiting_dest) else role
            else -> role
        }
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (hop.highlighted && running) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (hop.highlighted && running) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 48.dp, max = 112.dp)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = role,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildString {
                if (hop.delayMs > 0) append("${hop.delayMs}ms")
                if (hop.subtitle.isNotBlank() && hop.subtitle != hop.title) {
                    if (isNotEmpty()) append(" · ")
                    append(hop.subtitle)
                }
            }
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FlowArrow(flowing: Boolean, highlighted: Boolean) {
    val color = if (highlighted) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val phase by rememberInfiniteTransition(label = "flow").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    Canvas(modifier = Modifier.width(20.dp).height(16.dp)) {
        val y = size.height / 2f
        val effect = if (flowing) {
            PathEffect.dashPathEffect(floatArrayOf(10f, 8f), phase * 18f)
        } else {
            null
        }
        drawLine(
            color = color,
            start = Offset(0f, y),
            end = Offset(size.width - 4.dp.toPx(), y),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = effect,
        )
        val tip = size.width
        val ah = 4.dp.toPx()
        drawLine(color, Offset(tip - ah - 2f, y - ah), Offset(tip, y), 2.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(tip - ah - 2f, y + ah), Offset(tip, y), 2.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
private fun hopRoleLabel(role: ChainPathHop.Role): String = when (role) {
    ChainPathHop.Role.Device -> stringResource(R.string.chain_path_device)
    ChainPathHop.Role.Entry -> stringResource(R.string.chain_path_entry)
    ChainPathHop.Role.Exit -> stringResource(R.string.chain_path_exit)
    ChainPathHop.Role.Landing -> stringResource(R.string.chain_path_landing)
    ChainPathHop.Role.Destination -> stringResource(R.string.chain_path_destination)
}
