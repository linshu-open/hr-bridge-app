package cn.jarvis.hrbridge.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * 返回当前所需的运行时权限列表（依 Android 版本不同）。
 *
 * - Android 12+（SDK_INT >= 31）: BLUETOOTH_SCAN / BLUETOOTH_CONNECT
 * - 更早: ACCESS_FINE_LOCATION（历史原因：BLE 扫描需要）
 * - 33+: 加 POST_NOTIFICATIONS
 */
fun requiredBlePermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

fun hasAllPermissions(ctx: Context): Boolean =
    requiredBlePermissions().all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * 权限申请辅助 Composable。用法：
 *
 *   val requester = rememberPermissionRequester { granted -> ... }
 *   requester.request()
 */
class PermissionRequester(
    private val launch: () -> Unit,
    val granted: Boolean
) {
    fun request() = launch()
}

@Composable
fun rememberPermissionRequester(
    onResult: (allGranted: Boolean) -> Unit = {}
): PermissionRequester {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasAllPermissions(ctx)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val all = result.values.all { it }
        granted = all
        onResult(all)
    }
    return PermissionRequester(
        launch = { launcher.launch(requiredBlePermissions()) },
        granted = granted
    )
}

/** 引导用户去系统设置页（在权限被永久拒绝时使用） */
fun openAppSettings(ctx: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", ctx.packageName, null)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    ctx.startActivity(intent)
}

@Composable
fun PermissionDeniedDialog(
    onDismiss: () -> Unit,
    onGoToSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("需要权限") },
        text = {
            Text("JARVIS 心率桥接需要蓝牙与通知权限才能工作。请前往设置页手动授予。")
        },
        confirmButton = {
            Button(onClick = onGoToSettings) { Text("去设置") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
