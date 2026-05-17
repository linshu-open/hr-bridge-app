package cn.jarvis.hrbridge.sensors

import cn.jarvis.hrbridge.sensors.imu.ImuWindowAggregator
import cn.jarvis.hrbridge.util.Logger
import kotlinx.coroutines.CoroutineScope
import java.time.LocalTime

/**
 * 管理所有 [SensorCollector] 的总线。
 * 由 [cn.jarvis.hrbridge.service.HeartRateService] 调用。
 *
 * v2 升级：
 * - 加速度计作为"元传感器"，通过 [MotionStateDetector] 检测运动状态
 * - 状态变化时，从 [SensorFrequencyPolicy] 获取每传感器独立频率配置
 * - 通过 [SensorCollector.applyFrequency] 精确应用到每个 Collector
 *
 * 仍保留 [applyMode] 用于 HR 异常触发的全局 REALTIME 模式。
 */
class SensorHub(
    private val collectors: List<SensorCollector>,
    private val repo: SensorRepository
) : MotionStateListener {
    @Volatile private var currentMode: UploadMode = UploadMode.NORMAL
    private val running: MutableSet<String> = mutableSetOf()

    /** Shared IMU aggregator for cross-sensor windowing (M1B) */
    @Volatile
    var imuAggregator: ImuWindowAggregator = ImuWindowAggregator()

    /** Motion state detector: accelerometer feeds it, SensorHub reacts to changes */
    val motionDetector = MotionStateDetector()

    @Volatile
    var currentMotionState: MotionState = MotionState.STATIC
        private set

    /** Current per-sensor frequency config (recalculated on motion state change) */
    @Volatile
    var currentFreqConfig: SensorFreqConfig = SensorFrequencyPolicy.forMotionState(MotionState.STATIC)
        private set

    init {
        motionDetector.setListener(this)
    }

    // ── MotionStateListener ──────────────────────────────────────────

    override fun onMotionStateChanged(old: MotionState, new: MotionState) {
        currentMotionState = new
        val config = SensorFrequencyPolicy.onMotionStateChanged(old, new)
        currentFreqConfig = config
        applyFrequencyToAll(config)
        // Also apply to IMU aggregator window duration
        imuAggregator.applyMode(currentMode)
        Logger.i("SensorHub", "motion $old → $new, freq: accel=${config.accelDelayUs}us upload=${config.uploadIntervalMs}ms")
    }

    // ── Public API ───────────────────────────────────────────────────

    fun start(scope: CoroutineScope, enabled: Set<String>, mode: UploadMode) {
        currentMode = mode
        imuAggregator.applyMode(mode)
        val emit: Emit = { type, json -> repo.ingest(type, json) }

        for (c in collectors) {
            val want = c.type in enabled && c.isAvailable()
            val isRunning = c.type in running
            when {
                want && !isRunning -> {
                    runCatching { c.start(scope, mode, emit) }
                        .onSuccess { running.add(c.type); Logger.i("SensorHub", "start ${c.type}") }
                        .onFailure { Logger.w("SensorHub", "start ${c.type} failed: ${it.message}") }
                }
                !want && isRunning -> {
                    runCatching { c.stop() }
                    running.remove(c.type)
                    Logger.i("SensorHub", "stop ${c.type}")
                }
                /* want == isRunning */
            }
        }
        // Apply initial frequency config after start
        applyFrequencyToAll(currentFreqConfig)
    }

    fun applyMode(mode: UploadMode) {
        if (mode == currentMode) return
        currentMode = mode
        imuAggregator.applyMode(mode)
        // Per-sensor frequency still driven by motion state; mode is an override layer
        for (c in collectors) {
            if (c.type in running) runCatching { c.onModeChanged(mode) }
        }
        Logger.i("SensorHub", "mode → ${mode.wire}, motion=${currentMotionState}")
    }

    fun stop() {
        for (c in collectors) {
            if (c.type in running) runCatching { c.stop() }
        }
        running.clear()
    }

    fun runningTypes(): Set<String> = running.toSet()

    // ── Internal ─────────────────────────────────────────────────────

    /** Push [config] to all running collectors via their per-sensor [applyFrequency] */
    private fun applyFrequencyToAll(config: SensorFreqConfig) {
        for (c in collectors) {
            if (c.type in running) {
                runCatching { c.applyFrequency(config) }
                    .onFailure { Logger.w("SensorHub", "applyFreq ${c.type} failed: ${it.message}") }
            }
        }
    }
}
