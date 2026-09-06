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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.bg.RootClient
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.base.rememberApplyServiceChangeNotifier
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
                                    withContext(Dispatchers.Main) { reload() }
                                }
                            },
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                if (perAppProxyEnabled) {
                    ListItem(
                        headlineContent = { Text("管理应用") },
                        trailingContent = {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            navController.navigate("settings/profile_override/manage")
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
            Text(
                text = "配置修复",
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
                    title = "配置规范化",
                    subtitle = "覆写脚本：保留节点，其余换成 sing-box 标准模板",
                    checked = configNormalize,
                    onHelp = {
                        help = SwitchHelp(
                            "配置规范化",
                            "这是运行时覆写脚本，不改磁盘上的订阅文件。\n\n保留：你的节点（vmess/vless/ss/等）和 selector/urltest 分组。\n覆写：DNS、路由规则、规则集、缺少的 TUN、direct/block。\n\n模板会：\n· 国内域名/国内 IP 走 direct\n· 私网走 direct\n· 拦截 STUN（3478/19302/5349）防 WebRTC 泄露真实 IP\n· DNS 境外走代理、国内走直连\n· 其余走你的主选择组\n\n失败时仪表盘会红色提示，不会暗中改走 DIRECT。关闭开关即恢复原配置逻辑。",
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
