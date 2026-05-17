package cn.jarvis.hrbridge.sensors.imu

import cn.jarvis.hrbridge.sensors.UploadMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 本地 IMU 窗口聚合器。
 *
 * 接收两个 Collector 喂入的原始样本，维护滚动窗口（30s/60s/10s），
 * 窗口满之后计算统计量并返回 [ImuWindowFeature]。
 *
 * Thread-safe: all public methods synchronized on [lock].
 */
class ImuWindowAggregator {

    /** 窗口时长 (ms) */
    @Volatile
    var windowDurationMs: Long = 30_000L

    // 内部缓冲区
    private val accelSamples = ArrayList<AccelSample>(600)
    private val gyroSamples = ArrayList<GyroSample>(300)
    private var windowStartMs: Long = 0L
    private var guardedEmitted: Boolean = false
    private var mode: UploadMode = UploadMode.NORMAL
    private val lock = Any()

    // ── Public feed methods ──

    fun addAccel(sample: AccelSample) = synchronized(lock) {
        accelSamples.add(sample)
        // cap to prevent memory leak from aggressive sampling
        val maxCap = (windowDurationMs / 1000) * 35 // ~35 samples/sec headroom
        if (accelSamples.size > maxCap) {
            val remove = accelSamples.size - maxCap
            for (i in 0 until remove) accelSamples.removeAt(0)
            recalcWindowStart()
        }
    }

    fun addGyro(sample: GyroSample) = synchronized(lock) {
        gyroSamples.add(sample)
        val maxCap = (windowDurationMs / 1000) * 20
        if (gyroSamples.size > maxCap) {
            val remove = gyroSamples.size - maxCap
            for (i in 0 until remove) gyroSamples.removeAt(0)
        }
    }

    /**
     * 轮询是否窗口已完成。
     *
     * 窗口匹配策略：
     *   - 有 accel 样本且 accel 覆盖 ≥ windowDurationMs → poll 成功
     *   - 只有 gyro（极罕见）→ 也允许完成
     */
    fun applyMode(mode: UploadMode) = synchronized(lock) {
        this.mode = mode
        this.windowDurationMs = when (mode) {
            UploadMode.POWER_SAVER -> 60_000L
            UploadMode.NORMAL      -> 30_000L
            UploadMode.REALTIME    -> 10_000L
        }
        // reset window on mode change to avoid mixing old samples
        clearLocked()
    }

    fun applyWindowDuration(durationMs: Long) = synchronized(lock) {
        if (this.windowDurationMs == durationMs) return
        this.windowDurationMs = durationMs
    }

    // ── Poll completed ──

    fun pollCompleted(nowMs: Long): ImuWindowFeature? = synchronized(lock) {
        if (accelSamples.isEmpty() && gyroSamples.isEmpty()) return null

        val start = if (windowStartMs > 0L) windowStartMs else accelSamples.firstOrNull()?.tMs ?: return null
        val durationMs = nowMs - start

        if (durationMs < windowDurationMs) return null

        // Guard against double-emit within same window
        if (guardedEmitted) return null

        val windowEnd = nowMs
        val durationSec = ((windowEnd - start) / 1000L).toInt()

        // Build stats
        val accel = computeAccelStats(start, windowEnd)
        val gyro = computeGyroStats(start, windowEnd)
        val accelHz = accelHzFor(mode)
        val gyroHz = gyroHzFor(mode)

        // Derived scores
        val derived = computeDerived(accel, gyro)

        // Quality
        val quality = ImuQuality(
            missingAccel = accel.n == 0,
            missingGyro = gyro.n == 0,
            sampleDropRatio = computeDropRatio(accelHz, accel.n, durationSec),
            batterySaver = (mode == UploadMode.POWER_SAVER)
        )

        guardedEmitted = true

        return ImuWindowFeature(
            windowStartMs = start,
            windowEndMs = windowEnd,
            durationSec = durationSec,
            mode = mode.wire,
            accelHz = accelHz,
            gyroHz = gyroHz,
            accel = accel,
            gyro = gyro,
            derived = derived,
            quality = quality
        )
    }

    /** 窗口 clean + 开始新窗口 */
    fun clearLocked() = synchronized(lock) {
        accelSamples.clear()
        gyroSamples.clear()
        windowStartMs = 0L
        guardedEmitted = false
    }

    fun clear(): ImuWindowFeature? = synchronized(lock) {
        val feature = pollCompleted(System.currentTimeMillis())
        clearLocked()
        return feature
    }

    // ── Sampling rates ──

