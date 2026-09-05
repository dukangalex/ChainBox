package io.nekohasekai.sfa.compose.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.base.rememberApplyServiceChangeNotifier
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SwitchHelp(
    val title: String,
    val body: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileOverrideScreen(
    navController: NavController,
    serviceStatus: Status = Status.Stopped,
) {
    val scope = rememberCoroutineScope()
    val notifyApplyChange = rememberApplyServiceChangeNotifier(serviceStatus)

    var configNormalize by remember { mutableStateOf(Settings.configNormalize) }
    var disableQuic by remember { mutableStateOf(Settings.disableQuic) }
    var excludeCnQuic by remember { mutableStateOf(Settings.excludeCnQuic) }
    var strictRoute by remember { mutableStateOf(Settings.strictRoute) }
    var dnsProtect by remember { mutableStateOf(Settings.dnsProtect) }
    var disableIpv6 by remember { mutableStateOf(Settings.disableIpv6) }

    var help by remember { mutableStateOf<SwitchHelp?>(null) }

    fun reload() {
        scope.launch(Dispatchers.Main) {
            notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload)
        }
    }

    if (help != null) {
        val h = help!!
        AlertDialog(
            onDismissRequest = { help = null },
            title = { Text(h.title) },
            text = { Text(h.body) },
            confirmButton = {
                TextButton(onClick = { help = null }) { Text("知道了") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("配置覆盖") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = "配置修复",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(
                text = "开关仅影响运行时配置，不修改订阅文件。点 ⓘ 查看说明。链式在「工具 → 链式代理」设置后会在订阅更新后自动保持。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                OverrideSwitch(
                    title = "配置规范化",
                    subtitle = "修补格式/规则集，并拦截 STUN 防 WebRTC 真实 IP 泄漏",
                    checked = configNormalize,
                    onHelp = {
                        help = SwitchHelp(
                            "配置规范化",
                            "作用：不改磁盘订阅，启动/重载时自动：\n· 补齐 direct / block 出站\n· 清理无效 rule_set、废弃 geoip/geosite 字段\n· 修正无效 route.final\n· 【防 WebRTC 泄漏】优先拦截 UDP 3478/19302/5349（STUN/TURN），避免国内 STUN（B站/小米等）因「国内直连」暴露真实宽带 IP\n\n说明：国外 STUN 无泄漏、国内 STUN 显示真实 IP，是典型分流直连现象；规范化会拦截 STUN 探测。\n\n生效：切换后自动重载。",
                        )
                    },
                    onCheckedChange = {
                        configNormalize = it
                        scope.launch(Dispatchers.IO) {
                            Settings.configNormalize = it
                            withContext(Dispatchers.Main) { reload() }
                        }
                    },
                )
            }

            Text(
                text = "网络增强",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                OverrideSwitch(
                    title = "严格路由",
                    subtitle = "减少流量绕过 VPN，降低 IP/DNS 泄漏风险",
                    checked = strictRoute,
                    onHelp = {
                        help = SwitchHelp(
                            "严格路由",
                            "作用：在 TUN 入站上开启 strict_route。\n\n适合：防泄漏。注意需配置含 TUN。\n\n生效：切换后自动重载。",
                        )
                    },
                    onCheckedChange = {
                        strictRoute = it
                        scope.launch(Dispatchers.IO) {
                            Settings.strictRoute = it
                            withContext(Dispatchers.Main) { reload() }
                        }
                    },
                )
                OverrideSwitch(
                    title = "DNS 防泄漏倾向",
                    subtitle = "加强 DNS 走代理栈的倾向",
                    checked = dnsProtect,
                    onHelp = {
                        help = SwitchHelp(
                            "DNS 防泄漏倾向",
                            "作用：开启 independent_cache、auto_detect_interface 等。\n\n生效：切换后自动重载。",
                        )
                    },
                    onCheckedChange = {
                        dnsProtect = it
                        scope.launch(Dispatchers.IO) {
                            Settings.dnsProtect = it
                            withContext(Dispatchers.Main) { reload() }
                        }
                    },
                )
                OverrideSwitch(
                    title = "禁用 IPv6",
                    subtitle = "仅使用 IPv4，避免 IPv6 旁路泄漏",
                    checked = disableIpv6,
                    onHelp = {
                        help = SwitchHelp(
                            "禁用 IPv6",
                            "作用：DNS strategy=ipv4_only，并拦截 IPv6。\n\n生效：切换后自动重载。",
                        )
                    },
                    onCheckedChange = {
                        disableIpv6 = it
                        scope.launch(Dispatchers.IO) {
                            Settings.disableIpv6 = it
                            withContext(Dispatchers.Main) { reload() }
                        }
                    },
                )
                OverrideSwitch(
                    title = "禁用 QUIC",
                    subtitle = "拦截 UDP 443，强制回落 TCP/HTTP2",
                    checked = disableQuic,
                    onHelp = {
                        help = SwitchHelp(
                            "禁用 QUIC",
                            "作用：拦截 UDP 443（HTTP/3/QUIC），并附带拦截 STUN 端口以降低 WebRTC 探测。\n\n注意：不能单独解决「国内 STUN 显示真实 IP」——那是 UDP 3478 直连，请开「配置规范化」。\n\n生效：切换后自动重载。",
                        )
                    },
                    onCheckedChange = {
                        disableQuic = it
                        if (!it) excludeCnQuic = false
                        scope.launch(Dispatchers.IO) {
                            Settings.disableQuic = it
                            if (!it) Settings.excludeCnQuic = false
                            withContext(Dispatchers.Main) { reload() }
                        }
                    },
                )
                OverrideSwitch(
                    title = "排除国内 QUIC",
                    subtitle = "放行中国大陆 QUIC，仅禁用境外",
                    checked = excludeCnQuic,
                    enabled = disableQuic,
                    onHelp = {
                        help = SwitchHelp(
                            "排除国内 QUIC",
                            "作用：在「禁用 QUIC」时，对中国大陆 IP 的 UDP 443 放行（便于国内视频等），境外仍拦截。\n\n与 WebRTC 无关：国内 STUN 泄漏请依赖「配置规范化」拦截 3478 端口，而不是本开关。",
                        )
                    },
                    onCheckedChange = {
                        excludeCnQuic = it
                        scope.launch(Dispatchers.IO) {
                            Settings.excludeCnQuic = it
                            withContext(Dispatchers.Main) { reload() }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun OverrideSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onHelp: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                IconButton(onClick = onHelp) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "说明",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        supportingContent = {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
            )
        },
        modifier = Modifier.clip(RoundedCornerShape(12.dp)),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
