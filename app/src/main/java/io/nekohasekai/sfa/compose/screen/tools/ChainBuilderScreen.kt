package io.nekohasekai.sfa.compose.screen.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.chain.ChainRuntimeCompiler
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val CHAIN_TAG_PREFIX = "chainbox-chain-"

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
    val displayLine: String get() = "$profileName / $tag · $typeLabel"
}

private data class ProfileChoice(val id: Long, val name: String, val hops: List<HopRef>)

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
                val root = JSONObject(File(current.typed.path).readText())
                val outs = root.optJSONArray("outbounds") ?: JSONArray()
                val routeFinal = root.optJSONObject("route")?.optString("final")?.trim().orEmpty()
                val main = resolveCurrentMainTag(outs, routeFinal)
                val others = profiles.filter { it.id != selectedId }.mapNotNull { p ->
                    val hops = parseHopsFromProfile(p)
                    if (hops.isEmpty()) null else ProfileChoice(p.id, p.name, hops)
                }
                withContext(Dispatchers.Main) {
                    currentProfileName = current.name
                    currentProfilePath = current.typed.path
                    currentMainTag = main
                    otherProfiles = others
                    loadError = null
                    chainActive = Settings.chainEnabled || routeFinal.startsWith(CHAIN_TAG_PREFIX)
                    if (chainActive && Settings.chainLandingProfileId >= 0L && Settings.chainLandingTag.isNotBlank()) {
                        val found = others.flatMap { it.hops }.find {
                            it.profileId == Settings.chainLandingProfileId && it.tag == Settings.chainLandingTag
                        }
                        exit = found ?: HopRef(
                            Settings.chainLandingProfileId,
                            profiles.find { it.id == Settings.chainLandingProfileId }?.name ?: "落地",
                            Settings.chainLandingTag,
                            "selector",
                        )
                        savedHint = "链路：当前「${current.name}」(${main ?: "?"}) → ${exit?.displayLine}"
                    } else if (!chainActive) {
                        exit = null
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { loadError = e.message ?: "配置读取失败" }
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun save() {
        val landing = exit ?: run {
            scope.launch { snackbar.showSnackbar("请先选择落地代理") }
            return
        }
        val main = currentMainTag ?: run {
            scope.launch { snackbar.showSnackbar("当前配置没有可用入口") }
            return
        }
        val path = currentProfilePath ?: return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(path)
                    val root = JSONObject(file.readText())
                    val route = root.optJSONObject("route")
                    val originalFinal = route?.optString("final")?.takeIf { it.isNotBlank() && !it.startsWith(CHAIN_TAG_PREFIX) }
                    val compiled = ChainRuntimeCompiler.apply(root.toString(), Settings.selectedProfile)
                    file.writeText(compiled)
                    if (originalFinal != null && Settings.chainBoundProfileId < 0L) {
                        Settings.chainBoundProfileId = Settings.selectedProfile
                    }
                }
            }
            busy = false
            if (result.isSuccess) {
                withContext(Dispatchers.IO) {
                    Settings.chainEnabled = true
                    Settings.chainLandingProfileId = landing.profileId
                    Settings.chainLandingTag = landing.tag
                    Settings.chainBoundProfileId = Settings.selectedProfile
                }
                chainActive = true
                savedHint = "链路：当前「$currentProfileName」($main) → ${landing.displayLine}"
                snackbar.showSnackbar("已保存原生 Chain 链路，订阅更新会重新编译")
            } else {
                snackbar.showSnackbar("保存失败：${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun clearChain() {
        val path = currentProfilePath ?: return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(path)
                    val root = JSONObject(file.readText())
                    val final = resolveCurrentMainTag(root.optJSONArray("outbounds") ?: JSONArray(), "")
                    file.writeText(ChainRuntimeCompiler.clear(root.toString(), final))
                }
            }
            busy = false
            if (result.isSuccess) {
                withContext(Dispatchers.IO) {
                    Settings.chainEnabled = false
                    Settings.chainLandingProfileId = -1L
                    Settings.chainLandingTag = ""
                    Settings.chainBoundProfileId = -1L
                }
                exit = null
                chainActive = false
                savedHint = "已取消链式，恢复普通出口"
                snackbar.showSnackbar("已取消链式代理")
            } else {
                snackbar.showSnackbar("取消失败：${result.exceptionOrNull()?.message}")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("链式代理") },
                navigationIcon = { IconButton(onClick = { navController.navigateUp() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { IconButton(onClick = { reload() }) { Icon(Icons.Default.Refresh, "刷新") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loadError != null) Text("加载失败: $loadError", color = MaterialTheme.colorScheme.error)
            Text("当前配置：$currentProfileName", fontWeight = FontWeight.Medium)
            Text("入口组：${currentMainTag ?: "(未识别)"}")
            savedHint?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Text("选择落地代理。保存后使用 sing-box 原生 Chain：当前入口 → 落地 → 目标。链式失败不降级为 DIRECT。")
            Button(onClick = { pickerOpen = true }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                Text(exit?.displayLine ?: "选择落地")
            }
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth(), enabled = !busy && exit != null) {
                Text("保存并固定为链式出口")
            }
            OutlinedButton(onClick = { clearChain() }, modifier = Modifier.fillMaxWidth(), enabled = !busy && chainActive) {
                Text("取消链式（恢复普通出口）")
            }
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
                        label = { Text("搜索") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (pickerQuery.isNotEmpty()) IconButton(onClick = { pickerQuery = "" }) { Icon(Icons.Default.Clear, null) }
                        },
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(filtered) { hop ->
                            Column(
                                modifier = Modifier.fillMaxWidth().clickable { exit = hop; pickerOpen = false }.padding(vertical = 10.dp),
                            ) {
                                Text(hop.displayLine, fontWeight = FontWeight.Medium)
                                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickerOpen = false }) { Text("关闭") } },
        )
    }
}

