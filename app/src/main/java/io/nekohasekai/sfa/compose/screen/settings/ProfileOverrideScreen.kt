package io.nekohasekai.sfa.compose.screen.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.bg.RootClient
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.base.rememberApplyServiceChangeNotifier
import io.nekohasekai.sfa.compose.screen.profileoverride.PerAppProxyScanner
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SwitchHelp(val title: String, val body: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileOverrideScreen(
    navController: NavController,
    serviceStatus: Status = Status.Stopped,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notifyApplyChange = rememberApplyServiceChangeNotifier(serviceStatus)

    var autoRedirect by remember { mutableStateOf(Settings.autoRedirect) }
    var perAppProxyEnabled by remember { mutableStateOf(Settings.perAppProxyEnabled) }
    var managedModeEnabled by remember { mutableStateOf(Settings.perAppProxyManagedMode) }
    var isScanning by remember { mutableStateOf(false) }
    var webrtcProtect by remember { mutableStateOf(Settings.webrtcProtect) }
    var echDns by remember { mutableStateOf(Settings.echDns) }
    var chinaDirect by remember { mutableStateOf(Settings.chinaDirect) }
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

    fun scanAndSaveManagedList(shouldNotify: Boolean = false) {
        isScanning = true
        scope.launch {
            val chinaApps = PerAppProxyScanner.scanAllChinaApps()
            withContext(Dispatchers.IO) {
                Settings.perAppProxyManagedList = chinaApps
            }
            isScanning = false
            if (shouldNotify) {
                withContext(Dispatchers.Main) { reload() }
            }
        }
    }

    if (help != null) {
        val h = help!!
        AlertDialog(
            onDismissRequest = { help = null },
            title = { Text(h.title) },
            text = { Text(h.body) },
            confirmButton = { TextButton(onClick = { help = null }) { Text("知道了") } },
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
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                ListItem(
                    headlineContent = { Text("自动重定向") },
                    supportingContent = { Text("需要 ROOT 权限") },
                    leadingContent = { Icon(Icons.Outlined.Route, contentDescription = null) },
                    trailingContent = {
                        Switch(
                            checked = autoRedirect,
                            onCheckedChange = { checked ->
                                scope.launch(Dispatchers.IO) {
                                    if (checked) {
                                        val hasRoot = RootClient.checkRootAvailable()
                                        if (!hasRoot) {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(context, "需要 ROOT 权限", Toast.LENGTH_SHORT).show()
                                            }
                                            return@launch
                                        }
                                    }
                                    Settings.autoRedirect = checked
                                    withContext(Dispatchers.Main) {
                                        autoRedirect = checked
                                        reload()
                                    }
                                }
                            },
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            Text(
                text = "分应用代理",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                ListItem(
                    headlineContent = { Text("启用") },
                    leadingContent = { Icon(Icons.Outlined.FilterList, contentDescription = null) },
                    trailingContent = {
                        Switch(
                            checked = perAppProxyEnabled,
                            onCheckedChange = { checked ->
                                perAppProxyEnabled = checked
                                scope.launch(Dispatchers.IO) {
                                    Settings.perAppProxyEnabled = checked
                                    withContext(Dispatchers.Main) {
                                        if (checked && managedModeEnabled) {
                                            scanAndSaveManagedList(shouldNotify = true)
                                        } else {
                                            reload()
                                        }
                                    }
                                }
                            },
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                if (perAppProxyEnabled) {
                    ListItem(
                        headlineContent = { Text("管理") },
                        trailingContent = {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                        },
                        modifier = Modifier.clickable(enabled = !managedModeEnabled) {
                            navController.navigate("settings/profile_override/manage")
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    ListItem(
                        headlineContent = { Text("托管模式") },
                        supportingContent = { Text("自动排除中国应用") },
                        leadingContent = { Icon(Icons.Outlined.SmartToy, contentDescription = null) },
                        trailingContent = {
                            if (isScanning) {
                                CircularProgressIndicator()
                            } else {
                                Switch(
                                    checked = managedModeEnabled,
                                    onCheckedChange = { checked ->
                                        managedModeEnabled = checked
                                        scope.launch(Dispatchers.IO) {
                                            Settings.perAppProxyManagedMode = checked
                                        }
                                        if (checked) {
                                            scanAndSaveManagedList(shouldNotify = true)
                                        } else {
                                            reload()
                                        }
                                    },
                                )
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
            Text(
                text = "隐私防护",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(
                text = "以下为 ChainBox 运行时覆盖，不修改订阅文件。点 ⓘ 查看说明。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                OverrideSwitch(
                    title = "防 WebRTC 泄露",
                    subtitle = "拦截 STUN/TURN（UDP 3478/19302/5349）",
                    checked = webrtcProtect,
                    onHelp = {
                        help = SwitchHelp(
                            "防 WebRTC 泄露",
                            "拦截浏览器/应用的 STUN 探测，避免真实 IP 从 WebRTC 漏出。开启后「工具 → STUN 测试」会失败，这是预期行为。",
                        )
                    },
                    onCheckedChange = {
                        webrtcProtect = it
                        scope.launch(Dispatchers.IO) {
                            Settings.webrtcProtect = it
                            withContext(Dispatchers.Main) { reload() }
                        }
                    },
                )
                OverrideSwitch(
                    title = "ECH（DNS HTTPS）",
                    subtitle = "放行 EchConfig 所需的 HTTPS/SVCB DNS 查询",
                    checked = echDns,
                    onHelp = {
                        help = SwitchHelp(
                            "ECH（DNS HTTPS）",
                            "官方 sing-box 会在 tls.ech.enabled 且未写死 config 时，用 HTTPS DNS 记录拉取 EchConfig。部分订阅会拦截 query_type=HTTPS/SVCB，导致 ECH 失效。\n\n" +
                                "开启后：去掉这类拦截规则，节点里已有的 tls.ech 原样交给内核。不会给所有节点强开 ECH。",
                        )
                    },
                    onCheckedChange = {
                        echDns = it
                        scope.launch(Dispatchers.IO) {
                            Settings.echDns = it
                            withContext(Dispatchers.Main) { reload() }
                        }
                    },
                )
            }
            Text(
                text = "中国直连",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                OverrideSwitch(
                    title = "中国直连",
                    subtitle = "绕过中国 IP/域名、公共 DNS 与局域网",
                    checked = chinaDirect,
                    onHelp = {
                        help = SwitchHelp(
                            "中国直连",
                            "一个开关打包六项运行时绕过，不改订阅文件：\n" +
                                "1. 绕过中国 IP（配置里若已有 geoip-cn 规则集会直接用）\n" +
                                "2. 绕过中国域名（.cn 及常用国内站点）\n" +
                                "3. 绕过中国公共 DNS IP（阿里/114/DNSPod 等）\n" +
                                "4. 绕过中国公共 DNS 域名\n" +
                                "5. 绕过局域网 IP（ip_is_private）\n" +
                                "6. 绕过局域网域名（.local / .lan 等）\n\n" +
                                "国内域名解析走 223.5.5.5，流量走 direct。",
                        )
                    },
                    onCheckedChange = {
                        chinaDirect = it
                        scope.launch(Dispatchers.IO) {
                            Settings.chinaDirect = it
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
                OverrideSwitch("严格路由", "TUN strict_route，降低绕过泄漏", strictRoute, true, { help = SwitchHelp("严格路由", "开启 TUN strict_route。") }) {
                    strictRoute = it
                    scope.launch(Dispatchers.IO) { Settings.strictRoute = it; withContext(Dispatchers.Main) { reload() } }
                }
                OverrideSwitch("DNS 防泄漏倾向", "加强 DNS 走代理栈", dnsProtect, true, { help = SwitchHelp("DNS", "加强 DNS 防护倾向。") }) {
                    dnsProtect = it
                    scope.launch(Dispatchers.IO) { Settings.dnsProtect = it; withContext(Dispatchers.Main) { reload() } }
                }
                OverrideSwitch("禁用 IPv6", "仅 IPv4，避免 IPv6 旁路", disableIpv6, true, { help = SwitchHelp("禁用 IPv6", "ipv4_only 并拦截 IPv6。") }) {
                    disableIpv6 = it
                    scope.launch(Dispatchers.IO) { Settings.disableIpv6 = it; withContext(Dispatchers.Main) { reload() } }
                }
                OverrideSwitch("禁用 QUIC", "拦截 UDP 443", disableQuic, true, { help = SwitchHelp("禁用 QUIC", "拦截 HTTP/3。") }) {
                    disableQuic = it
                    if (!it) excludeCnQuic = false
                    scope.launch(Dispatchers.IO) {
                        Settings.disableQuic = it
                        if (!it) Settings.excludeCnQuic = false
                        withContext(Dispatchers.Main) { reload() }
                    }
                }
                OverrideSwitch("排除国内 QUIC", "放行中国大陆 QUIC", excludeCnQuic, disableQuic, { help = SwitchHelp("排除国内 QUIC", "国内 UDP 443 放行。") }) {
                    excludeCnQuic = it
                    scope.launch(Dispatchers.IO) { Settings.excludeCnQuic = it; withContext(Dispatchers.Main) { reload() } }
                }
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
                    Icon(Icons.Outlined.Info, contentDescription = "说明", tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
        supportingContent = {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = { Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange) },
        modifier = Modifier.clip(RoundedCornerShape(12.dp)),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
