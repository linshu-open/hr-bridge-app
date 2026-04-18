package cn.jarvis.hrbridge.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jarvis.hrbridge.BuildConfig
import cn.jarvis.hrbridge.util.HrThresholds

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionHeader("服务器")

            var serverUrl by remember(s.serverUrl) { mutableStateOf(s.serverUrl) }
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("单条上传 URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(onClick = { vm.setServerUrl(serverUrl) }) { Text("保存") }
                }
            )

            var batchUrl by remember(s.batchUrl) { mutableStateOf(s.batchUrl) }
            OutlinedTextField(
                value = batchUrl,
                onValueChange = { batchUrl = it },
                label = { Text("批量上传 URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(onClick = { vm.setBatchUrl(batchUrl) }) { Text("保存") }
                }
            )

            var token by remember(s.authToken) { mutableStateOf(s.authToken) }
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Token（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(onClick = { vm.setToken(token) }) { Text("保存") }
                }
            )

            SectionHeader("心率阈值")
            ThresholdRow("紧急偏低", s.thresholds.criticalLow, 30..80) { vm.setThresholds(s.thresholds.copy(criticalLow = it)) }
            ThresholdRow("偏低",     s.thresholds.low,         40..90) { vm.setThresholds(s.thresholds.copy(low = it)) }
            ThresholdRow("正常上限", s.thresholds.normalMax,   80..120){ vm.setThresholds(s.thresholds.copy(normalMax = it)) }
            ThresholdRow("偏高",     s.thresholds.elevated,    100..140){ vm.setThresholds(s.thresholds.copy(elevated = it)) }
            ThresholdRow("紧急偏高", s.thresholds.criticalHigh,120..200){ vm.setThresholds(s.thresholds.copy(criticalHigh = it)) }
            OutlinedButton(
                onClick = { vm.setThresholds(HrThresholds.DEFAULT) },
                modifier = Modifier.align(Alignment.End)
            ) { Text("恢复默认") }

            SectionHeader("行为")
            SwitchRow("自动重连", "断线后指数退避重连", s.autoReconnect, vm::setAutoReconnect)
            SwitchRow("本地告警", "critical 心率持续 30s 触发通知", s.alertEnabled, vm::setAlertEnabled)
            Text(
                "勿扰时段: ${s.alertQuietStart}:00 - ${s.alertQuietEnd}:00（不弹告警）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SectionHeader("外观")
            SwitchRow("Material You 动态取色", "Android 12+ 跟随壁纸颜色", s.dynamicColor, vm::setDynamicColor)

            SectionHeader("关于")
            ListItem(
                headlineContent = { Text("版本") },
                supportingContent = { Text("v${BuildConfig.VERSION_NAME} · code ${BuildConfig.VERSION_CODE}") },
                leadingContent = { Icon(Icons.Default.Info, contentDescription = null) }
            )
            ListItem(
                headlineContent = { Text("检查更新") },
                supportingContent = { Text(state.updateMessage ?: "点击检查 GitHub Releases") },
                modifier = Modifier.clickable(enabled = !state.checkingUpdate) { vm.checkUpdate() },
                trailingContent = {
                    if (state.checkingUpdate) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            )
            state.updateInfo?.takeIf { it.isNewer && it.downloadUrl != null }?.let { info ->
                Button(
                    onClick = vm::downloadAndInstall,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.downloadingProgress == null
                ) {
                    Text(
                        when (val p = state.downloadingProgress) {
                            null -> "下载并安装 ${info.latestVersion}"
                            else -> "下载中 $p%"
                        }
                    )
                }
                info.body.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it.take(200),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            ListItem(
                headlineContent = { Text("GitHub") },
                supportingContent = { Text("https://github.com/linshu-open/hr-bridge-app") }
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ThresholdRow(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.width(90.dp))
        Text("$value bpm", modifier = Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.weight(1f)
        )
    }
}
