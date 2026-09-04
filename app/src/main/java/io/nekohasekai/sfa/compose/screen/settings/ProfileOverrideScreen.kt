package io.nekohasekai.sfa.compose.screen.settings

// TEMP minimal stub so project compiles; full UI restore next
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.constant.Status

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileOverrideScreen(
    navController: NavController,
    serviceStatus: Status = Status.Stopped,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("配置覆盖") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("此页正在修复中。请暂时用手改配置实现禁用 QUIC（route 规则 UDP 443 -> block）。")
        }
    }
}
