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
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A selectable hop that may come from another profile. */
private data class HopRef(
    val profileId: Long,
    val profileName: String,
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
    /** Tag written into current config after merge. */
    val mergedTag: String get() = "ext-${profileId}-$tag"
    val displayLine: String get() = "$profileName / $tag · $typeLabel"
}

private data class ProfileChoice(
    val id: Long,
    val name: String,
    val hops: List<HopRef>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChainBuilderScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var currentProfileName by remember { mutableStateOf("") }
    var currentProfilePath by remember { mutableStateOf<String?>(null) }
    var currentProfileId by remember { mutableStateOf(-1L) }
    var otherProfiles by remember { mutableStateOf<List<ProfileChoice>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }

    var chainTag by remember { mutableStateOf("my-chain") }
    var front by remember { mutableStateOf<HopRef?>(null) }
    var exit by remember { mutableStateOf<HopRef?>(null) }
    var setAsFinal by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var savedHint by remember { mutableStateOf<String?>(null) }

    // picker: null closed; "front"/"exit" open
    var pickerRole by remember { mutableStateOf<String?>(null) }
    var pickerQuery by remember { mutableStateOf("") }
    // which other profile is expanded in picker; null = list profiles first
    var pickerProfileId by remember { mutableStateOf<Long?>(null) }

    fun reload() {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val selectedId = Settings.selectedProfile
                    if (selectedId == -1L) {
                        loadError = "请先在主页选择一个配置（作为「当前配置」写入链式）"
                        otherProfiles = emptyList()
                        currentProfilePath = null
                        return@withContext
                    }
                    val current = ProfileManager.get(selectedId) ?: run {
                        loadError = "找不到当前配置"
                        return@withContext
                    }
                    currentProfileId = current.id
                    currentProfileName = current.name
                    currentProfilePath = current.typed.path

                    // Load other profiles only — 候选不能来自当前配置
                    val all = ProfileManager.list()
                    val choices = mutableListOf<ProfileChoice>()
                    for (p in all) {
                        if (p.id == current.id) continue
                        val hops = parseHopsFromProfile(p)
                        if (hops.isNotEmpty()) {
                            choices.add(ProfileChoice(p.id, p.name, hops))
                        }
                    }
                    otherProfiles = choices

                    // Reload saved chain from current config if present
                    val root = JSONObject(File(current.typed.path).readText())
                    val outs = root.optJSONArray("outbounds") ?: JSONArray()
                    val routeFinal = root.optJSONObject("route")?.optString("final")?.trim().orEmpty()
                    for (i in 0 until outs.length()) {
                        val o = outs.optJSONObject(i) ?: continue
                        if (o.optString("type") != "chain") continue
                        val tag = o.optString("tag")
                        val hopsArr = o.optJSONArray("outbounds") ?: continue
                        if (hopsArr.length() < 2) continue
                        if (routeFinal == tag || tag == chainTag || tag == "my-chain") {
                            chainTag = tag
                            val fTag = hopsArr.optString(0)
                            val eTag = hopsArr.optString(hopsArr.length() - 1)
                            front = resolveMergedTag(fTag, choices)
                            exit = resolveMergedTag(eTag, choices)
                            if (routeFinal == tag) setAsFinal = true
                            savedHint = "已加载链式：$fTag → $eTag"
                            break
                        }
                    }

                    loadError = when {
                        choices.isEmpty() -> "没有其他订阅/配置可选。请先添加另一个配置，再来组链。"
                        else -> null
                    }
                } catch (e: Exception) {
                    loadError = "读取失败: ${e.message}"
                    otherProfiles = emptyList()
                }
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun save() {
        val f = front
        val e = exit
        if (f == null || e == null) {
            scope.launch { snackbar.showSnackbar("请选择前置和落地（均须来自其他配置）") }
            return
        }
        if (f.mergedTag == e.mergedTag && f.profileId == e.profileId) {
            scope.launch { snackbar.showSnackbar("前置和落地不能完全相同") }
            return
        }
        if (chainTag.isBlank()) {
            scope.launch { snackbar.showSnackbar("请填写链条名称") }
            return
        }
        val path = currentProfilePath ?: return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val file = File(path)
                    val root = JSONObject(file.readText())
                    var outs = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }

                    // Merge needed outbounds from other profiles into current config
                    outs = mergeHopTree(outs, f)
                    outs = mergeHopTree(outs, e)

                    // Remove old chain with same tag
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
                    hopArr.put(f.mergedTag)
                    hopArr.put(e.mergedTag)
                    chain.put("outbounds", hopArr)
                    cleaned.put(chain)
                    root.put("outbounds", cleaned)

                    if (setAsFinal) {
                        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
                        route.put("final", chainTag.trim())
                    }
                    file.writeText(root.toString(2))
                    Result.success(Unit)
                } catch (ex: Exception) {
                    Result.failure(ex)
                }
            }
            busy = false
            if (result.isSuccess) {
                savedHint = "已保存：${f.displayLine} → ${e.displayLine}"
                snackbar.showSnackbar("保存成功。请停止并重新启动服务")
            } else {
                snackbar.showSnackbar("保存失败: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    // —— Picker dialog: other profiles only ——
    if (pickerRole != null) {
        val isFront = pickerRole == "front"
        val title = if (isFront) "选择前置代理（其他订阅）" else "选择落地代理（其他订阅）"
        val q = pickerQuery.trim().lowercase()
        val expanded = otherProfiles.find { it.id == pickerProfileId }

        AlertDialog(
            onDismissRequest = {
                pickerRole = null
                pickerQuery = ""
                pickerProfileId = null
            },
            title = { Text(title) },
            text = {
                Column {
                    Text(
                        "仅显示其他配置中的分组/节点，不包含当前配置「$currentProfileName」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    if (expanded != null) {
                        TextButton(onClick = { pickerProfileId = null }) {
                            Text("← 返回订阅列表")
                        }
                        OutlinedTextField(
                            value = pickerQuery,
                            onValueChange = { pickerQuery = it },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            placeholder = { Text("搜索") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                        )
                        val hops = expanded.hops.filter {
                            q.isEmpty() || it.tag.lowercase().contains(q) || it.typeLabel.contains(q)
                        }
                        LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                            val groups = hops.filter { it.isGroup }
                            val nodes = hops.filter { !it.isGroup }
                            if (groups.isNotEmpty()) {
                                item {
                                    Text("分组", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 6.dp))
                                }
                                items(groups) { hop ->
                                    HopRow(hop) {
                                        if (isFront) front = hop else exit = hop
                                        pickerRole = null
                                        pickerQuery = ""
                                        pickerProfileId = null
                                    }
                                }
                            }
                            if (nodes.isNotEmpty()) {
                                item {
                                    HorizontalDivider()
                                    Text("节点", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 6.dp))
                                }
                                items(nodes) { hop ->
                                    HopRow(hop) {
                                        if (isFront) front = hop else exit = hop
                                        pickerRole = null
                                        pickerQuery = ""
                                        pickerProfileId = null
                                    }
                                }
                            }
                            if (hops.isEmpty()) {
                                item { Text("无匹配项", style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                            if (otherProfiles.isEmpty()) {
                                item {
                                    Text("没有其他订阅。请先在主页添加另一个配置。", style = MaterialTheme.typography.bodyMedium)
                                }
                            } else {
                                items(otherProfiles) { pc ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { pickerProfileId = pc.id }
                                            .padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(pc.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                            Text(
                                                "${pc.hops.count { it.isGroup }} 个分组 · ${pc.hops.count { !it.isGroup }} 个节点",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pickerRole = null
                    pickerQuery = ""
                    pickerProfileId = null
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
                    IconButton(onClick = { reload() }) {
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
                    Text("当前配置（写入目标）", style = MaterialTheme.typography.labelMedium)
                    Text(
                        currentProfileName.ifBlank { "（未选择）" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = loadError
                            ?: savedHint
                            ?: "前置/落地只能从「其他订阅」选择，不会出现当前配置里的节点。",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (loadError != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
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
                subtitle = "从其他订阅中选择入口（分组可自动优选）",
                value = front?.displayLine ?: "点击选择其他订阅",
                onClick = {
                    pickerQuery = ""
                    pickerProfileId = null
                    pickerRole = "front"
                },
                onClear = { front = null },
                showClear = front != null,
            )

            SelectField(
                title = "落地代理",
                subtitle = "从其他订阅中选择出口",
                value = exit?.displayLine ?: "点击选择其他订阅",
                onClick = {
                    pickerQuery = ""
                    pickerProfileId = null
                    pickerRole = "exit"
                },
                onClear = { exit = null },
                showClear = exit != null,
            )

            if (front != null && exit != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "链路：${front!!.displayLine}\n　　→ ${exit!!.displayLine}",
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
                onClick = { save() },
                enabled = !busy && front != null && exit != null && chainTag.isNotBlank() && currentProfilePath != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "保存中…" else "保存到当前配置")
            }

            Text(
                "逻辑对齐 v2rayNG：前置/落地选的是「别的配置」里的分组或节点；保存时会合并进当前配置并生成 chain。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun parseHopsFromProfile(profile: Profile): List<HopRef> {
    return try {
        val root = JSONObject(File(profile.typed.path).readText())
        val outs = root.optJSONArray("outbounds") ?: return emptyList()
        val list = mutableListOf<HopRef>()
        val metaNames = setOf("节点选择", "自动选择", "手动切换", "proxy", "GLOBAL", "global", "direct", "block", "dns")
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            val tag = o.optString("tag").trim()
            val type = o.optString("type").trim()
            if (tag.isEmpty()) continue
            if (type in listOf("direct", "block", "dns", "chain")) continue
            if (tag in metaNames) continue
            if (tag.contains(":chain:")) continue
            list.add(HopRef(profile.id, profile.name, tag, type))
        }
        list
    } catch (_: Exception) {
        emptyList()
    }
}

private fun resolveMergedTag(mergedOrRaw: String, choices: List<ProfileChoice>): HopRef? {
    // ext-{profileId}-{originalTag}
    if (mergedOrRaw.startsWith("ext-")) {
        val rest = mergedOrRaw.removePrefix("ext-")
        val dash = rest.indexOf('-')
        if (dash > 0) {
            val pid = rest.substring(0, dash).toLongOrNull()
            val tag = rest.substring(dash + 1)
            if (pid != null) {
                return choices.flatMap { it.hops }.find { it.profileId == pid && it.tag == tag }
            }
        }
    }
    return choices.flatMap { it.hops }.find { it.tag == mergedOrRaw || it.mergedTag == mergedOrRaw }
}

/** Copy hop (+ group members) from source profile into outs with merged tags. */
private fun mergeHopTree(outs: JSONArray, hop: HopRef): JSONArray {
    val sourceProfile = runCatching {
        // Read via path from ProfileManager in caller context — open file from hop
        null
    }
    // Load source JSON by scanning outs is insufficient; load from disk
    val profile = kotlinx.coroutines.runBlocking { ProfileManager.get(hop.profileId) } ?: return outs
    val srcRoot = JSONObject(File(profile.typed.path).readText())
    val srcOuts = srcRoot.optJSONArray("outbounds") ?: return outs

    fun findSrc(tag: String): JSONObject? {
        for (i in 0 until srcOuts.length()) {
            val o = srcOuts.optJSONObject(i) ?: continue
            if (o.optString("tag") == tag) return o
        }
        return null
    }

    fun alreadyHas(tag: String): Boolean {
        for (i in 0 until outs.length()) {
            if (outs.optJSONObject(i)?.optString("tag") == tag) return true
        }
        return false
    }

    fun putClone(src: JSONObject, newTag: String) {
        if (alreadyHas(newTag)) return
        val clone = JSONObject(src.toString())
        clone.put("tag", newTag)
        // Remap group members to merged tags
        if (clone.optString("type") == "selector" || clone.optString("type") == "urltest") {
            val members = clone.optJSONArray("outbounds") ?: JSONArray()
            val mapped = JSONArray()
            for (i in 0 until members.length()) {
                val m = members.optString(i)
                mapped.put("ext-${hop.profileId}-$m")
            }
            clone.put("outbounds", mapped)
            if (clone.has("default")) {
                val d = clone.optString("default")
                if (d.isNotEmpty()) clone.put("default", "ext-${hop.profileId}-$d")
            }
        }
        outs.put(clone)
    }

    val src = findSrc(hop.tag) ?: return outs
    if (hop.isGroup) {
        val members = src.optJSONArray("outbounds") ?: JSONArray()
        for (i in 0 until members.length()) {
            val mTag = members.optString(i)
            val mSrc = findSrc(mTag) ?: continue
            putClone(mSrc, "ext-${hop.profileId}-$mTag")
        }
        putClone(src, hop.mergedTag)
    } else {
        putClone(src, hop.mergedTag)
    }
    return outs
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
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (showClear) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Clear, contentDescription = "清除")
                    }
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
    }
}

@Composable
private fun HopRow(hop: HopRef, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(hop.tag, style = MaterialTheme.typography.bodyLarge)
            Text(hop.typeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}
