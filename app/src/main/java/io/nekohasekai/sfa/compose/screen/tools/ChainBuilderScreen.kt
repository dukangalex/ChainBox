package io.nekohasekai.sfa.compose.screen.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private data class OutboundItem(
    val tag: String,
    val type: String,
) {
    val isGroup: Boolean get() = type == "selector" || type == "urltest"
    val typeLabel: String
        get() = when (type) {
            "urltest" -> "自动优选"
            "selector" -> "手动选择"
            "chain" -> "链式"
            else -> type.ifBlank { "节点" }
        }
}

/**
 * NekoBox-like chain from subscription groups, while keeping sing-box
 * urltest/selector auto-select on any hop (including dual-group chains).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChainBuilderScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var profileName by remember { mutableStateOf("") }
    var profilePath by remember { mutableStateOf<String?>(null) }
    var groups by remember { mutableStateOf<List<OutboundItem>>(emptyList()) }
    var nodes by remember { mutableStateOf<List<OutboundItem>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }

    var chainTag by remember { mutableStateOf("my-chain") }
    val hops = remember { mutableStateListOf<String>() }
    var setAsFinal by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    // Wizard: pick front group then exit (node or group)
    var frontPick by remember { mutableStateOf<String?>(null) }
    var exitPick by remember { mutableStateOf<String?>(null) }

    fun allItems(): List<OutboundItem> = groups + nodes

    fun reloadProfile() {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val id = Settings.selectedProfile
                    if (id == -1L) {
                        loadError = "请先在主页选择一个配置"
                        groups = emptyList()
                        nodes = emptyList()
                        profilePath = null
                        profileName = ""
                        return@withContext
                    }
                    val profile = ProfileManager.get(id)
                    if (profile == null) {
                        loadError = "找不到当前配置"
                        groups = emptyList()
                        nodes = emptyList()
                        profilePath = null
                        return@withContext
                    }
                    val path = profile.typed.path
                    val root = JSONObject(File(path).readText())
                    val outs = root.optJSONArray("outbounds") ?: JSONArray()
                    val g = mutableListOf<OutboundItem>()
                    val n = mutableListOf<OutboundItem>()
                    for (i in 0 until outs.length()) {
                        val o = outs.optJSONObject(i) ?: continue
                        val tag = o.optString("tag").trim()
                        val type = o.optString("type").trim()
                        if (tag.isEmpty()) continue
                        if (type in listOf("direct", "block", "dns")) continue
                        if (tag in listOf("direct", "block", "dns", "global")) continue
                        // chain itself can be nested carefully; skip listing other chains as hops by default
                        if (type == "chain") continue
                        val item = OutboundItem(tag, type)
                        if (item.isGroup) g.add(item) else n.add(item)
                    }
                    profileName = profile.name
                    profilePath = path
                    groups = g
                    nodes = n
                    loadError = when {
                        g.isEmpty() && n.isEmpty() -> "配置里没有可串联的分组或节点"
                        else -> null
                    }
                } catch (e: Exception) {
                    loadError = "读取配置失败: ${e.message}"
                    groups = emptyList()
                    nodes = emptyList()
                    profilePath = null
                }
            }
        }
    }

    LaunchedEffect(Unit) { reloadProfile() }

    fun applyWizardToHops() {
        val f = frontPick
        val e = exitPick
        if (f == null || e == null) {
            scope.launch { snackbar.showSnackbar("请选择前置分组和落地") }
            return
        }
        if (f == e) {
            scope.launch { snackbar.showSnackbar("前置和落地不能相同") }
            return
        }
        hops.clear()
        hops.add(f)
        hops.add(e)
        scope.launch { snackbar.showSnackbar("已生成链路：分组 → 落地（可再手动调整）") }
    }

    fun saveToProfile() {
        if (hops.size < 2) {
            scope.launch { snackbar.showSnackbar("至少需要 2 跳（例如：优选分组 → 落地节点）") }
            return
        }
        if (chainTag.isBlank()) {
            scope.launch { snackbar.showSnackbar("请填写链条名称") }
            return
        }
        val path = profilePath ?: run {
            scope.launch { snackbar.showSnackbar("没有可用配置") }
            return
        }
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val root = JSONObject(File(path).readText())
                    val outs = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
                    val cleaned = JSONArray()
                    for (i in 0 until outs.length()) {
                        val o = outs.optJSONObject(i) ?: continue
                        if (o.optString("tag") == chainTag.trim()) continue
                        cleaned.put(o)
                    }
                    val chain = JSONObject()
                    chain.put("type", "chain")
                    chain.put("tag", chainTag.trim())
                    val hopArr = JSONArray()
                    hops.forEach { hopArr.put(it) }
                    chain.put("outbounds", hopArr)
                    cleaned.put(chain)
                    root.put("outbounds", cleaned)
                    if (setAsFinal) {
                        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
                        route.put("final", chainTag.trim())
                    }
                    File(path).writeText(root.toString(2))
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            busy = false
            if (result.isSuccess) {
                snackbar.showSnackbar("已写入「$profileName」。停止后重新启动服务生效")
            } else {
                snackbar.showSnackbar("写入失败: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    @Composable
    fun ChipSection(title: String, subtitle: String, items: List<OutboundItem>) {
        if (items.isEmpty()) return
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items.forEach { item ->
                val selected = hops.contains(item.tag)
                FilterChip(
                    selected = selected,
                    onClick = {
                        if (selected) hops.remove(item.tag) else hops.add(item.tag)
                    },
                    label = { Text("${item.tag} · ${item.typeLabel}") },
                    leadingIcon = if (selected) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("链式代理") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { reloadProfile() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("当前配置", style = MaterialTheme.typography.labelMedium)
                    Text(
                        profileName.ifBlank { "（未选择）" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = loadError
                            ?: "支持：分组→节点（前置自动优选）、分组→分组（两端都能优选）",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (loadError != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // Quick wizard like NekoBox + mihomo relay
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("快速建链（推荐）", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "① 前置：选 urltest/selector 分组（自动优选）\n② 落地：选单节点，或再选一个分组（订阅→订阅）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("前置分组", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        groups.forEach { g ->
                            FilterChip(
                                selected = frontPick == g.tag,
                                onClick = { frontPick = if (frontPick == g.tag) null else g.tag },
                                label = { Text("${g.tag} (${g.typeLabel})") },
                            )
                        }
                        if (groups.isEmpty()) {
                            Text("无 urltest/selector 分组，请先在配置里建分组", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("落地（单节点或另一分组）", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (nodes + groups).forEach { item ->
                            if (item.tag == frontPick) return@forEach
                            FilterChip(
                                selected = exitPick == item.tag,
                                onClick = { exitPick = if (exitPick == item.tag) null else item.tag },
                                label = {
                                    Text(
                                        if (item.isGroup) {
                                            "${item.tag} (分组·${item.typeLabel})"
                                        } else {
                                            item.tag
                                        },
                                    )
                                },
                            )
                        }
                    }
                    Button(
                        onClick = { applyWizardToHops() },
                        enabled = frontPick != null && exitPick != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("应用为链路")
                    }
                }
            }

            OutlinedTextField(
                value = chainTag,
                onValueChange = { chainTag = it },
                label = { Text("链条名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("链路预览（上→下 = 前置→落地）", style = MaterialTheme.typography.titleSmall)
            if (hops.isEmpty()) {
                Text(
                    "用上方「快速建链」，或在下方点选分组/节点。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                hops.forEachIndexed { index, tag ->
                    val item = allItems().find { it.tag == tag }
                    val role = when (index) {
                        0 -> "前置"
                        hops.lastIndex -> "落地"
                        else -> "中转"
                    }
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            ) {
                                Text(
                                    "${index + 1}",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp),
                            ) {
                                Text("$role · $tag", fontWeight = FontWeight.Medium)
                                Text(
                                    item?.let {
                                        if (it.isGroup) "${it.typeLabel}（运行时自动选节点）" else it.typeLabel
                                    } ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (index < hops.lastIndex) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (index > 0) {
                                        val x = hops.removeAt(index)
                                        hops.add(index - 1, x)
                                    }
                                },
                                enabled = index > 0,
                            ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上移") }
                            IconButton(
                                onClick = {
                                    if (index < hops.lastIndex) {
                                        val x = hops.removeAt(index)
                                        hops.add(index + 1, x)
                                    }
                                },
                                enabled = index < hops.lastIndex,
                            ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "下移") }
                            IconButton(onClick = { hops.removeAt(index) }) {
                                Icon(Icons.Default.Delete, contentDescription = "移除")
                            }
                        }
                    }
                }
            }

            ChipSection(
                title = "订阅分组（自动优选 / 手动选择）",
                subtitle = "urltest = 自动测速优选；selector = 手动点选。可作前置或落地。",
                items = groups,
            )
            ChipSection(
                title = "单节点",
                subtitle = "适合作为落地出口；也可手动加入链路任意位置。",
                items = nodes,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("设为默认出口 (route.final)")
                    Text(
                        "开启后全局默认走这条链",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = setAsFinal, onCheckedChange = { setAsFinal = it })
            }

            Button(
                onClick = { saveToProfile() },
                enabled = !busy && hops.size >= 2 && chainTag.isNotBlank() && profilePath != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "正在写入…" else "一键写入当前配置")
            }

            OutlinedButton(
                onClick = {
                    hops.clear()
                    frontPick = null
                    exitPick = null
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = hops.isNotEmpty() || frontPick != null || exitPick != null,
            ) {
                Text("清空")
            }

            Text(
                text = "能力说明：\n" +
                    "· 分组→单节点：前置 urltest 自动优选，落地固定节点（NekoBox 常见用法 + 自动优选）\n" +
                    "· 分组→分组：类似 mihomo 订阅接力，两端都可自动优选\n" +
                    "· 依赖配置里已有 selector/urltest 分组；写入的是原生 chain outbound",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
