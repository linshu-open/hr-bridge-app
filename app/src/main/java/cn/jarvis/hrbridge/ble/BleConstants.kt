package cn.jarvis.hrbridge.ble

import java.util.UUID

/** Bluetooth SIG 标准心率服务 UUID —— 保持与 v1.2.7 一致 */
object BleConstants {
    val HR_SERVICE: UUID      = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val HR_MEASUREMENT: UUID  = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID            = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** 可选：电池服务 */
    val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL: UUID   = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    const val SCAN_TIMEOUT_MS = 15_000L
}