    private fun accelHzFor(mode: UploadMode): Int = when (mode) {
        UploadMode.POWER_SAVER -> 5
        UploadMode.NORMAL      -> 10
        UploadMode.REALTIME    -> 50
    }

    private fun gyroHzFor(mode: UploadMode): Int = when (mode) {
        UploadMode.POWER_SAVER -> 5
        UploadMode.NORMAL      -> 5
        UploadMode.REALTIME    -> 25
    }

    // ── Stats computation ──

    private fun computeAccelStats(startMs: Long, endMs: Long): AccelStats {
        val inWindow = accelSamples
            .map { it.magnitude }
            .toList()

        if (inWindow.isEmpty()) return AccelStats.empty()

        val n = inWindow.size
        val mean = inWindow.sum().toDouble() / n
        val variance = if (n > 1) inWindow.map { (it.toDouble() - mean) * (it.toDouble() - mean) }.sum() / (n - 1) else 0.0
        val std = sqrt(variance).toFloat()
        val minVal = inWindow.minOrNull()!!
        val maxVal = inWindow.maxOrNull()!!

        // jerk = Δ|mag| per second
        var jerkSum = 0.0
        var jerkMax = 0f
        var jerkCount = 0
        var burstCount = 0
        if (n >= 2) {
            for (i in 1 until n) {
                val dtSec = max(0.001, (accelSamples[i].tMs - accelSamples[i - 1].tMs) / 1000.0)
                val deltaMag = abs(inWindow[i] - inWindow[i - 1])
                val jerk = (deltaMag / dtSec).toFloat()
                jerkSum += jerk
                if (jerk > jerkMax) jerkMax = jerk
                jerkCount++
                if (jerk > 2.5f) burstCount++
            }
        }
        val jerkMean = if (jerkCount > 0) (jerkSum / jerkCount).toFloat() else 0f

        // periodicity: simple autocorrelation at ~0.6-0.7s lag (walking cadence)
        var periodicity = 0f
        if (n >= 30) {
            val lag = (n * 0.15).toInt().coerceIn(5, n / 3)
            var corr = 0.0
            for (i in 0 until n - lag) {
                corr += (inWindow[i].toDouble() - mean) * (inWindow[i + lag].toDouble() - mean)
            }
            val denominator = maxOf(1.0, (n - lag).toDouble() * variance)
            corr /= denominator
            periodicity = corr.toFloat().coerceIn(0f, 1f)
        }

        // orientation stability = 1 - std(z_i/mag_i)
        var orientStab = 1f
        if (n > 0) {
            val ratios = accelSamples
                .map { if (it.magnitude > 0.5f) it.z / it.magnitude else 0f }
                .filter { it != 0f }
            if (ratios.size >= 2) {
                val rMean = ratios.average().toFloat()
                val rVar = ratios.map { (it - rMean) * (it - rMean) }.average().toFloat()
                val rStd = sqrt(rVar.toDouble()).toFloat()
                orientStab = (1f - rStd * 3f).coerceIn(0f, 1f)
            }
        }

        return AccelStats(
            n = n,
            meanMagnitude = mean.toFloat(),
            stdMagnitude = std,
            minMagnitude = minVal,
            maxMagnitude = maxVal,
            jerkMean = jerkMean,
            jerkMax = jerkMax,
            periodicityScore = periodicity,
            movementBurstCount = burstCount,
            orientationStability = orientStab
        )
    }

    private fun computeGyroStats(startMs: Long, endMs: Long): GyroStats {
        if (gyroSamples.isEmpty()) return GyroStats.empty()

        val speeds = gyroSamples.map { it.angularSpeed }
        val n = speeds.size
        val mean = speeds.average().toFloat()
        val variance = if (n > 1) speeds.map { (it - mean) * (it - mean) }.average().toFloat() else 0f
        val std = sqrt(variance.toDouble()).toFloat()
        val max = speeds.maxOrNull()!!

        val microRotationCount = speeds.count { it > 0.25f }

        // phone_handling_score = clamp01(stdAngularSpeed * 2 + microRotationCount / n)
        val handling = if (n > 0) {
            (std * 2f + microRotationCount.toFloat() / n).coerceIn(0f, 1f)
        } else 0f

        // stable_on_table_score = clamp01(1 - stdMagnitude*5 - stdAngularSpeed*2)
        // Use global accel std if available; if not, go purely by gyro
        val accelStd = if (accelSamples.isNotEmpty()) {
            val mags = accelSamples.map { it.magnitude }
            val m = mags.average()
            if (mags.size > 1) sqrt(mags.map { (it - m) * (it - m) }.average()).toFloat() else 0f
        } else 0f

        val stableScore = (1f - accelStd * 5f - std * 2f).coerceIn(0f, 1f)

        return GyroStats(
            n = n,
            meanAngularSpeed = mean,
            stdAngularSpeed = std,
            maxAngularSpeed = max,
            microRotationCount = microRotationCount,
            phoneHandlingScore = handling,
            stableOnTableScore = stableScore
        )
    }

