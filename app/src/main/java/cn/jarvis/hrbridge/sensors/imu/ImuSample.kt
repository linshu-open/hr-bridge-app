package cn.jarvis.hrbridge.sensors.imu

/**
 * AccelSample：加速度计单次采样。
 */
data class AccelSample(
    val tMs: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val magnitude: Float
)

/**
 * GyroSample：陀螺仪单次采样。
 */
data class GyroSample(
    val tMs: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val angularSpeed: Float
)
