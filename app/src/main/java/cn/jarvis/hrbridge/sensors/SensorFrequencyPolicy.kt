package cn.jarvis.hrbridge.sensors

import cn.jarvis.hrbridge.util.Logger
import java.time.LocalTime

/**
 * 动态传感器频率策略 —— "加速度计做元传感器，其他跟随"。
 *
 * 核心规则：
 * 1. 每种 [MotionState] 对应一套传感器频率配置
 * 2. 夜间（22:00-07:00）+ 静止 → 定位极度保守（可长时间不上传）
 * 3. STATIC → MOVING 立即启动高频传感器（陀螺仪/加速度计/光线必须实时响应）
 * 4. 回落时有迟滞，避免抖动
 *
 * @property accelDelayUs  SensorManager.registerListener 的采样周期 (μs)
 * @property accelReportLatencyUs 硬件批处理延迟 (μs)，0=不使用批处理
 * @property uploadIntervalMs  上传间隔 (ms)
 * @property imuWindowMs  IMU 聚合窗口 (ms)
 */
data class SensorFreqConfig(
    val accelDelayUs: Int,
    val accelReportLatencyUs: Int,
    val gyroDelayUs: Int,
    val gyroReportLatencyUs: Int,
    val lightEnabled: Boolean,       // 光线传感器是否保持实时监听
    val locationIntervalMs: Long,    // 定位请求间隔
    val locationMinDistance: Float,  // 定位最小位移 (m)
    val uploadIntervalMs: Long,      // 主上传间隔
    val imuWindowMs: Long            // IMU 窗口时长
)

object SensorFrequencyPolicy {

    /** 夜间起始（含） */
    private const val NIGHT_START = 22
    /** 夜间结束（不含） */
    private const val NIGHT_END = 7

    // ── 每种运动状态的频率配置 ──

    private val STATIC_CONFIG = SensorFreqConfig(
        accelDelayUs = 200_000,          // 5Hz — 足以检测静止
        accelReportLatencyUs = 0,        // 禁用硬件批处理，防止部分手机硬件 FIFO 溢出死锁
        gyroDelayUs = 200_000,           // 5Hz
        gyroReportLatencyUs = 0,
        lightEnabled = true,
        locationIntervalMs = 30 * 60_000L,  // 30min
        locationMinDistance = 50f,
        uploadIntervalMs = 5 * 60_000L,     // 5min
        imuWindowMs = 60_000L               // 60s
    )

    private val STATIC_NIGHT_CONFIG = SensorFreqConfig(
        accelDelayUs = 200_000,
        accelReportLatencyUs = 0,
        gyroDelayUs = 200_000,
        gyroReportLatencyUs = 0,
        lightEnabled = true,              // 光线仍需响应（开灯=起床信号）
        locationIntervalMs = 60 * 60_000L, // 60min — 夜间静止不需要频繁定位
        locationMinDistance = 100f,
        uploadIntervalMs = 10 * 60_000L,   // 10min
        imuWindowMs = 60_000L
    )

    private val AMBIENT_MOTION_CONFIG = SensorFreqConfig(
        accelDelayUs = 100_000,           // 10Hz
        accelReportLatencyUs = 0,
        gyroDelayUs = 100_000,            // 10Hz
        gyroReportLatencyUs = 0,
        lightEnabled = true,
        locationIntervalMs = 15 * 60_000L,
        locationMinDistance = 30f,
        uploadIntervalMs = 60_000L,        // 1min
        imuWindowMs = 30_000L
    )

    private val HAND_MOVEMENT_CONFIG = SensorFreqConfig(
        accelDelayUs = 40_000,            // 25Hz
        accelReportLatencyUs = 0,
        gyroDelayUs = 40_000,             // 25Hz
        gyroReportLatencyUs = 0,
        lightEnabled = true,
        locationIntervalMs = 10 * 60_000L,
        locationMinDistance = 20f,
        uploadIntervalMs = 30_000L,        // 30s
        imuWindowMs = 30_000L
    )

    private val WALKING_CONFIG = SensorFreqConfig(
        accelDelayUs = 20_000,            // 50Hz — 需要高采样做步态分析
        accelReportLatencyUs = 0,
        gyroDelayUs = 40_000,             // 25Hz
        gyroReportLatencyUs = 0,
        lightEnabled = true,
        locationIntervalMs = 2 * 60_000L,  // 2min — 移动中定位需要及时
        locationMinDistance = 10f,
        uploadIntervalMs = 10_000L,         // 10s
        imuWindowMs = 10_000L               // 10s
    )

    private val VEHICLE_CONFIG = SensorFreqConfig(
        accelDelayUs = 20_000,            // 50Hz
        accelReportLatencyUs = 0,
        gyroDelayUs = 40_000,             // 25Hz
        gyroReportLatencyUs = 0,
        lightEnabled = true,
        locationIntervalMs = 5 * 60_000L,  // 5min
        locationMinDistance = 30f,
        uploadIntervalMs = 10_000L,         // 10s
        imuWindowMs = 10_000L
    )

    // ── Public API ──

    fun forMotionState(state: MotionState, now: LocalTime = LocalTime.now()): SensorFreqConfig {
        val config = when (state) {
            MotionState.STATIC -> {
                if (isNight(now)) STATIC_NIGHT_CONFIG else STATIC_CONFIG
            }
            MotionState.AMBIENT_MOTION -> AMBIENT_MOTION_CONFIG
            MotionState.HAND_MOVEMENT -> HAND_MOVEMENT_CONFIG
            MotionState.WALKING -> WALKING_CONFIG
            MotionState.VEHICLE -> VEHICLE_CONFIG
        }
        Logger.d("FreqPolicy", "Config for $state (night=${isNight(now)}): accel=${config.accelDelayUs}us upload=${config.uploadIntervalMs}ms")
        return config
    }

    /**
     * 判断是否夜间（22:00-07:00）
     */
    fun isNight(now: LocalTime = LocalTime.now()): Boolean {
        val hour = now.hour
        return hour >= NIGHT_START || hour < NIGHT_END
    }

    /**
     * 当运动状态改变时，返回目标配置。
     * 调用方（SensorHub）负责应用新配置到各 Collector。
     */
    fun onMotionStateChanged(old: MotionState, new: MotionState, now: LocalTime = LocalTime.now()): SensorFreqConfig {
        Logger.i("FreqPolicy", "Motion state: $old → $new")
        return forMotionState(new, now)
    }
}
