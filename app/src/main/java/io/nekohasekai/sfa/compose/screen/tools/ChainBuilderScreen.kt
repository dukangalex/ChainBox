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
import androidx.compose.material.icons.filled.Search
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
            else -> "节点"
        }

    val displayLine: String
        get() = if (isGroup) "$tag · $typeLabel" else tag
}

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
    var savedHint by remember { mutableStateOf<String?>(null) }

    var pickerRole by remember { mutableStateOf<String?>(null) }
    var pickerQuery by remember { mutableStateOf("") }

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
                    var loadedFront: String? = null
                    var loadedExit: String? = null
                    var loadedChainTag: String? = null
                    var finalIsChain = false

                    val routeFinal = root.optJSONObject("route")?.optString("final")?.trim().orEmpty()

                    for (i in 0 until outs.length()) {
                        val o = outs.optJSONObject(i) ?: continue
                        val tag = o.optString("tag").trim()
                        val type = o.optString("type").trim()
                        if (tag.isEmpty()) continue

                        if (type == "chain") {
                            // Prefer chain that matches route.final, else first chain, else my-chain name
                            val hops = o.optJSONArray("outbounds")
                            if (hops != null && hops.length() >= 2) {
                                val prefer = when {
                                    routeFinal == tag -> true
                                    loadedChainTag == null -> true
                                    tag == chainTag -> true
                                    else -> false
                                }
                                if (prefer || loadedChainTag == null) {
                                    loadedChainTag = tag
                                    loadedFront = hops.optString(0)
                                    loadedExit = hops.optString(hops.length() - 1)
                                    if (routeFinal == tag) finalIsChain = true
                                }
                            }
                            continue
                        }

                        if (type in listOf("direct", "block", "dns")) continue
                        if (tag in listOf("direct", "block", "dns", "global")) continue
                        // skip internal synthetic tags
                        if (tag.contains(":chain:")) continue

                        val item = OutboundItem(tag, type)
                        if (item.isGroup) g.add(item) else n.add(item)
                    }

                    profileName = profile.name
                    profilePath = path
                    groups = g
                    nodes = n
                    if (loadedChainTag != null) {
                        chainTag = loadedChainTag!!
                        frontTag = loadedFront
                        exitTag = loadedExit
                        if (finalIsChain) setAsFinal = true
                        savedHint = "已加载配置中的链式：${loadedFront} → ${loadedExit}"
                    }
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
                    val file = File(path)
                    val root = JSONObject(file.readText())
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
                    file.writeText(root.toString(2))
                    // Verify write
                    val verify = JSONObject(file.readText())
                    val vOuts = verify.optJSONArray("outbounds") ?: JSONArray()
                    var found = false
                    for (i in 0 until vOuts.length()) {
                        val o = vOuts.optJSONObject(i) ?: continue
                        if (o.optString("tag") == chainTag.trim() && o.optString("type") == "chain") {
                            found = true
                            break
                        }
                    }
                    if (!found) Result.failure(IllegalStateException("写入后未找到 chain"))
                    else Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            busy = false
            if (result.isSuccess) {
                savedHint = "已保存：$front → $exit"
                snackbar.showSnackbar("保存成功。请回到仪表盘停止并重新启动服务")
            } else {
                snackbar.showSnackbar("保存失败: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    if (pickerRole != null) {
        val isFront = pickerRole == "front"
        val title = if (isFront) "选择前置代理" else "选择落地代理"
        val exclude = if (isFront) exitTag else frontTag
        val q = pickerQuery.trim().lowercase()
        fun match(item: OutboundItem): Boolean {
            if (item.tag == exclude) return false
            if (q.isEmpty()) return true
            return item.tag.lowercase().contains(q) || item.typeLabel.contains(q)
        }
        val filteredGroups = groups.filter { match(it) }
        val filteredNodes = nodes.filter { match(it) }

        AlertDialog(
            onDismissRequest = {
                pickerRole = null
                pickerQuery = ""
            },
            title = { Text(title) },
            text = {
                Column {
                    OutlinedTextField(
                        value = pickerQuery,
                        onValueChange = { pickerQuery = it },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        placeholder = { Text("搜索分组或节点") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                        if (filteredGroups.isNotEmpty()) {
                            item {
                                Text(
                                    "分组（推荐作前置，可自动优选）",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                            items(filteredGroups) { item ->
                                PickerRow(item) {
                                    if (isFront) frontTag = item.tag else exitTag = item.tag
                                    pickerRole = null
                                    pickerQuery = ""
                                }
                            }
                        }
                        if (filteredNodes.isNotEmpty()) {
                            item {
                                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                Text(
                                    "节点",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                            items(filteredNodes) { item ->
                                PickerRow(item) {
                                    if (isFront) frontTag = item.tag else exitTag = item.tag
                                    pickerRole = null
                                    pickerQuery = ""
                                }
                            }
                        }
                        if (filteredGroups.isEmpty() && filteredNodes.isEmpty()) {
                            item {
                                Text(
                                    if (q.isNotEmpty()) "没有匹配「$pickerQuery」的项"
                                    else "没有可选项目",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pickerRole = null
                    pickerQuery = ""
                }) { Text("取消") }
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
                    } else if (savedHint != null) {
                        Text(
                            savedHint!!,
                            color = MaterialTheme.colorScheme.primary,
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

            SelectField(
                title = "前置代理",
                subtitle = "入口。建议选「自动优选分组」。",
                value = labelOf(frontTag),
                onClick = {
                    pickerQuery = ""
                    pickerRole = "front"
                },
                onClear = { frontTag = null },
                showClear = frontTag != null,
            )

            SelectField(
                title = "落地代理",
                subtitle = "出口。可选分组或单个节点。",
                value = labelOf(exitTag),
                onClick = {
                    pickerQuery = ""
                    pickerRole = "exit"
                },
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
                        "开启后全局默认走这条链",
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
                Text(if (busy) "保存中…" else "保存到配置")
            }

            Text(
                "保存后请回到仪表盘：先停止服务，再启动。\n" +
                    "若启动报 rule-set / geoip 404，是订阅里规则集地址失效，与链式无关，需更新或删除那些 rule-set。",
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
