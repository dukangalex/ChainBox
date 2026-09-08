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
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
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
    var currentProfileId by remember { mutableStateOf(-1L) }
    var currentProfileName by remember { mutableStateOf("") }
    var currentProfilePath by remember { mutableStateOf<String?>(null) }
    var currentHops by remember { mutableStateOf<List<HopRef>>(emptyList()) }
    var allProfiles by remember { mutableStateOf<List<ProfileChoice>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var entry by remember { mutableStateOf<HopRef?>(null) }
    var exit by remember { mutableStateOf<HopRef?>(null) }
    var busy by remember { mutableStateOf(false) }
    var savedHint by remember { mutableStateOf<String?>(null) }
    var chainActive by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<String?>(null) }
    var pickerQuery by remember { mutableStateOf("") }
    var showHelp by remember { mutableStateOf(false) }

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
                val content = File(current.typed.path).readText()
                val root = JSONObject(content)
                val routeFinal = root.optJSONObject("route")?.optString("final")?.trim().orEmpty()
                val hops = ChainRuntimeCompiler.listSelectableHops(content, current.id, current.name).map {
                    HopRef(it.profileId, it.profileName, it.tag, it.type)
                }
                val suggested = ChainRuntimeCompiler.resolveMainTag(
                    root.optJSONArray("outbounds") ?: JSONArray(),
                    routeFinal,
                )
                val others = profiles.mapNotNull { p ->
                    val parsed = parseHopsFromProfile(p)
                    if (parsed.isEmpty()) null else ProfileChoice(p.id, p.name, parsed)
                }
                withContext(Dispatchers.Main) {
                    currentProfileId = current.id
                    currentProfileName = current.name
                    currentProfilePath = current.typed.path
                    currentHops = hops
                    allProfiles = others
                    loadError = null
                    chainActive = Settings.chainEnabled
                    val savedEntry = Settings.chainEntryTag.trim()
                    entry = hops.find { it.tag == savedEntry }
                        ?: hops.find { it.tag == suggested }
                        ?: hops.firstOrNull { !ChainRuntimeCompiler.isFinalLike(it.tag) }
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
                        savedHint = "链路：${entry?.tag ?: "?"} → ${exit?.displayLine}"
                    } else if (!chainActive) {
                        exit = null
                        savedHint = null
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
        val main = entry ?: run {
            scope.launch { snackbar.showSnackbar("请先选择入口分组或节点") }
            return
        }
        if (landing.profileId == currentProfileId && landing.tag == main.tag) {
            scope.launch { snackbar.showSnackbar("入口与落地不能是同一个 outbound") }
            return
        }
        val path = currentProfilePath ?: return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(path)
                    val raw = file.readText()
                    val originalFinal = JSONObject(raw).optJSONObject("route")
                        ?.optString("final")
                        ?.takeIf { it.isNotBlank() && !it.startsWith(ChainRuntimeCompiler.GENERATED_PREFIX) }
                    val landingContent = if (landing.profileId == Settings.selectedProfile) {
                        null
                    } else {
                        val landingProfile = ProfileManager.get(landing.profileId)
                            ?: error("落地配置不存在")
                        File(landingProfile.typed.path).readText()
                    }
                    ChainRuntimeCompiler.apply(
                        ChainRuntimeCompiler.ApplyRequest(
                            content = raw,
                            currentProfileId = Settings.selectedProfile,
                            entryTag = main.tag,
                            landingProfileId = landing.profileId,
                            landingTag = landing.tag,
                            landingContent = landingContent,
                        ),
                    )
                    file.writeText(ChainRuntimeCompiler.clear(raw, originalFinal))
                    Settings.chainEnabled = true
                    Settings.chainEntryTag = main.tag
                    Settings.chainLandingProfileId = landing.profileId
                    Settings.chainLandingTag = landing.tag
                    Settings.chainBoundProfileId = Settings.selectedProfile
                }
            }
            busy = false
            if (result.isSuccess) {
                chainActive = true
                savedHint = "链路：${main.tag} → ${landing.displayLine}"
                snackbar.showSnackbar("已保存链式出口。启动服务时按入口→落地串联，失败不会改走 DIRECT。")
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
                    val final = ChainRuntimeCompiler.resolveMainTag(root.optJSONArray("outbounds") ?: JSONArray(), "")
                    file.writeText(ChainRuntimeCompiler.clear(root.toString(), final))
                    Settings.chainEnabled = false
                    Settings.chainEntryTag = ""
                    Settings.chainLandingProfileId = -1L
                    Settings.chainLandingTag = ""
                    Settings.chainBoundProfileId = -1L
                }
            }
            busy = false
            if (result.isSuccess) {
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
                title = { Text(stringResource(R.string.chain_builder)) },
                navigationIcon = { IconButton(onClick = { navController.navigateUp() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = { showHelp = true }) { Icon(Icons.Default.Info, stringResource(R.string.read_more)) }
                    IconButton(onClick = { reload() }) { Icon(Icons.Default.Refresh, stringResource(R.string.action_reload)) }
                },
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
            savedHint?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Text(
                "Chain 只负责按你选的顺序串联现有 outbound：入口 → 落地 → 目标。不绑定机场或协议。链路失败不会自动改走 DIRECT。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("入口（当前配置）", fontWeight = FontWeight.Medium)
            Button(onClick = { picker = "entry"; pickerQuery = "" }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                Text(entry?.let { "${it.tag} · ${it.typeLabel}" } ?: "选择入口")
            }
            Text("落地（当前或其他配置）", fontWeight = FontWeight.Medium)
            Button(onClick = { picker = "landing"; pickerQuery = "" }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                Text(exit?.displayLine ?: "选择落地")
            }
            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth(), enabled = !busy && entry != null && exit != null) {
                Text("保存并固定为链式出口")
            }
            OutlinedButton(onClick = { clearChain() }, modifier = Modifier.fillMaxWidth(), enabled = !busy && chainActive) {
                Text("取消链式（恢复普通出口）")
            }
        }
    }

    if (picker != null) {
        val source = if (picker == "entry") {
            currentHops
        } else {
            allProfiles.flatMap { it.hops }.filter { hop ->
                !(hop.profileId == currentProfileId && hop.tag == entry?.tag)
            }
        }
        val q = pickerQuery.trim().lowercase()
        val filtered = if (q.isEmpty()) source else source.filter {
            it.tag.lowercase().contains(q) || it.profileName.lowercase().contains(q)
        }
        AlertDialog(
            onDismissRequest = { picker = null },
            title = { Text(if (picker == "entry") "选择入口" else "选择落地") },
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
                    if (filtered.isEmpty()) {
                        Text("没有可选项。请先导入含节点的配置。", style = MaterialTheme.typography.bodySmall)
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(filtered) { hop ->
                            Column(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    if (picker == "entry") entry = hop else exit = hop
                                    picker = null
                                }.padding(vertical = 10.dp),
                            ) {
                                Text(if (picker == "entry") "${hop.tag} · ${hop.typeLabel}" else hop.displayLine, fontWeight = FontWeight.Medium)
                                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picker = null }) { Text("关闭") } },
        )
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("链式代理说明") },
            text = {
                Text(
                    "1. 入口：当前配置里流量先走的分组或节点（前置机场）。不要依赖「漏网之鱼」。\n" +
                        "2. 落地：下一跳，出口 IP 应该是落地节点，不是前置机场。\n" +
                        "3. 保存后使用 sing-box 原生 Chain outbound：入口 → 落地 → 目标。\n" +
                        "4. Fail Closed：链路失败会明确报错并停止启动，不会偷偷改走 DIRECT。\n" +
                        "5. 哪些流量走 Chain 仍由路由规则决定；Chain 只提供串联能力。",
                )
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("知道了") } },
        )
    }
}

private fun parseHopsFromProfile(profile: Profile): List<HopRef> = try {
    ChainRuntimeCompiler.listSelectableHops(
        File(profile.typed.path).readText(),
        profile.id,
        profile.name,
    ).map { HopRef(it.profileId, it.profileName, it.tag, it.type) }
} catch (_: Exception) {
    emptyList()
}
