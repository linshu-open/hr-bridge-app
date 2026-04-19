package cn.jarvis.hrbridge.sensors.impl

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import cn.jarvis.hrbridge.sensors.Emit
import cn.jarvis.hrbridge.sensors.SensorCollector
import cn.jarvis.hrbridge.sensors.SensorType
import cn.jarvis.hrbridge.sensors.UploadMode
import cn.jarvis.hrbridge.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * 加速度计采集。
 *
 * - 注册 SensorManager.TYPE_ACCELEROMETER（50Hz 实时 / 5Hz 省电）
 * - 滑动窗口聚合：magnitude / activity(still|walking|running|vigorous)
 * - 跌倒检测：magnitude 突降 + 静止 → fall_detected = true
 * - 久坐累计：still_duration_min
 * - 上传频率：POWER_SAVER 5min / NORMAL 1min / REALTIME 10s
 */
class AccelerometerCollector(private val ctx: Context) : SensorCollector {

    override val type: String = SensorType.ACCELEROMETER

    private val sm: SensorManager? = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var mode: UploadMode = UploadMode.NORMAL
    private var emitRef: Emit? = null
    private var scopeRef: CoroutineScope? = null
    private var timerJob: Job? = null

    // ---- 滑动窗口 ----
    private val windowMs: Long = 10_000L  // 10s 聚合窗口
    private val samples = ArrayDeque<Float>()  // magnitude 值
    private var windowStartMs: Long = 0L
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var lastZ: Float = 0f
    private var lastMagnitude: Float = 9.81f

    // ---- 跌倒检测 ----
    private var prevMagnitude: Float = 9.81f
    private var fallCandidateTs: Long = 0L
    private var fallDetected: Boolean = false

    // ---- 久坐 ----
    private var stillSinceMs: Long = System.currentTimeMillis()
    private var currentActivity: String = "still"

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            lastX = x; lastY = y; lastZ = z
            val mag = sqrt(x * x + y * y + z * z)
            lastMagnitude = mag

            // 跌倒检测：幅值突然大幅下降（从 >15 降到 <5），且随后静止
            if (prevMagnitude > 15f && mag < 5f) {
                fallCandidateTs = System.currentTimeMillis()
            }
            if (fallCandidateTs > 0L && System.currentTimeMillis() - fallCandidateTs in 500..3000 && mag < 3f) {
                fallDetected = true
                fallCandidateTs = 0L
            }
            prevMagnitude = mag

            // 活动分类
            currentActivity = classifyActivity(mag)

            // 久坐计时
            if (currentActivity == "still") {
                // stillSinceMs 不重置，持续累加
            } else {
                stillSinceMs = System.currentTimeMillis()
            }

            // 滑动窗口收集
            val now = System.currentTimeMillis()
            if (windowStartMs == 0L) windowStartMs = now
            samples.addLast(mag)
            // 裁剪超出窗口的旧样本
            while (samples.isNotEmpty() && now - windowStartMs > windowMs) {
                samples.removeFirst()
                windowStartMs = now - windowMs
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun isAvailable(): Boolean = sensor != null

    override fun start(scope: CoroutineScope, mode: UploadMode, emit: Emit) {
        this.mode = mode
        this.emitRef = emit
        this.scopeRef = scope
        registerListener()
        startTimer()
        Logger.i("Accel", "started, mode=$mode")
    }

    override fun onModeChanged(mode: UploadMode) {
        if (this.mode == mode) return
        this.mode = mode
        sm?.unregisterListener(listener)
        registerListener()
        restartTimer()
    }

    override fun stop() {
        timerJob?.cancel()
        timerJob = null
        sm?.unregisterListener(listener)
        emitRef = null
        scopeRef = null
        samples.clear()
        Logger.i("Accel", "stopped")
    }

    // ---- internal ----

    private fun sensorDelayUs(): Int = when (mode) {
        UploadMode.POWER_SAVER -> 200_000   // ~5Hz
        UploadMode.NORMAL      -> 100_000   // ~10Hz
        UploadMode.REALTIME    ->  20_000   // ~50Hz
    }

    private fun intervalMs(): Long = when (mode) {
        UploadMode.POWER_SAVER -> 5 * 60_000L
        UploadMode.NORMAL      ->     60_000L
        UploadMode.REALTIME    ->     10_000L
    }

    private fun registerListener() {
        sm?.registerListener(listener, sensor, sensorDelayUs())
    }

    private fun startTimer() {
        val scope = scopeRef ?: return
        timerJob = scope.launch {
            while (true) {
                delay(intervalMs())
                maybeEmit()
            }
        }
    }

    private fun restartTimer() {
        timerJob?.cancel()
        startTimer()
    }

    private suspend fun maybeEmit() {
        val emit = emitRef ?: return
        val ts = System.currentTimeMillis() / 1000
        val mag = lastMagnitude
        val avgMag = if (samples.isNotEmpty()) samples.average().toFloat() else mag
        val stillMin = ((System.currentTimeMillis() - stillSinceMs) / 60_000L).toInt()
        val fall = fallDetected
        if (fall) fallDetected = false  // consume

        val json = buildString {
            append("{")
            append("\"magnitude\":${"%.2f".format(avgMag)},")
            append("\"x\":${"%.2f".format(lastX)},")
            append("\"y\":${"%.2f".format(lastY)},")
            append("\"z\":${"%.2f".format(lastZ)},")
            append("\"activity\":\"$currentActivity\",")
            append("\"fall_detected\":$fall,")
            append("\"still_duration_min\":$stillMin,")
            append("\"ts\":$ts")
            append("}")
        }
        runCatching { emit(SensorType.ACCELEROMETER, json) }
            .onFailure { Logger.w("Accel", "emit failed: ${it.message}") }
    }

    private fun classifyActivity(mag: Float): String = when {
        mag < 2f    -> "still"
        mag < 12f   -> "walking"
        mag < 20f   -> "running"
        else        -> "vigorous"
    }
}