    // ── Derived state scoring ──

    private fun computeDerived(accel: AccelStats, gyro: GyroStats): DerivedImuState {
        val phoneOnTable = if (accel.n > 0) {
            // Higher score when: low accel std, low gyro, high stability, low bursts
            var score = (gyro.stableOnTableScore * 0.5f
                + (1f - (accel.stdMagnitude * 2f).coerceIn(0f, 1f)) * 0.3f
                + accel.orientationStability * 0.2f
            )
            if (accel.movementBurstCount > 1) score *= 0.6f
            score.coerceIn(0f, 1f)
        } else {
            gyro.stableOnTableScore
        }

        val phoneInHand = gyro.phoneHandlingScore

        // walking_score = periodicity*0.7 + (stdMagnitude above gravity)*0.2 + jerk bonus
        val walking = if (accel.n > 0) {
            val periodicityPart = accel.periodicityScore * 0.7f
            val motionPart = ((accel.stdMagnitude - 0.3f) * 0.2f).coerceIn(0f, 0.25f)
            val jerkPart = if (accel.jerkMax > 1.5f) 0.15f else 0f
            (periodicityPart + motionPart + jerkPart).coerceIn(0f, 1f)
        } else 0f

        // vehicle_vibration = high-frequency low-amplitude + drift (simplified)
        // M1B Fix: Must strictly penalize if phone is being handled (gyro is active).
        // Holding the phone in hand while sitting still can produce small jerks.
        val vehicleVib = if (accel.n > 0) {
            val hasVibration = (accel.jerkMean > 0.6f && accel.stdMagnitude < 1.5f)
            val hasNoSteps = (accel.periodicityScore < 0.2f)
            val noHandling = (gyro.phoneHandlingScore < 0.3f)
            if (hasVibration && hasNoSteps && noHandling) {
                // Confidence scales with how little it is being handled
                0.72f * (1f - gyro.phoneHandlingScore)
            } else 0.05f
        } else 0f

        // lying_still = phone_on_table + posture hint (simplified: horizontal orientation)
        val lyingStill = if (accel.n > 0) {
            val ratioCount = accelSamples.takeLast(20).count { abs(it.z / max(it.magnitude, 0.1f)) < 0.4f }
            val lyingRatio = ratioCount.toFloat() / max(1, min(20, accelSamples.size))
            ((phoneOnTable * 0.5f + lyingRatio * 0.5f)).coerceIn(0f, 1f)
        } else 0f

        // Dominant pattern order:
        // phone_in_hand must beat vehicle_vibration — manual phone use also
        // produces small jerks that the vehicle detector falsely matches.
        // walking is unambiguous and beats everything else.
        val dominant = when {
            walking >= 0.65f -> "walking_with_phone"
            phoneInHand >= 0.55f -> "phone_in_hand_scrolling"
            phoneOnTable >= 0.65f -> "phone_on_table"
            vehicleVib >= 0.65f -> "vehicle_vibration"
            lyingStill >= 0.65f && phoneOnTable >= 0.55f -> "lying_still"
            else -> "unknown"
        }

        return DerivedImuState(
            phoneOnTableScore = phoneOnTable,
            phoneInHandScore = phoneInHand,
            walkingScore = walking,
            vehicleVibrationScore = vehicleVib,
            lyingStillScore = lyingStill,
            dominantPattern = dominant
        )
    }

    private fun computeDropRatio(expectedHz: Int, actualN: Int, durationSec: Int): Float {
        if (durationSec <= 0 || expectedHz <= 0) return 0f
        val expected = expectedHz * durationSec
        return ((1f - actualN.toFloat() / expected)).coerceIn(0f, 1f)
    }

    private fun recalcWindowStart() {
        windowStartMs = accelSamples.firstOrNull()?.tMs ?: windowStartMs
    }

    fun accelSampleCount(): Int = synchronized(lock) { accelSamples.size }
    fun gyroSampleCount(): Int = synchronized(lock) { gyroSamples.size }
}
