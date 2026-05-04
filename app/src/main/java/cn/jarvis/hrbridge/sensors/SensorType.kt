package cn.jarvis.hrbridge.sensors

/**
 * 服务端 SensorRouter 约定的 sensor_type 字符串。
 * 用作 `POST /jarvis/sensor/{type}` 的路径段和 Room `sensor_records.sensorType`。
 */
object SensorType {
    const val HEART_RATE    = "heart_rate"       // 专用端点，保留旧链路
    const val STEP_COUNT    = "step_count"
    const val LOCATION      = "location"
    const val ACCELEROMETER = "accelerometer"
    const val GYROSCOPE     = "gyroscope"
    const val LIGHT         = "light"
    const val BLUETOOTH     = "bluetooth"
    const val SLEEP         = "sleep"

    /** 默认启用集合（Alpha1 先开 3 个核心传感器） */
    val DEFAULT_ENABLED = setOf(HEART_RATE, STEP_COUNT, LOCATION)

    /** 全部通用端点类型（不含 HR，因为 HR 走专用端点） */
    val GENERIC = listOf(
        STEP_COUNT, LOCATION, ACCELEROMETER, GYROSCOPE, LIGHT, BLUETOOTH, SLEEP
    )

    /** 全部类型（含 HR） */
    val ALL = listOf(HEART_RATE) + GENERIC

    /** UI 显示名 */
    val DISPLAY_NAMES = mapOf(
        HEART_RATE    to "心率",
        STEP_COUNT    to "计步",
        LOCATION      to "定位",
        ACCELEROMETER to "加速度",
        GYROSCOPE     to "陀螺仪",
        LIGHT         to "光线",
        BLUETOOTH     to "蓝牙",
        SLEEP         to "睡眠"
    )
}
