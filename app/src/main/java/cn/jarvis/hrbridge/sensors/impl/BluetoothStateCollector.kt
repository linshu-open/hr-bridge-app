package cn.jarvis.hrbridge.sensors.impl

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import cn.jarvis.hrbridge.sensors.Emit
import cn.jarvis.hrbridge.sensors.SensorCollector
import cn.jarvis.hrbridge.sensors.SensorType
import cn.jarvis.hrbridge.sensors.UploadMode
import cn.jarvis.hrbridge.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 蓝牙连接状态采集。
 *
 * - 监听 ACL_CONNECTED / ACL_DISCONNECTED + ADAPTER_STATE_CHANGED
 * - 上报当前手环设备名 + 是否连接 + bonded 设备数
 * - 状态变化时触发 emit（非轮询）
 */
class BluetoothStateCollector(private val ctx: Context) : SensorCollector {

    override val type: String = SensorType.BLUETOOTH

    private var emitRef: Emit? = null
    private var scopeRef: CoroutineScope? = null
    private var lastDevice: String = ""
    private var lastConnected: Boolean = false

    private val adapter: BluetoothAdapter? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        else
            @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val connected = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED
                    if (device != null) {
                        lastDevice = device.name ?: device.address
                        lastConnected = connected
                        scopeRef?.launch { doEmit() }
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    // 适配器开关变化时也上报一次
                    scopeRef?.launch { doEmit() }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun isAvailable(): Boolean = adapter != null

    override fun start(scope: CoroutineScope, mode: UploadMode, emit: Emit) {
        this.emitRef = emit
        this.scopeRef = scope

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ctx.registerReceiver(receiver, filter)

        // 启动时发一次初始状态
        scope.launch { doEmit() }
        Logger.i("BtState", "started")
    }

    override fun stop() {
        runCatching { ctx.unregisterReceiver(receiver) }
        emitRef = null
        scopeRef = null
        Logger.i("BtState", "stopped")
    }

    private suspend fun doEmit() {
        val emit = emitRef ?: return
        val ts = System.currentTimeMillis() / 1000
        @Suppress("DEPRECATION")
        val adapter = this.adapter
        val bondedCount = adapter?.bondedDevices?.size ?: 0
        val enabled = adapter?.isEnabled == true

        val json = buildString {
            append("{")
            append("\"device\":\"$lastDevice\",")
            append("\"connected\":$lastConnected,")
            append("\"bonded_devices\":$bondedCount,")
            append("\"adapter_enabled\":$enabled,")
            append("\"ts\":$ts")
            append("}")
        }
        runCatching { emit(SensorType.BLUETOOTH, json) }
            .onFailure { Logger.w("BtState", "emit failed: ${it.message}") }
    }
}
