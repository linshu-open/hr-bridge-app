package cn.jarvis.hrbridge.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import cn.jarvis.hrbridge.util.Logger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 单设备 BLE GATT 连接封装。
 *
 * 使用方式：
 *   val conn = BleConnection(ctx)
 *   conn.events.collect { evt -> ... }
 *   conn.connect(mac)
 *   ...
 *   conn.disconnect()
 *
 * 事件采用共享流（hot），避免生命周期下心率数据丢失。
 */
class BleConnection(private val ctx: Context) {

    sealed interface Event {
        data object Connecting : Event
        data class Connected(val mac: String) : Event
        data object ServicesDiscovered : Event
        data class HeartRate(val hr: Int, val rrMs: List<Int>) : Event
        data class Disconnected(val reason: Int) : Event
        data class Error(val message: String) : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: Flow<Event> = _events.asSharedFlow()

    @Volatile private var gatt: BluetoothGatt? = null

    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else true

    @SuppressLint("MissingPermission")
    fun connect(mac: String) {
        if (!hasConnectPermission()) {
            _events.tryEmit(Event.Error("缺少 BLUETOOTH_CONNECT 权限"))
            return
        }
        val adapter = ctx.getSystemService<BluetoothManager>()?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _events.tryEmit(Event.Error("蓝牙未开启"))
            return
        }
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            _events.tryEmit(Event.Error("非法 MAC: $mac"))
            return
        }

        _events.tryEmit(Event.Connecting)
        Logger.i("BleConn", "connecting $mac")
        gatt?.close()
        gatt = device.connectGatt(
            ctx, /* autoConnect = */ false,
            callback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        if (!hasConnectPermission()) return
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: SecurityException) {
            Logger.w("BleConn", "disconnect 权限异常: ${e.message}")
        }
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _events.tryEmit(Event.Connected(g.device.address))
                    Logger.i("BleConn", "connected → discoverServices")
                    try { g.discoverServices() } catch (e: SecurityException) {
                        _events.tryEmit(Event.Error(e.message ?: "discoverServices 权限异常"))
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _events.tryEmit(Event.Disconnected(status))
                    Logger.i("BleConn", "disconnected status=$status")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _events.tryEmit(Event.Error("服务发现失败 status=$status"))
                return
            }
            val svc = g.getService(BleConstants.HR_SERVICE)
            if (svc == null) {
                _events.tryEmit(Event.Error("心率服务未找到（设备不支持标准 HRS）"))
                return
            }
            val chr = svc.getCharacteristic(BleConstants.HR_MEASUREMENT)
            if (chr == null) {
                _events.tryEmit(Event.Error("心率 Characteristic 未找到"))
                return
            }

            try {
                g.setCharacteristicNotification(chr, true)
                val cccd = chr.getDescriptor(BleConstants.CCCD)
                if (cccd != null) {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            } catch (e: SecurityException) {
                _events.tryEmit(Event.Error("订阅心率通知权限异常: ${e.message}"))
                return
            }
            _events.tryEmit(Event.ServicesDiscovered)
        }

        @Deprecated("pre-Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, chr: BluetoothGattCharacteristic) {
            handleHr(chr.uuid, chr.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            chr: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleHr(chr.uuid, value)
        }

        private fun handleHr(uuid: java.util.UUID, value: ByteArray?) {
            if (uuid != BleConstants.HR_MEASUREMENT) return
            val parsed = HrParser.parse(value) ?: return
            _events.tryEmit(Event.HeartRate(parsed.hr, parsed.rrIntervalsMs))
        }
    }
}
