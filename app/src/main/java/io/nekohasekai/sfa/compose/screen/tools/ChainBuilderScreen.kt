package io.nekohasekai.sfa.compose.screen.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * NekoBox-style chain builder:
 * 1) Load current profile outbounds as selectable nodes
 * 2) Tap to add hops in order (visual chain)
 * 3) One-tap write chain outbound into profile JSON
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChainBuilderScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var profileName by remember { mutableStateOf("") }
    var profilePath by remember { mutableStateOf<String?>(null) }
    var availableTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }

    var chainTag by remember { mutableStateOf("my-chain") }
    val hops = remember { mutableStateListOf<String>() }
    var setAsFinal by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    fun reloadProfile() {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val id = Settings.selectedProfile
                    if (id == -1L) {
                        loadError = "请先在主页选择一个配置"
                        availableTags = emptyList()
                        profilePath = null
                        profileName = ""
                        return@withContext
                    }
                    val profile = ProfileManager.get(id)
                    if (profile == null) {
                        loadError = "找不到当前配置"
                        availableTags = emptyList()
                        profilePath = null
                        return@withContext
                    }
                    val path = profile.typed.path
                    val text = File(path).readText()
                    val root = JSONObject(text)
                    val outs = root.optJSONArray("outbounds") ?: JSONArray()
                    val tags = mutableListOf<String>()
                    for (i in 0 until outs.length()) {
                        val o = outs.optJSONObject(i) ?: continue
                        val tag = o.optString("tag").trim()
                        val type = o.optString("type").trim()
                        if (tag.isEmpty()) continue
                        // Skip system / meta outbounds for hop picking
                        if (type in listOf("direct", "block", "dns", "selector", "urltest", "chain")) continue
                        if (tag in listOf("direct", "block", "dns", "global")) continue
                        if (!tags.contains(tag)) tags.add(tag)
                    }
                    profileName = profile.name
                    profilePath = path
                    availableTags = tags
                    loadError = if (tags.isEmpty()) "当前配置里没有可串联的节点（需要普通代理 outbound）" else null
                } catch (e: Exception) {
                    loadError = "读取配置失败: ${e.message}"
                    availableTags = emptyList()
                    profilePath = null
                }
            }
        }
    }

    LaunchedEffect(Unit) { reloadProfile() }

    fun saveToProfile() {
        if (hops.size < 2) {
            scope.launch { snackbar.showSnackbar("至少选择 2 个节点") }
            return
        }
        if (chainTag.isBlank()) {
            scope.launch { snackbar.showSnackbar("请填写链条名称") }
            return
        }
        val path = profilePath
        if (path == null) {
            scope.launch { snackbar.showSnackbar("没有可用配置") }
            return
        }
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val root = JSONObject(File(path).readText())
                    var outs = root.optJSONArray("outbounds")
                    if (outs == null) {
                        outs = JSONArray()
                        root.put("outbounds", outs)
                    }
                    // Remove existing chain with same tag
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
                snackbar.showSnackbar("已写入配置「$profileName」。重启服务后生效")
            } else {
                snackbar.showSnackbar("写入失败: ${result.exceptionOrNull()?.message}")
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
                        Icon(Icons.Default.Refresh, contentDescription = "刷新节点")
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
            // Profile status
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier = Modifier.padding(12.dp)) {
                    Text("当前配置", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = profileName.ifBlank { "（未选择）" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (loadError != null) {
                        Text(
                            text = loadError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    } else {
                        Text(
                            text = "点选下方节点加入链条（顺序 = 从上到下转发）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

            // Visual chain preview (NekoBox style order list)
            Text("链路预览", style = MaterialTheme.typography.titleSmall)
            if (hops.isEmpty()) {
                Text(
                    "尚未选择节点。从下面的节点列表点选即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                hops.forEachIndexed { index, tag ->
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
                                    text = "${index + 1}",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            Text(
                                text = tag,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
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
                                        val item = hops.removeAt(index)
                                        hops.add(index - 1, item)
                                    }
                                },
                                enabled = index > 0,
                            ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "上移") }
                            IconButton(
                                onClick = {
                                    if (index < hops.lastIndex) {
                                        val item = hops.removeAt(index)
                                        hops.add(index + 1, item)
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

            // Available nodes as chips (tap to add)
            Text("可选节点（点一下加入）", style = MaterialTheme.typography.titleSmall)
            if (availableTags.isEmpty()) {
                Text(
                    "没有可选节点。请先在配置里添加普通代理节点。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    availableTags.forEach { tag ->
                        val selected = hops.contains(tag)
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (selected) {
                                    hops.remove(tag)
                                } else {
                                    hops.add(tag)
                                }
                            },
                            label = { Text(tag) },
                            leadingIcon = if (selected) {
                                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null,
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("设为默认出口 (route.final)", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "开启后，未匹配规则的流量走这条链",
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
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = hops.isNotEmpty(),
            ) {
                Text("清空链路")
            }

            Text(
                text = "说明：类似 NekoBox——点选节点组成链条，保存后自动写入配置，无需手写 JSON。修改后请停止再启动服务。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
