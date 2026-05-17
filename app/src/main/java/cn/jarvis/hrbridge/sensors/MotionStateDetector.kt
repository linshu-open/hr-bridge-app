package cn.jarvis.hrbridge.sensors

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 实时运动状态检测器 —— 加速度计作为"元传感器"。
 *
 * 核心职责：
 * - 短窗口（2s）检测 STATIC → MOVING 瞬时转换，<500ms 反应
 * - 长窗口（10s）稳定分类（WALKING/VEHICLE/HAND_MOVEMENT）
 * - 回调 [onMotionStateChanged] 通知 SensorHub 调整全传感器频率
 *
 * Thread-safe: 所有 public 方法在 [lock] 下执行。
 */
enum class MotionState {
    /** 完全静止（手机放桌上/床上） */
    STATIC,
    /** 环境微动（口袋/包里轻微晃动） */
    AMBIENT_MOTION,
    /** 手持操作（滑动/打字/拿起放下） */
    HAND_MOVEMENT,
    /** 步行（周期性加速度模式） */
    WALKING,
    /** 车辆振动（高频低幅 + 无步行周期） */
    VEHICLE
}

interface MotionStateListener {
    fun onMotionStateChanged(old: MotionState, new: MotionState)
}

class MotionStateDetector {

    private val lock = Any()

    // 短窗 ~2s：用于 STATIC→MOVING 快速响应
    private val shortWindow = ArrayDeque<Float>(100)
    // 长窗 ~10s：用于稳定分类
    private val longWindow = ArrayDeque<Float>(500)

    @Volatile
    var currentState: MotionState = MotionState.STATIC
        private set

    private var lastStaticMs: Long = System.currentTimeMillis()
    private var lastMovingMs: Long = 0L
    private var listener: MotionStateListener? = null

    // 防抖：连续 N 次采样在新状态才切换
    private var stateVoteCount = 0
    private var pendingState: MotionState = MotionState.STATIC
    private val voteThreshold = 6 // ~300ms at 20Hz, fast enough for starter

    // 回落迟滞：MOVING→STATIC 需要 30s 持续静止
    private val staticHoldMs: Long = 30_000L

    // 采样计数（用于 Hz 估算）
    private var sampleCount = 0
    private var countStartMs: Long = System.currentTimeMillis()

    fun setListener(listener: MotionStateListener?) {
        synchronized(lock) { this.listener = listener }
    }

    /**
     * 喂入加速度 magnitude，返回当前状态（可能已更新）。
     * 由 AccelerometerCollector 的 SensorEventListener 高频调用。
     */
    fun feedMagnitude(mag: Float): MotionState {
        synchronized(lock) {
            val now = System.currentTimeMillis()

            shortWindow.addLast(mag)
            while (shortWindow.size > 40) shortWindow.removeFirst() // ~2s at 20Hz

            longWindow.addLast(mag)
            while (longWindow.size > 200) longWindow.removeFirst() // ~10s at 20Hz

            // 至少需要 1s 数据
            if (shortWindow.size < 10) return currentState

            val shortStd = computeStd(shortWindow)
            val longStd = computeStd(longWindow)
            val shortMean = shortWindow.average().toFloat()
            val longMean = longWindow.average().toFloat()

            // 重力附近（9.8±0.3）且方差 < 0.02 → STATIC
            val nearGravity = abs(shortMean - 9.81f) < 0.5f
            val veryStill = shortStd < 0.03f
            val still = shortStd < 0.08f

            // step 1: 快速分类
            val classified = when {
                veryStill && nearGravity -> MotionState.STATIC
                shortStd < 0.04f && nearGravity -> MotionState.STATIC
                shortStd >= 1.2f && hasPeriodicity() -> MotionState.WALKING
                shortStd >= 0.6f && shortStd < 1.5f && !nearGravity -> MotionState.HAND_MOVEMENT
                shortStd >= 0.15f && shortStd < 0.6f && hasVibrationPattern() -> MotionState.VEHICLE
                still && nearGravity -> {
                    // 边缘情况：静止但有微小振动
                    if (longStd < 0.06f) MotionState.STATIC
                    else MotionState.AMBIENT_MOTION
                }
                shortStd >= 0.15f -> MotionState.AMBIENT_MOTION
                else -> MotionState.STATIC
            }

            // step 2: 带迟滞的状态机过渡
            val newState = applyHysteresis(classified, now)

            // step 3: 回调
            if (newState != currentState) {
                val old = currentState
                currentState = newState
                listener?.onMotionStateChanged(old, newState)
            }

            return currentState
        }
    }

    private fun applyHysteresis(classified: MotionState, now: Long): MotionState {
        val current = currentState

        // STATIC → anything else: 立即响应（start 逻辑，<500ms）
        if (current == MotionState.STATIC && classified != MotionState.STATIC) {
            lastMovingMs = now
            return classifyWithVote(classified)
        }

        // MOVING → STATIC: 需要持续静止 30s（防抖）
        if (classified == MotionState.STATIC && current != MotionState.STATIC) {
            val elapsed = now - lastMovingMs
            if (elapsed < staticHoldMs) {
                return current // 保持旧状态
            }
            stateVoteCount = 0
            pendingState = MotionState.STATIC
            return MotionState.STATIC
        }

        // Moving state changes: use voting for stability
        if (classified != current) {
            lastMovingMs = now
            return classifyWithVote(classified)
        }

        // Same state: update lastMovingMs for transitions
        if (classified != MotionState.STATIC) {
            lastMovingMs = now
        }

        return current
    }

    private fun classifyWithVote(classified: MotionState): MotionState {
        if (classified == pendingState) {
            stateVoteCount++
            if (stateVoteCount >= voteThreshold) {
                return classified
            }
            return currentState
        }
        pendingState = classified
        stateVoteCount = 1
        return currentState
    }

    private fun computeStd(window: ArrayDeque<Float>): Float {
        if (window.size < 2) return 0f
        val mean = window.average()
        val variance = window.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance).toFloat()
    }

    /**
     * 简易周期性检测：自相关在 ~0.5-0.8s lag 处有峰值 → 可能是步行
     */
    private fun hasPeriodicity(): Boolean {
        val w = longWindow
        if (w.size < 40) return false
        val mean = w.average()
        val values = w.toList()
        // lag ~0.6s (approximately 12 samples at 20Hz)
        val lag = (w.size * 0.15).toInt().coerceIn(8, w.size / 3)
        var corr = 0.0
        var denom = 0.0
        for (i in 0 until w.size - lag) {
            val a = values[i] - mean
            val b = values[i + lag] - mean
            corr += a * b
            denom += a * a
        }
        if (denom < 0.001) return false
        return (corr / denom) > 0.35
    }

    /**
     * 车辆振动模式：高频低幅，无步态周期
     */
    private fun hasVibrationPattern(): Boolean {
        val w = shortWindow
        if (w.size < 20) return false
        // 计数加速度 jerk（突变次数）
        var jerkCount = 0
        val values = w.toList()
        for (i in 1 until values.size) {
            if (abs(values[i] - values[i - 1]) > 0.4f) jerkCount++
        }
        val jerkRatio = jerkCount.toFloat() / values.size
        // 车辆：频繁小 jerk，但无走路的大幅周期
        return jerkRatio > 0.2f && jerkRatio < 0.6f && !hasPeriodicity()
    }

    fun isStatic(): Boolean = currentState == MotionState.STATIC
    fun isMoving(): Boolean = currentState != MotionState.STATIC
}
