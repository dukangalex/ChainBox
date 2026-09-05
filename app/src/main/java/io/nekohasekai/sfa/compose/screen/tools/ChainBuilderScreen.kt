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

private const val LEGACY_CHAIN_TAG = "my-chain"

private data class HopRef(
    val profileId: Long,
    val profileName: String,
    val tag: String,
    val type: String,
) {
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

    fun reload() {
        scope.launch(Dispatchers.IO) {
            try {
                val selectedId = Settings.selectedProfile
                val profiles = ProfileManager.list()
                val current = profiles.find { it.id == selectedId }
                if (current == null) {
                    withContext(Dispatchers.Main) { loadError = "未选择配置" }
                    return@launch
                }
                val path = current.typed.path
                val root = JSONObject(File(path).readText())
                val outs = root.optJSONArray("outbounds") ?: JSONArray()
                val route = root.optJSONObject("route")
                val routeFinal = route?.optString("final")?.trim().orEmpty()
                val main = resolveCurrentMainTag(outs, routeFinal)
                val others = profiles.filter { it.id != selectedId }.mapNotNull { p ->
                    val hops = parseHopsFromProfile(p)
                    if (hops.isEmpty()) null else ProfileChoice(p.id, p.name, hops)
                }
                withContext(Dispatchers.Main) {
                    currentProfileName = current.name
                    currentProfilePath = path
                    currentMainTag = main
                    otherProfiles = others
                    loadError = null
                    chainActive = Settings.chainEnabled
                    if (routeFinal.startsWith("ext-")) {
                        chainActive = true
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { loadError = e.message }
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
                withContext(Dispatchers.IO) {
                    Settings.chainEnabled = true
                    Settings.chainLandingProfileId = landing.profileId
                    Settings.chainLandingTag = landing.tag
                }
                chainActive = true
                savedHint = "链路：当前「$currentProfileName」($currentMainTag) → ${landing.displayLine}"
                snackbar.showSnackbar("已固定落地链式。订阅更新后会自动保持")
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
                withContext(Dispatchers.IO) {
                    Settings.chainEnabled = false
                    Settings.chainLandingProfileId = -1L
                    Settings.chainLandingTag = ""
                }
                exit = null
                chainActive = false
                savedHint = "已取消链式，恢复普通出口"
                snackbar.showSnackbar("已取消链式代理")
            } else {
                snackbar.showSnackbar("取消失败: ${result.exceptionOrNull()?.message}")
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loadError != null) {
                Text("加载失败: $loadError", color = MaterialTheme.colorScheme.error)
            }
            Text("当前配置：$currentProfileName", fontWeight = FontWeight.Medium)
            Text("入口组：${currentMainTag ?: "(未识别)"}")
            savedHint?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Text("选择落地代理（保存后：当前 → 落地 → 目标）。订阅更新后会自动保持链式。")
            Button(
                onClick = { pickerOpen = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) { Text(exit?.displayLine ?: "选择落地") }
            Button(
                onClick = { save() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && exit != null,
            ) { Text("保存并固定为链式出口") }
            OutlinedButton(
                onClick = { clearChain() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && chainActive,
            ) { Text("取消链式（恢复普通出口）") }
        }
    }

    if (pickerOpen) {
        val allHops = otherProfiles.flatMap { it.hops }
        val q = pickerQuery.trim().lowercase()
        val filtered = if (q.isEmpty()) allHops else allHops.filter {
            it.tag.lowercase().contains(q) || it.profileName.lowercase().contains(q)
        }
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            title = { Text("选择落地") },
            text = {
                Column {
                    OutlinedTextField(
                        value = pickerQuery,
                        onValueChange = { pickerQuery = it },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("搜索") },
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(filtered) { hop ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        exit = hop
                                        pickerOpen = false
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(text = hop.tag)
                                    Text(
                                        text = hop.displayLine,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickerOpen = false }) { Text("关闭") }
            },
        )
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
                if (scoreTag(routeFinal, type) >= 40) return routeFinal
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
            for (i in 0 until members.length()) mergeTag(members.optString(i))
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
