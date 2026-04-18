package cn.jarvis.hrbridge.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import cn.jarvis.hrbridge.util.Logger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * BLE 扫描器（协程 Flow 化）。调用方自行处理超时与权限申请。
 *
 * 设备发现以 Flow 流出；调用方可用 `take(1)` 或超时 `withTimeout` 控制停止。
 */
class BleScanner(private val ctx: Context) {

    data class Discovery(
        val name: String?,
        val mac: String,
        val rssi: Int,
        val ts: Long = System.currentTimeMillis()
    )

    fun isReady(): Boolean {
        if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return false
        val adapter = adapter() ?: return false
        return adapter.isEnabled
    }

    private fun adapter(): BluetoothAdapter? =
        ctx.getSystemService<BluetoothManager>()?.adapter

    private fun hasScanPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun scan(): Flow<Discovery> = callbackFlow {
        val scanner = adapter()?.bluetoothLeScanner
        if (scanner == null) {
            Logger.w("BleScanner", "scanner 不可用（蓝牙关闭？）")
            close()
            return@callbackFlow
        }
        if (!hasScanPermission()) {
            Logger.w("BleScanner", "缺少 BLUETOOTH_SCAN 权限")
            close()
            return@callbackFlow
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device
                trySend(Discovery(dev.name, dev.address, result.rssi))
            }
            override fun onScanFailed(errorCode: Int) {
                Logger.w("BleScanner", "scan failed: $errorCode")
                close(RuntimeException("Scan failed: $errorCode"))
            }
        }

        try {
            scanner.startScan(null, settings, cb)
            Logger.i("BleScanner", "BLE 扫描已启动")
        } catch (e: SecurityException) {
            Logger.e("BleScanner", "startScan 权限异常", e)
            close(e)
            return@callbackFlow
        }

        awaitClose {
            try {
                if (hasScanPermission()) scanner.stopScan(cb)
            } catch (e: SecurityException) {
                Logger.w("BleScanner", "stopScan 权限异常: ${e.message}")
            }
            Logger.i("BleScanner", "BLE 扫描已停止")
        }
    }
}
