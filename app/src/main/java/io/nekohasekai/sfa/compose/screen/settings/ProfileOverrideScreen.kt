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
                text = "网络增强",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(
                text = "开关仅影响运行时配置，不修改订阅文件。点 ⓘ 可查看详细说明。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
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
                            "作用：尽量让系统流量只走 VPN/TUN 隧道，降低 WebRTC、旁路 DNS 等泄漏。\n\n适合：需要更严格防泄漏时开启。\n\n注意：部分局域网共享、投屏、车机互联可能受影响，出现异常可关闭。\n\n生效：切换后自动重载服务。",
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
                    subtitle = "加强 DNS 走代理栈的倾向，降低明文 DNS 旁路",
                    checked = dnsProtect,
                    onHelp = {
                        help = SwitchHelp(
                            "DNS 防泄漏倾向",
                            "作用：在不推翻你原有 DNS 服务器列表的前提下，开启独立缓存、自动探测网卡等，降低 DNS 查询绕过代理的概率。\n\n适合：检测网站提示 DNS 泄漏，或希望查询更稳定走隧道时。\n\n注意：不会完全重写机场自带 DNS；若订阅 DNS 本身有问题，仍需改配置或换订阅。\n\n生效：切换后自动重载服务。",
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
                            "作用：DNS 策略改为仅 IPv4，并拦截 IPv6 流量，避免在仅代理 IPv4 时出现 IPv6 直连泄漏。\n\n适合：检测仍暴露真实 IPv6，或网络 IPv6 不稳定时。\n\n注意：纯 IPv6 站点将无法访问。\n\n生效：切换后自动重载服务。",
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
                    subtitle = "拦截 UDP 443，强制浏览器回落 TCP/HTTP2",
                    checked = disableQuic,
                    onHelp = {
                        help = SwitchHelp(
                            "禁用 QUIC",
                            "作用：拦截 UDP 443（HTTP/3 / QUIC），让浏览器用 TCP，可缓解部分网站打不开或异常超时。\n\n适合：个别站点异常、与代理兼容性不好时。\n\n注意：会略微影响依赖 HTTP/3 的站点体验。\n\n生效：切换后自动重载服务。",
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
                            "作用：在开启「禁用 QUIC」时，对中国大陆 IP 的 UDP 443 放行，只拦截境外 QUIC。\n\n适合：希望国内视频/应用仍可用 HTTP/3，同时限制境外 QUIC。\n\n注意：依赖 geoip:cn，订阅需带有可用的 geoip 数据。\n\n生效：需先开启「禁用 QUIC」；切换后自动重载服务。",
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

            Text(
                text = "分应用代理、自动重定向等将在后续版本恢复到本页。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
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
