package cn.jarvis.hrbridge.sensors.imu

/**
 * AccelStats — 加速度计窗口统计。
 *
 * 公式参考 design §5 / §6：
 *   mean_magnitude = Σ|v|/n
 *   std_magnitude  = sqrt(Σ(|v|-mean)²/(n-1))
 *   jerk_mean      = Σ|Δ|v||/(n-1)/dt
 *   periodicity_score = 归一化自相关近似
 *   orientation_stability = 1 - std(gravity_ratio)   (gravity_ratio = z/mag)
 */
data class AccelStats(
    val n: Int,
    val meanMagnitude: Float,
    val stdMagnitude: Float,
    val minMagnitude: Float,
    val maxMagnitude: Float,
    val jerkMean: Float,
    val jerkMax: Float,
    val periodicityScore: Float,
    val movementBurstCount: Int,
    val orientationStability: Float
) {
    companion object {
        fun empty() = AccelStats(
            n = 0,
            meanMagnitude = 0f,
            stdMagnitude = 0f,
            minMagnitude = 0f,
            maxMagnitude = 0f,
            jerkMean = 0f,
            jerkMax = 0f,
            periodicityScore = 0f,
            movementBurstCount = 0,
            orientationStability = 0f
        )
    }
}

/**
 * GyroStats — 陀螺仪窗口统计。
 *
 *   micro_rotation_count = count of |angularSpeed| > 0.25 rad/s
 *   phone_handling_score = clamp01(stdAngularSpeed*2 + microRotationCount/n)
 *   stable_on_table_score = clamp01(1 - stdMagnitude*5 - stdAngularSpeed*2)
 */
data class GyroStats(
    val n: Int,
    val meanAngularSpeed: Float,
    val stdAngularSpeed: Float,
    val maxAngularSpeed: Float,
    val microRotationCount: Int,
    val phoneHandlingScore: Float,
    val stableOnTableScore: Float
) {
    companion object {
        fun empty() = GyroStats(
            n = 0,
            meanAngularSpeed = 0f,
            stdAngularSpeed = 0f,
            maxAngularSpeed = 0f,
            microRotationCount = 0,
            phoneHandlingScore = 0f,
            stableOnTableScore = 0f
        )
    }
}

/**
 * DerivedImuState — 组合行为推断。
 */
data class DerivedImuState(
    val phoneOnTableScore: Float,
    val phoneInHandScore: Float,
    val walkingScore: Float,
    val vehicleVibrationScore: Float,
    val lyingStillScore: Float,
    val dominantPattern: String
)

/**
 * ImuQuality — 窗口质量标记。
 */
data class ImuQuality(
    val missingAccel: Boolean,
    val missingGyro: Boolean,
    val sampleDropRatio: Float,
    val batterySaver: Boolean
)

/**
 * ImuWindowFeature — 单个聚合窗口的完整特征。
 */
data class ImuWindowFeature(
    val schemaVersion: String = "imu-window-v1",
    val windowStartMs: Long,
    val windowEndMs: Long,
    val durationSec: Int,
    val mode: String,
    val accelHz: Int,
    val gyroHz: Int,
    val accel: AccelStats,
    val gyro: GyroStats,
    val derived: DerivedImuState,
    val quality: ImuQuality
)
