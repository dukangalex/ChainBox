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

// Marker only used to strip legacy type:chain configs from older builds
private const val LEGACY_CHAIN_TAG = "my-chain"

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

    var exit by remember { mutableStateOf<HopRef?>(null) }
    var busy by remember { mutableStateOf(false) }
    var savedHint by remember { mutableStateOf<String?>(null) }
    var chainActive by remember { mutableStateOf(false) }

    var pickerOpen by remember { mutableStateOf(false) }
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

                    exit = null
                    chainActive = false
                    savedHint = null

                    if (routeFinal.startsWith("ext-")) {
                        resolveMergedTag(routeFinal, choices)?.let { hop ->
                            exit = hop
                            chainActive = true
                            savedHint = "链路：当前「$currentProfileName」($currentMainTag) → ${hop.displayLine}"
                        }
                    } else {
                        for (i in 0 until outs.length()) {
                            val o = outs.optJSONObject(i) ?: continue
                            if (o.optString("type") == "chain" && o.optString("tag") == LEGACY_CHAIN_TAG) {
                                chainActive = true
                                savedHint = "检测到旧版链式配置，请点「取消链式」后重新选择落地"
                                break
                            }
                            val tag = o.optString("tag")
                            if (tag.startsWith("ext-") && o.optString("detour").isNotEmpty()) {
                                resolveMergedTag(tag, choices)?.let { hop ->
                                    exit = hop
                                    chainActive = true
                                    savedHint = "链路：当前「$currentProfileName」($currentMainTag) → ${hop.displayLine}"
                                }
                            }
                        }
                    }

                    loadError = when {
                        choices.isEmpty() -> "请先添加另一个订阅/配置，才能设置落地"
                        currentMainTag == null -> "当前配置找不到可用入口分组（节点选择/自动选择等）"
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
        val landing = exit
        if (landing == null) {
            scope.launch { snackbar.showSnackbar("请先选择落地代理") }
            return
        }
        val main = currentMainTag
        if (main.isNullOrBlank()) {
            scope.launch { snackbar.showSnackbar("当前配置没有可用入口") }
            return
        }
        val path = currentProfilePath ?: return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val file = File(path)
                    val root = JSONObject(file.readText())
                    var outs = root.optJSONArray("outbounds") ?: JSONArray()
                    val cleanedBase = JSONArray()
                    for (i in 0 until outs.length()) {
                        val o = outs.optJSONObject(i) ?: continue
                        val tag = o.optString("tag")
                        if (tag == LEGACY_CHAIN_TAG) continue
                        if (tag.startsWith("ext-")) continue
                        if (o.has("detour") && o.optString("detour").startsWith("ext-")) {
                            o.remove("detour")
                        }
                        cleanedBase.put(o)
                    }
                    outs = cleanedBase
                    root.put("outbounds", outs)
                    val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
                    if (route.has("_chainbox_original_final")) route.remove("_chainbox_original_final")

                    val p = ProfileManager.get(landing.profileId)
                        ?: return@withContext Result.failure(IllegalStateException("找不到落地配置"))
                    outs = mergeHopTree(outs, landing, p.typed.path)

                    // App → current(main) → landing → target
                    applyDetourToLeaves(outs, landing.mergedTag, main)

                    root.put("outbounds", outs)
                    route.put("final", landing.mergedTag)

                    file.writeText(root.toString(2))
                    Result.success(Unit)
                } catch (ex: Exception) {
                    Result.failure(ex)
                }
            }
            busy = false
            if (result.isSuccess) {
                chainActive = true
                savedHint = "链路：当前「$currentProfileName」($currentMainTag) → ${landing.displayLine}"
                snackbar.showSnackbar("已固定落地链式。请重启服务")
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
                        val tag = o.optString("tag")
                        if (tag == LEGACY_CHAIN_TAG) continue
                        if (tag.startsWith("ext-")) continue
                        if (o.has("detour") && o.optString("detour").startsWith("ext-")) {
                            o.remove("detour")
                        }
                        cleaned.put(o)
                    }
                    root.put("outbounds", cleaned)
                    val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
                    if (route.has("_chainbox_original_final")) route.remove("_chainbox_original_final")
                    val fin = route.optString("final")
                    if (fin == LEGACY_CHAIN_TAG || fin.startsWith("ext-")) {
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
                exit = null
                chainActive = false
                savedHint = "已取消链式，恢复普通出口"
                snackbar.showSnackbar("已取消链式代理")
            } else {
                snackbar.showSnackbar("取消失败: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    if (pickerOpen) {
        val q = pickerQuery.trim().lowercase()
        val expanded = otherProfiles.find { it.id == pickerProfileId }

        AlertDialog(
            onDismissRequest = { pickerOpen = false; pickerQuery = ""; pickerProfileId = null },
            title = { Text("选择落地（其他订阅）") },
            text = {
                Column {
                    Text(
                        "流量路径：当前配置 → 落地 → 目标。对齐 v2rayNG/NekoBox（detour）。",
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
                                        exit = hop
                                        pickerOpen = false; pickerQuery = ""; pickerProfileId = null
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
                                        exit = hop
                                        pickerOpen = false; pickerQuery = ""; pickerProfileId = null
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
                                        Column(modifier = Modifier.weight(1f)) {
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
                TextButton(onClick = { pickerOpen = false; pickerQuery = ""; pickerProfileId = null }) { Text("取消") }
            },
        )
        return
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
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("当前配置 = 入口（可在仪表盘切换节点）", style = MaterialTheme.typography.labelMedium)
                    Text(currentProfileName.ifBlank { "（未选择）" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = when {
                            loadError != null -> loadError!!
                            savedHint != null -> savedHint!!
                            else -> "只选落地即可。保存后：当前节点 → 落地 → 目标（与 v2rayNG/NekoBox 相同，用 detour）"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (loadError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (currentMainTag != null) {
                        Text(
                            "入口分组 : $currentMainTag",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            SelectField(
                title = "落地代理",
                subtitle = "其他订阅的节点或分组。流量最后从这里出去。",
                value = exit?.displayLine ?: "点击选择落地",
                onClick = { pickerOpen = true },
                onClear = { exit = null },
                showClear = exit != null,
            )

            if (exit != null && currentMainTag != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "链路：当前「$currentProfileName」($currentMainTag) → ${exit!!.displayLine}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            Button(
                onClick = { save() },
                enabled = !busy && exit != null && currentMainTag != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存并固定为链式出口")
            }

            OutlinedButton(
                onClick = { clearChain() },
                enabled = !busy && chainActive,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("取消链式（恢复普通出口）")
            }
        }
    }
}

private fun resolveCurrentMainTag(outs: JSONArray, routeFinal: String): String? {
    fun scoreTag(tag: String, type: String): Int {
        var s = 50
        if (type == "urltest") s += 20
        if (type == "selector") s += 15
        val t = tag.lowercase()
        if (t.contains("漏网") || t.contains("final") || t.contains("剩余")) s -= 80
        if (t.contains("节点") || t.contains("选择") || t.contains("自动") || t.contains("proxy") || t.contains("select")) s += 25
        if (t.equals("global", true)) s += 5
        return s
    }

    data class Cand(val tag: String, val score: Int)
    val cands = mutableListOf<Cand>()
    for (i in 0 until outs.length()) {
        val o = outs.optJSONObject(i) ?: continue
        val tag = o.optString("tag").trim()
        val type = o.optString("type").trim()
        if (tag.isEmpty() || tag == LEGACY_CHAIN_TAG || tag.startsWith("ext-")) continue
        if (type != "selector" && type != "urltest") continue
        cands.add(Cand(tag, scoreTag(tag, type)))
    }
    if (routeFinal.isNotEmpty() && routeFinal != LEGACY_CHAIN_TAG && !routeFinal.startsWith("ext-")) {
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (o.optString("tag") != routeFinal) continue
            val type = o.optString("type")
            if (type == "selector" || type == "urltest") {
                val sc = scoreTag(routeFinal, type)
                if (sc >= 40) return routeFinal
            }
        }
    }
    return cands.maxByOrNull { it.score }?.tag
}

private fun parseHopsFromProfile(profile: Profile): List<HopRef> {
    return try {
        val root = JSONObject(File(profile.typed.path).readText())
        val outs = root.optJSONArray("outbounds") ?: return emptyList()
        val list = mutableListOf<HopRef>()
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            val tag = o.optString("tag").trim()
            val type = o.optString("type").trim()
            if (tag.isEmpty()) continue
            if (type in listOf("direct", "block", "dns", "chain")) continue
            if (tag.contains(":chain:")) continue
            list.add(HopRef(profile.id, profile.name, tag, type))
        }
        list
    } catch (_: Exception) {
        emptyList()
    }
}

private fun resolveMergedTag(mergedOrRaw: String, choices: List<ProfileChoice>): HopRef? {
    if (!mergedOrRaw.startsWith("ext-")) return null
    val rest = mergedOrRaw.removePrefix("ext-")
    val dash = rest.indexOf('-')
    if (dash <= 0) return null
    val idStr = rest.substring(0, dash)
    val tag = rest.substring(dash + 1)
    val id = idStr.toLongOrNull() ?: return null
    val pc = choices.find { it.id == id } ?: return null
    return pc.hops.find { it.tag == tag }
}

private fun findOutbound(outs: JSONArray, tag: String): JSONObject? {
    for (i in 0 until outs.length()) {
        val o = outs.optJSONObject(i) ?: continue
        if (o.optString("tag") == tag) return o
    }
    return null
}

private fun applyDetourToLeaves(outs: JSONArray, rootTag: String, detourTag: String) {
    val visiting = mutableSetOf<String>()
    fun walk(tag: String) {
        if (tag.isEmpty() || !visiting.add(tag)) return
        val o = findOutbound(outs, tag) ?: return
        val type = o.optString("type")
        if (type == "selector" || type == "urltest") {
            val members = o.optJSONArray("outbounds") ?: return
            for (i in 0 until members.length()) walk(members.optString(i))
            return
        }
        if (type == "direct" || type == "block" || type == "dns" || type == "chain") return
        o.put("detour", detourTag)
    }
    walk(rootTag)
}

private fun mergeHopTree(outs: JSONArray, hop: HopRef, sourcePath: String): JSONArray {
    val srcRoot = try {
        JSONObject(File(sourcePath).readText())
    } catch (_: Exception) {
        return outs
    }
    val srcOuts = srcRoot.optJSONArray("outbounds") ?: return outs
    val visiting = mutableSetOf<String>()

    fun alreadyHas(tag: String): Boolean {
        for (i in 0 until outs.length()) {
            if (outs.optJSONObject(i)?.optString("tag") == tag) return true
        }
        return false
    }

    fun findSrc(tag: String): JSONObject? {
        for (i in 0 until srcOuts.length()) {
            val o = srcOuts.optJSONObject(i) ?: continue
            if (o.optString("tag") == tag) return o
        }
        return null
    }

    fun putClone(src: JSONObject, newTag: String) {
        if (alreadyHas(newTag)) return
        val clone = JSONObject(src.toString())
        clone.put("tag", newTag)
        if (clone.has("detour")) clone.remove("detour")
        val type = clone.optString("type")
        if (type == "selector" || type == "urltest") {
            val members = clone.optJSONArray("outbounds") ?: JSONArray()
            val mapped = JSONArray()
            for (i in 0 until members.length()) {
                val m = members.optString(i)
                if (m.isNotEmpty()) mapped.put("ext-${hop.profileId}-$m")
            }
            clone.put("outbounds", mapped)
            if (clone.has("default")) {
                val d = clone.optString("default")
                if (d.isNotEmpty()) clone.put("default", "ext-${hop.profileId}-$d")
            }
        }
        outs.put(clone)
    }

    fun mergeTag(tag: String) {
        if (tag.isEmpty() || tag == "direct" || tag == "block" || tag == "dns") return
        if (!visiting.add(tag)) return
        val src = findSrc(tag) ?: return
        val type = src.optString("type")
        if (type == "chain") return
        val newTag = "ext-${hop.profileId}-$tag"
        if (alreadyHas(newTag)) {
            visiting.remove(tag)
            return
        }
        if (type == "selector" || type == "urltest") {
            val members = src.optJSONArray("outbounds") ?: JSONArray()
            for (i in 0 until members.length()) {
                mergeTag(members.optString(i))
            }
            val def = src.optString("default")
            if (def.isNotEmpty()) mergeTag(def)
        }
        val detour = src.optString("detour")
        if (detour.isNotEmpty()) mergeTag(detour)
        putClone(src, newTag)
        visiting.remove(tag)
    }

    mergeTag(hop.tag)
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