private fun resolveCurrentMainTag(outs: JSONArray, routeFinal: String): String? {
    if (routeFinal.isNotBlank() && !routeFinal.startsWith(CHAIN_TAG_PREFIX)) {
        val o = findOutbound(outs, routeFinal)
        if (o != null && o.optString("type") in listOf("selector", "urltest")) return routeFinal
    }
    var best: String? = null
    var bestScore = Int.MIN_VALUE
    for (i in 0 until outs.length()) {
        val o = outs.optJSONObject(i) ?: continue
        val tag = o.optString("tag").trim()
        val type = o.optString("type")
        if (tag.isEmpty() || tag.startsWith(CHAIN_TAG_PREFIX) || type !in listOf("selector", "urltest")) continue
        var score = if (type == "urltest") 20 else 15
        val t = tag.lowercase()
        if (t.contains("漏网") || t.contains("final") || t.contains("剩余")) score -= 80
        if (t.contains("节点") || t.contains("选择") || t.contains("自动") || t.contains("proxy") || t.contains("select")) score += 25
        if (score > bestScore) { bestScore = score; best = tag }
    }
    return best
}

private fun parseHopsFromProfile(profile: Profile): List<HopRef> = try {
    val outs = JSONObject(File(profile.typed.path).readText()).optJSONArray("outbounds") ?: return emptyList()
    buildList {
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            val tag = o.optString("tag").trim()
            val type = o.optString("type").trim()
            if (tag.isEmpty()) continue
            if (type in listOf("direct", "block", "dns", "chain")) continue
            if (tag.startsWith(CHAIN_TAG_PREFIX) || tag.startsWith("chainbox-landing-") || tag.startsWith("ext-")) continue
            add(HopRef(profile.id, profile.name, tag, type))
        }
    }
} catch (_: Exception) { emptyList() }

private fun findOutbound(outs: JSONArray, tag: String): JSONObject? {
    for (i in 0 until outs.length()) if (outs.optJSONObject(i)?.optString("tag") == tag) return outs.optJSONObject(i)
    return null
}
