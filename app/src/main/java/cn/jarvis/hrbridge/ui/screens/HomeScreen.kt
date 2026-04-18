package cn.jarvis.hrbridge.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jarvis.hrbridge.BuildConfig
import cn.jarvis.hrbridge.service.HeartRateService
import cn.jarvis.hrbridge.ui.components.HrHero
import cn.jarvis.hrbridge.ui.components.PermissionDeniedDialog
import cn.jarvis.hrbridge.ui.components.StatsCards
import cn.jarvis.hrbridge.ui.components.TrendChart
import cn.jarvis.hrbridge.ui.components.openAppSettings
import cn.jarvis.hrbridge.ui.components.rememberPermissionRequester

@Composable
fun HomeScreen(
    onScanClick: () -> Unit,
    onSettingsClick: () -> Unit,
    vm: HomeViewModel = viewModel()
) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()

    var serviceRunning by remember { mutableStateOf(false) }
    var showPermDeniedDialog by remember { mutableStateOf(false) }

    val permRequester = rememberPermissionRequester { granted ->
        if (granted && state.hasDevice) {
            HeartRateService.start(ctx)
            serviceRunning = true
        } else if (!granted) {
            showPermDeniedDialog = true
        }
    }

    // 进入时提示 Battery Optimization（仅首次）
    LaunchedEffect(Unit) {
        // 空操作，交由用户主动点击；避免自动弹窗打扰
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("JARVIS 心率桥接") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
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
            HrHero(
                hr = state.latestHr,
                connected = serviceRunning && state.latestHr != null,
                deviceName = state.settings.selectedDeviceName.ifEmpty { null }
            )

            StatsCards(state.min, state.avg, state.max)

            TrendChart(data = state.hrSamples)

            // 控制按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        if (!state.hasDevice) {
                            onScanClick()
                        } else {
                            // 启动服务前先确保权限
                            permRequester.request()
                        }
                    },
                    enabled = !serviceRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.hasDevice) "开始监测" else "选择设备")
                }
                OutlinedButton(
                    onClick = {
                        HeartRateService.stop(ctx)
                        serviceRunning = false
                    },
                    enabled = serviceRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("停止监测")
                }
            }

            OutlinedButton(
                onClick = onScanClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Bluetooth, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.hasDevice) "切换设备" else "扫描 BLE 设备")
            }

            // 测试服务器
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = vm::testServer,
                    enabled = !state.testing,
                    modifier = Modifier.weight(1f)
                ) {
                    if (state.testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("测试中…")
                    } else {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("测试服务器")
                    }
                }
            }

            state.lastTestMessage?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(msg, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = vm::clearTestMessage) { Text("×") }
                    }
                }
            }

            // 未上传提示
            if (state.pendingCount > 0) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        "本地缓存 ${state.pendingCount} 条待上传",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // 电池优化引导
            BatteryOptHint()

            Spacer(Modifier.height(8.dp))
            Text(
                "v${BuildConfig.VERSION_NAME} · 服务器 ${state.settings.serverUrl.take(40)}…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showPermDeniedDialog) {
        PermissionDeniedDialog(
            onDismiss = { showPermDeniedDialog = false },
            onGoToSettings = {
                showPermDeniedDialog = false
                openAppSettings(ctx)
            }
        )
    }
}

@Composable
private fun BatteryOptHint() {
    val ctx = LocalContext.current
    val pm = ctx.getSystemService<PowerManager>() ?: return
    val ignoring = remember { pm.isIgnoringBatteryOptimizations(ctx.packageName) }
    if (ignoring) return

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("建议关闭此应用的电池优化", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Android 的 Doze 模式可能中断 BLE 通知。把本应用加入电池白名单可大幅提升后台稳定性。",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${ctx.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                runCatching { ctx.startActivity(intent) }
            }) { Text("去设置") }
        }
    }
}
