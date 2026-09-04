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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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

private const val CHAIN_TAG = "my-chain"

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
    val mergedTag: String get() = "ext-$profileId-$tag"
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
    var currentMainTag by remember { mutableStateOf<String?>(null) }
    var otherProfiles by remember { mutableStateOf<List<ProfileChoice>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }

    var front by remember { mutableStateOf<HopRef?>(null) }
    var exit by remember { mutableStateOf<HopRef?>(null) }
    var busy by remember { mutableStateOf(false) }
    var savedHint by remember { mutableStateOf<String?>(null) }
    var chainActive by remember { mutableStateOf(false) }

    var pickerRole by remember { mutableStateOf<String?>(null) }
    var pickerQuery by remember { mutableStateOf("") }
    var pickerProfileId by remember { mutableStateOf<Long?>(null) }

    fun reload() {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val selectedId = Settings.selectedProfile
                    if (selectedId == -1L) {
                        loadError = "请先在主页选择一个配置"
                        otherProfiles = emptyList()
                        currentProfilePath = null
                        return@withContext
                    }
                    val current = ProfileManager.get(selectedId) ?: run {
                        loadError = "找不到当前配置"
                        return@withContext
                    }
                    currentProfileName = current.name
                    currentProfilePath = current.typed.path

                    val file = File(current.typed.path)
                    val root = JSONObject(file.readText())
                    // Strip illegal custom fields that break official sing-box decoder
                    val routeObj = root.optJSONObject("route")
                    if (routeObj != null && routeObj.has("_chainbox_original_final")) {
                        routeObj.remove("_chainbox_original_final")
                        file.writeText(root.toString(2))
                    }
                    val outs = root.optJSONArray("outbounds") ?: JSONArray()
                    val route = root.optJSONObject("route")
                    val routeFinal = route?.optString("final")?.trim().orEmpty()

                    currentMainTag = resolveCurrentMainTag(outs, routeFinal)

                    val choices = mutableListOf<ProfileChoice>()
                    for (p in ProfileManager.list()) {
                        if (p.id == current.id) continue
                        val hops = parseHopsFromProfile(p)
                        if (hops.isNotEmpty()) choices.add(ProfileChoice(p.id, p.name, hops))
                    }
                    otherProfiles = choices

                    front = null
                    exit = null
                    chainActive = false
                    for (i in 0 until outs.length()) {
                        val o = outs.optJSONObject(i) ?: continue
                        if (o.optString("type") != "chain") continue
                        if (o.optString("tag") != CHAIN_TAG) continue
                        val hopsArr = o.optJSONArray("outbounds") ?: continue
                        val tags = (0 until hopsArr.length()).map { hopsArr.optString(it) }
                        val localIdx = tags.indexOfFirst { !it.startsWith("ext-") }
                        if (localIdx >= 0) {
                            currentMainTag = tags[localIdx]
                            for (j in 0 until localIdx) {
                                resolveMergedTag(tags[j], choices)?.let { front = it }
                            }
                            for (j in localIdx + 1 until tags.size) {
                                resolveMergedTag(tags[j], choices)?.let { exit = it }
                            }
                        } else {
                            if (tags.isNotEmpty()) front = resolveMergedTag(tags.first(), choices)
                            if (tags.size > 1) exit = resolveMergedTag(tags.last(), choices)
                        }
                        chainActive = true
                        savedHint = buildChainHint(front, currentMainTag, exit, currentProfileName)
                        break
                    }

                    loadError = when {
                        choices.isEmpty() -> "请先添加另一个订阅/配置，才能设置前置或落地"
                        currentMainTag == null -> "当前配置找不到可用主出口（需要 urltest/selector 或 route.final）"
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
        if (front == null && exit == null) {
            scope.launch { snackbar.showSnackbar("请至少选择前置或落地之一") }
            return
        }
        val main = currentMainTag
        if (main.isNullOrBlank()) {
            scope.launch { snackbar.showSnackbar("当前配置没有可用主出口") }
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
                    val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }

                    // Never write non-schema fields into route — sing-box rejects unknown keys
                    if (route.has("_chainbox_original_final")) {
                        route.remove("_chainbox_original_final")
                    }

                    front?.let { hop ->
                        val p = ProfileManager.get(hop.profileId)
                            ?: return@withContext Result.failure(IllegalStateException("找不到前置配置"))
                        outs = mergeHopTree(outs, hop, p.typed.path)
                    }
                    exit?.let { hop ->
                        val p = ProfileManager.get(hop.profileId)
                            ?: return@withContext Result.failure(IllegalStateException("找不到落地配置"))
                        outs = mergeHopTree(outs, hop, p.typed.path)
                    }

                    val hopArr = JSONArray()
                    front?.let { hopArr.put(it.mergedTag) }
                    hopArr.put(main)
                    exit?.let { hopArr.put(it.mergedTag) }
                    if (hopArr.length() < 2) {
                        return@withContext Result.failure(IllegalStateException("链路至少需要两跳"))
                    }

                    val cleaned = JSONArray()
                    for (i in 0 until outs.length()) {
                        val o = outs.optJSONObject(i) ?: continue
                        if (o.optString("tag") == CHAIN_TAG) continue
                        cleaned.put(o)
                    }
                    val chain = JSONObject()
                    chain.put("type", "chain")
                    chain.put("tag", CHAIN_TAG)
                    chain.put("outbounds", hopArr)
                    cleaned.put(chain)
                    root.put("outbounds", cleaned)
                    route.put("final", CHAIN_TAG)

                    file.writeText(root.toString(2))
                    Result.success(Unit)
                } catch (ex: Exception) {
                    Result.failure(ex)
                }
            }
            busy = false
            if (result.isSuccess) {
                chainActive = true
                savedHint = buildChainHint(front, currentMainTag, exit, currentProfileName)
                snackbar.showSnackbar("已固定为链式出口。除非手动取消，否则一直生效。请重启服务")
            } else {
                snackbar.showSnackbar("保存失败: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun clearChain() {
        val path = currentProfilePath ?: return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val file = File(path)
                    val root = JSONObject(file.readText())
                    val outs = root.optJSONArray("outbounds") ?: JSONArray()
                    val cleaned = JSONArray()
                    for (i in 0 until outs.length()) {
                        val o = outs.optJSONObject(i) ?: continue
                        if (o.optString("tag") == CHAIN_TAG) continue
                        cleaned.put(o)
                    }
                    root.put("outbounds", cleaned)
                    val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
                    if (route.has("_chainbox_original_final")) {
                        route.remove("_chainbox_original_final")
                    }
                    if (route.optString("final") == CHAIN_TAG) {
                        currentMainTag?.let { route.put("final", it) }
                    }
                    file.writeText(root.toString(2))
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            busy = false
            if (result.isSuccess) {
                front = null
                exit = null
                chainActive = false
                savedHint = "已取消链式，恢复普通出口"
                snackbar.showSnackbar("已取消链式代理")
            } else {
                snackbar.showSnackbar("取消失败: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    if (pickerRole != null) {
        val isFront = pickerRole == "front"
        val title = if (isFront) "选择前置（其他订阅）" else "选择落地（其他订阅）"
        val q = pickerQuery.trim().lowercase()
        val expanded = otherProfiles.find { it.id == pickerProfileId }

        AlertDialog(
            onDismissRequest = { pickerRole = null; pickerQuery = ""; pickerProfileId = null },
            title = { Text(title) },
            text = {
                Column {
                    Text(
                        "与当前配置「$currentProfileName」自动组成链式。二选一即可。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    if (expanded != null) {
                        TextButton(onClick = { pickerProfileId = null }) { Text("← 返回订阅列表") }
                        OutlinedTextField(
                            value = pickerQuery,
                            onValueChange = { pickerQuery = it },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            placeholder = { Text("搜索") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        )
                        val hops = expanded.hops.filter {
                            q.isEmpty() || it.tag.lowercase().contains(q) || it.typeLabel.contains(q)
                        }
                        LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                            val groups = hops.filter { it.isGroup }
                            val nodes = hops.filter { !it.isGroup }
                            if (groups.isNotEmpty()) {
                                item { Text("分组", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 6.dp)) }
                                items(groups) { hop ->
                                    HopRow(hop) {
                                        if (isFront) front = hop else exit = hop
                                        pickerRole = null; pickerQuery = ""; pickerProfileId = null
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
                                        pickerRole = null; pickerQuery = ""; pickerProfileId = null
                                    }
                                }
                            }
                            if (hops.isEmpty()) item { Text("无匹配项") }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                            if (otherProfiles.isEmpty()) {
                                item { Text("没有其他订阅，请先添加") }
                            } else {
                                items(otherProfiles) { pc ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clickable { pickerProfileId = pc.id }.padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(pc.name, fontWeight = FontWeight.Medium)
                                            Text(
                                                "${pc.hops.count { it.isGroup }} 分组 · ${pc.hops.count { !it.isGroup }} 节点",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickerRole = null; pickerQuery = ""; pickerProfileId = null }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("链式代理") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { reload() }) { Icon(Icons.Default.Refresh, "刷新") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("当前配置（固定在链路中）", style = MaterialTheme.typography.labelMedium)
                    Text(currentProfileName.ifBlank { "（未选择）" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = when {
                            loadError != null -> loadError!!
                            savedHint != null -> savedHint!!
                            else -> "前置或落地任选其一，即可与当前配置组成链式；保存后固定生效，直到手动取消。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (loadError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (currentMainTag != null) {
                        Text(
                            "当前主出口：$currentMainTag",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            SelectField(
                title = "前置代理（可选）",
                subtitle = "其他订阅 → 当前配置（当前作落地）。只设前置也可以。",
                value = front?.displayLine ?: "不使用前置",
                onClick = { pickerQuery = ""; pickerProfileId = null; pickerRole = "front" },
                onClear = { front = null },
                showClear = front != null,
            )

            SelectField(
                title = "落地代理（可选）",
                subtitle = "当前配置 → 其他订阅（当前作前置）。只设落地也可以。",
                value = exit?.displayLine ?: "不使用落地",
                onClick = { pickerQuery = ""; pickerProfileId = null; pickerRole = "exit" },
                onClear = { exit = null },
                showClear = exit != null,
            )

            if (front != null || exit != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        buildChainHint(front, currentMainTag, exit, currentProfileName),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Button(
                onClick = { save() },
                enabled = !busy && (front != null || exit != null) && currentMainTag != null && currentProfilePath != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "保存中…" else "保存并固定为链式出口")
            }

            if (chainActive || front != null || exit != null) {
                OutlinedButton(
                    onClick = { clearChain() },
                    enabled = !busy && currentProfilePath != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("取消链式（恢复普通出口）")
                }
            }
        }
    }
}

private fun buildChainHint(front: HopRef?, main: String?, exit: HopRef?, currentName: String): String {
    val parts = mutableListOf<String>()
    front?.let { parts.add(it.displayLine) }
    parts.add("当前「$currentName」(${main ?: "?"})")
    exit?.let { parts.add(it.displayLine) }
    return "链路：${parts.joinToString(" → ")}"
}

private fun resolveCurrentMainTag(outs: JSONArray, routeFinal: String): String? {
    if (routeFinal.isNotEmpty() && routeFinal != CHAIN_TAG && !routeFinal.startsWith("ext-")) {
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (o.optString("tag") == routeFinal && o.optString("type") != "chain") {
                return routeFinal
            }
        }
    }
    var firstUrltest: String? = null
    var firstSelector: String? = null
    for (i in 0 until outs.length()) {
        val o = outs.optJSONObject(i) ?: continue
        val tag = o.optString("tag")
        val type = o.optString("type")
        if (tag.contains(":chain:") || tag.startsWith("ext-")) continue
        when (type) {
            "urltest" -> {
                if (tag.contains("自动") || tag.equals("auto", true)) return tag
                if (firstUrltest == null) firstUrltest = tag
            }
            "selector" -> {
                if (firstSelector == null) firstSelector = tag
            }
        }
    }
    return firstUrltest ?: firstSelector
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

private fun mergeHopTree(outs: JSONArray, hop: HopRef, sourcePath: String): JSONArray {
    val srcRoot = JSONObject(File(sourcePath).readText())
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
        if (clone.optString("type") == "selector" || clone.optString("type") == "urltest") {
            val members = clone.optJSONArray("outbounds") ?: JSONArray()
            val mapped = JSONArray()
            for (i in 0 until members.length()) {
                mapped.put("ext-${hop.profileId}-${members.optString(i)}")
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
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (showClear) {
                    IconButton(onClick = onClear) { Icon(Icons.Default.Clear, "清除") }
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        }
    }
}

@Composable
private fun HopRow(hop: HopRef, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(hop.tag, style = MaterialTheme.typography.bodyLarge)
            Text(hop.typeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
    }
}
