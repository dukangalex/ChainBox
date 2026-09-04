package io.nekohasekai.sfa.compose.screen.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            "urltest" -> "自动优选分组"
            "selector" -> "手动选择分组"
            else -> type.ifBlank { "节点" }
        }

    val displayLine: String
        get() = if (isGroup) "$tag · $typeLabel" else tag
}

/**
 * v2rayNG-style chain editor:
 * - 前置代理：点选 → 弹窗选分组/节点（入口）
 * - 落地代理：点选 → 弹窗选分组/节点（出口）
 * Writes native type:chain outbound into current profile.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var frontTag by remember { mutableStateOf<String?>(null) }
    var exitTag by remember { mutableStateOf<String?>(null) }
    var setAsFinal by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    // null = closed; "front" / "exit" = which field is picking
    var pickerRole by remember { mutableStateOf<String?>(null) }

    fun allItems(): List<OutboundItem> = groups + nodes

    fun labelOf(tag: String?): String {
        if (tag.isNullOrBlank()) return "点击选择"
        return allItems().find { it.tag == tag }?.displayLine ?: tag
    }

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
                    val profile = ProfileManager.get(id) ?: run {
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
                        if (type in listOf("direct", "block", "dns", "chain")) continue
                        if (tag in listOf("direct", "block", "dns", "global")) continue
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

    fun saveToProfile() {
        val front = frontTag
        val exit = exitTag
        if (front.isNullOrBlank() || exit.isNullOrBlank()) {
            scope.launch { snackbar.showSnackbar("请选择前置代理和落地代理") }
            return
        }
        if (front == exit) {
            scope.launch { snackbar.showSnackbar("前置和落地不能相同") }
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
                    hopArr.put(front)
                    hopArr.put(exit)
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
                snackbar.showSnackbar("已保存。请停止服务后重新启动")
            } else {
                snackbar.showSnackbar("保存失败: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    if (pickerRole != null) {
        val isFront = pickerRole == "front"
        val title = if (isFront) "选择前置代理" else "选择落地代理"
        val exclude = if (isFront) exitTag else frontTag
        AlertDialog(
            onDismissRequest = { pickerRole = null },
            title = { Text(title) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    if (groups.isNotEmpty()) {
                        item {
                            Text(
                                "订阅分组（可自动优选）",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        items(groups.filter { it.tag != exclude }) { item ->
                            PickerRow(item) {
                                if (isFront) frontTag = item.tag else exitTag = item.tag
                                pickerRole = null
                            }
                        }
                    }
                    if (nodes.isNotEmpty()) {
                        item {
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            Text(
                                "节点",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        items(nodes.filter { it.tag != exclude }) { item ->
                            PickerRow(item) {
                                if (isFront) frontTag = item.tag else exitTag = item.tag
                                pickerRole = null
                            }
                        }
                    }
                    if (groups.isEmpty() && nodes.isEmpty()) {
                        item {
                            Text(
                                "没有可选项目。请先在配置中添加分组或节点。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickerRole = null }) { Text("取消") }
            },
        )
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("当前配置", style = MaterialTheme.typography.labelMedium)
                    Text(
                        profileName.ifBlank { "（未选择）" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (loadError != null) {
                        Text(
                            loadError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
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

            // 前置 — v2rayNG style
            SelectField(
                title = "前置代理",
                subtitle = "作为代理链入口。可选自动优选分组或节点。",
                value = labelOf(frontTag),
                onClick = { pickerRole = "front" },
                onClear = { frontTag = null },
                showClear = frontTag != null,
            )

            // 落地 — v2rayNG style
            SelectField(
                title = "落地代理",
                subtitle = "作为代理链出口。可选分组或单节点。",
                value = labelOf(exitTag),
                onClick = { pickerRole = "exit" },
                onClear = { exitTag = null },
                showClear = exitTag != null,
            )

            if (frontTag != null && exitTag != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "链路：${labelOf(frontTag)}  →  ${labelOf(exitTag)}",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("设为默认出口")
                    Text(
                        "开启后 route.final 指向这条链",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = setAsFinal, onCheckedChange = { setAsFinal = it })
            }

            Button(
                onClick = { saveToProfile() },
                enabled = !busy && frontTag != null && exitTag != null && chainTag.isNotBlank() && profilePath != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "保存中…" else "保存")
            }

            Text(
                "说明：类似 v2rayNG——点「前置 / 落地」弹出选择列表；分组可保留 urltest 自动优选。保存后请重启服务。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SelectField(
    title: String,
    subtitle: String,
    value: String,
    onClick: () -> Unit,
    onClear: () -> Unit,
    showClear: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = value,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (showClear) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Clear, contentDescription = "清除")
                    }
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PickerRow(item: OutboundItem, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.tag, style = MaterialTheme.typography.bodyLarge)
            Text(
                item.typeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
