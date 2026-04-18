package cn.jarvis.hrbridge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.jarvis.hrbridge.ui.components.PermissionDeniedDialog
import cn.jarvis.hrbridge.ui.components.openAppSettings
import cn.jarvis.hrbridge.ui.components.rememberPermissionRequester

@Composable
fun ScanScreen(
    onBack: () -> Unit,
    vm: ScanViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var showPermDialog by remember { mutableStateOf(false) }

    val permRequester = rememberPermissionRequester { granted ->
        if (granted) vm.startScan() else showPermDialog = true
    }

    LaunchedEffect(Unit) { permRequester.request() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("扫描 BLE 设备") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { if (state.scanning) vm.stopScan() else permRequester.request() }
                    ) {
                        if (state.scanning) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "重新扫描")
                        }
                    }
                }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(16.dp)
        ) {
            Text(
                text = when {
                    state.scanning -> "正在扫描…已发现 ${state.devices.size} 个设备"
                    state.devices.isEmpty() -> "未发现设备"
                    else -> "发现 ${state.devices.size} 个设备 · 点击连接"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(12.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                items(state.devices, key = { it.mac }) { d ->
                    DeviceRow(
                        name = d.name ?: "(未命名)",
                        mac = d.mac,
                        rssi = d.rssi,
                        onClick = { vm.select(d, onBack) }
                    )
                }
                if (state.devices.isEmpty() && !state.scanning) {
                    item {
                        Text(
                            "未找到任何 BLE 设备。请确认：\n" +
                                "1. 手环/手表已开启心率广播\n" +
                                "2. 手机蓝牙已开启\n" +
                                "3. 已授予权限",
                            modifier = Modifier.padding(vertical = 24.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showPermDialog) {
        PermissionDeniedDialog(
            onDismiss = { showPermDialog = false },
            onGoToSettings = {
                showPermDialog = false
                openAppSettings(ctx)
            }
        )
    }
}

@Composable
private fun DeviceRow(name: String, mac: String, rssi: Int, onClick: () -> Unit) {
    val tint = rssiColor(rssi)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Bluetooth, contentDescription = null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(
                mac,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text("$rssi dBm", style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

@Composable
private fun rssiColor(rssi: Int): Color = when {
    rssi > -60 -> MaterialTheme.colorScheme.primary
    rssi > -80 -> MaterialTheme.colorScheme.tertiary
    else       -> MaterialTheme.colorScheme.error
}
