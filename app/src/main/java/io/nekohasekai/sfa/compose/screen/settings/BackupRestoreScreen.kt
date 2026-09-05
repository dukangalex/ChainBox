package io.nekohasekai.sfa.compose.screen.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.BackupManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var webdavUrl by remember { mutableStateOf(Settings.webdavUrl) }
    var webdavUser by remember { mutableStateOf(Settings.webdavUser) }
    var webdavPass by remember { mutableStateOf(Settings.webdavPassword) }
    var remoteFile by remember { mutableStateOf(Settings.webdavRemoteFile.ifEmpty { "backup.zip" }) }
    var connectivity by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showWebdavEditor by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    val createDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(context.cacheDir, "chainbox-backup.zip")
                    BackupManager.createBackupFile(context, tmp).getOrThrow()
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(tmp).use { it.copyTo(out) }
                    } ?: error("无法写入文件")
                }
            }
            busy = false
            snackbar.showSnackbar(if (result.isSuccess) "本地备份完成" else "备份失败: ${result.exceptionOrNull()?.message}")
        }
    }

    val openDoc = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(context.cacheDir, "chainbox-restore.zip")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tmp).use { output -> input.copyTo(output) }
                    } ?: error("无法读取文件")
                    BackupManager.restoreBackupFile(context, tmp).getOrThrow()
                }
            }
            busy = false
            snackbar.showSnackbar(
                if (result.isSuccess) "恢复完成，请强行停止并重新打开应用"
                else "恢复失败: ${result.exceptionOrNull()?.message}",
            )
        }
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("备份与恢复") },
            text = {
                Text(
                    "备份内容包括：设置、配置列表、各订阅/配置 JSON。\n\n" +
                        "恢复策略为覆盖：会写回数据库与 configs 目录。\n\n" +
                        "恢复后请完全退出应用再打开，使数据库重新加载。\n\n" +
                        "WebDAV 需填写可访问的目录 URL，以及账号密码（若需要）。",
                )
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("知道了") } },
        )
    }

    if (showWebdavEditor) {
        AlertDialog(
            onDismissRequest = { showWebdavEditor = false },
            title = { Text("WebDAV 服务器") },
            text = {
                Column {
                    OutlinedTextField(
                        value = webdavUrl,
                        onValueChange = { webdavUrl = it },
                        label = { Text("URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                    OutlinedTextField(
                        value = webdavUser,
                        onValueChange = { webdavUser = it },
                        label = { Text("用户名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                    OutlinedTextField(
                        value = webdavPass,
                        onValueChange = { webdavPass = it },
                        label = { Text("密码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                    OutlinedTextField(
                        value = remoteFile,
                        onValueChange = { remoteFile = it },
                        label = { Text("远程文件名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        Settings.webdavUrl = webdavUrl
                        Settings.webdavUser = webdavUser
                        Settings.webdavPassword = webdavPass
                        Settings.webdavRemoteFile = remoteFile
                    }
                    showWebdavEditor = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showWebdavEditor = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("备份与恢复") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.Outlined.Info, contentDescription = "说明")
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
        ) {
            Text("远程", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                ListItem(
                    headlineContent = {
                        Text(if (webdavUrl.isBlank()) "未配置 WebDAV" else webdavUrl)
                    },
                    supportingContent = {
                        val status = when (connectivity) {
                            true -> "连通性：正常"
                            false -> "连通性：失败"
                            null -> "连通性：未测试"
                        }
                        Text(status)
                    },
                    trailingContent = {
                        TextButton(onClick = { showWebdavEditor = true }) { Text("编辑") }
                    },
                )
                ListItem(
                    headlineContent = { Text("文件") },
                    supportingContent = { Text(remoteFile) },
                )
                ListItem(
                    headlineContent = { Text("备份") },
                    supportingContent = { Text("备份数据到 WebDAV") },
                    modifier = Modifier.clickable(enabled = !busy) {
                        scope.launch {
                            if (webdavUrl.isBlank()) {
                                snackbar.showSnackbar("请先配置 WebDAV")
                                return@launch
                            }
                            busy = true
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val tmp = File(context.cacheDir, "chainbox-backup.zip")
                                    BackupManager.createBackupFile(context, tmp).getOrThrow()
                                    BackupManager.webdavUpload(
                                        webdavUrl, webdavUser, webdavPass, remoteFile, tmp,
                                    ).getOrThrow()
                                }
                            }
                            busy = false
                            snackbar.showSnackbar(
                                if (result.isSuccess) "WebDAV 备份完成"
                                else "WebDAV 备份失败: ${result.exceptionOrNull()?.message}",
                            )
                        }
                    },
                )
                ListItem(
                    headlineContent = { Text("恢复") },
                    supportingContent = { Text("通过 WebDAV 恢复数据") },
                    modifier = Modifier.clickable(enabled = !busy) {
                        scope.launch {
                            if (webdavUrl.isBlank()) {
                                snackbar.showSnackbar("请先配置 WebDAV")
                                return@launch
                            }
                            busy = true
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val tmp = File(context.cacheDir, "chainbox-restore.zip")
                                    BackupManager.webdavDownload(
                                        webdavUrl, webdavUser, webdavPass, remoteFile, tmp,
                                    ).getOrThrow()
                                    BackupManager.restoreBackupFile(context, tmp).getOrThrow()
                                }
                            }
                            busy = false
                            snackbar.showSnackbar(
                                if (result.isSuccess) "恢复完成，请强行停止并重新打开应用"
                                else "WebDAV 恢复失败: ${result.exceptionOrNull()?.message}",
                            )
                        }
                    },
                )
                ListItem(
                    headlineContent = { Text("测试连通性") },
                    modifier = Modifier.clickable(enabled = !busy) {
                        scope.launch {
                            busy = true
                            val ok = withContext(Dispatchers.IO) {
                                BackupManager.webdavProbe(webdavUrl, webdavUser, webdavPass).getOrDefault(false)
                            }
                            connectivity = ok
                            busy = false
                            snackbar.showSnackbar(if (ok) "WebDAV 可访问" else "WebDAV 不可用")
                        }
                    },
                )
            }

            Text(
                "本地",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp),
            )
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                ListItem(
                    headlineContent = { Text("备份") },
                    supportingContent = { Text("备份数据到本地") },
                    modifier = Modifier.clickable(enabled = !busy) {
                        createDoc.launch("ChainBox-backup.zip")
                    },
                )
                ListItem(
                    headlineContent = { Text("恢复") },
                    supportingContent = { Text("通过文件恢复数据") },
                    modifier = Modifier.clickable(enabled = !busy) {
                        openDoc.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
                )
            }

            Text(
                "选项",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp),
            )
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                ListItem(
                    headlineContent = { Text("恢复策略") },
                    trailingContent = { Text("覆盖", color = MaterialTheme.colorScheme.primary) },
                    supportingContent = { Text("当前仅支持覆盖写入") },
                )
            }
        }
    }
}
