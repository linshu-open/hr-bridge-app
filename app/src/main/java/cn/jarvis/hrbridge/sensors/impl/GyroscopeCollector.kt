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
 * 陀螺仪采集。
 *
 * - SensorManager.TYPE_GYROSCOPE 5Hz
 * - angular_speed = sqrt(x²+y²+z²)；posture 推断(upright|lying|tilted)
 * - 上传频率同加速度计梯度：POWER_SAVER 5min / NORMAL 1min / REALTIME 10s
 */
class GyroscopeCollector(private val ctx: Context) : SensorCollector {

    override val type: String = SensorType.GYROSCOPE

    private val sm: SensorManager? = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var mode: UploadMode = UploadMode.NORMAL
    private var emitRef: Emit? = null
    private var scopeRef: CoroutineScope? = null
    private var timerJob: Job? = null

    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var lastZ: Float = 0f
    private var lastAngularSpeed: Float = 0f

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            lastX = event.values[0]
            lastY = event.values[1]
            lastZ = event.values[2]
            lastAngularSpeed = sqrt(lastX * lastX + lastY * lastY + lastZ * lastZ)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun isAvailable(): Boolean = sensor != null

    override fun start(scope: CoroutineScope, mode: UploadMode, emit: Emit) {
        this.mode = mode
        this.emitRef = emit
        this.scopeRef = scope
        sm?.registerListener(listener, sensor, 200_000) // ~5Hz
        startTimer()
        Logger.i("Gyro", "started, mode=$mode")
    }

    override fun onModeChanged(mode: UploadMode) {
        if (this.mode == mode) return
        this.mode = mode
        restartTimer()
    }

    override fun stop() {
        timerJob?.cancel()
        timerJob = null
        sm?.unregisterListener(listener)
        emitRef = null
        scopeRef = null
        Logger.i("Gyro", "stopped")
    }

    // ---- internal ----

    private fun intervalMs(): Long = when (mode) {
        UploadMode.POWER_SAVER -> 5 * 60_000L
        UploadMode.NORMAL      ->     60_000L
        UploadMode.REALTIME    ->     10_000L
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
        val posture = inferPosture()

        val json = buildString {
            append("{")
            append("\"angular_speed\":${"%.2f".format(lastAngularSpeed)},")
            append("\"x\":${"%.2f".format(lastX)},")
            append("\"y\":${"%.2f".format(lastY)},")
            append("\"z\":${"%.2f".format(lastZ)},")
            append("\"posture\":\"$posture\",")
            append("\"ts\":$ts")
            append("}")
        }
        runCatching { emit(SensorType.GYROSCOPE, json) }
            .onFailure { Logger.w("Gyro", "emit failed: ${it.message}") }
    }

    /** 简化姿态推断：Y 轴角速度大 = 旋转/翻身，整体小 = 静止 */
    private fun inferPosture(): String = when {
        lastAngularSpeed < 0.1f -> "upright"
        lastY > 0.5f            -> "lying"
        else                    -> "tilted"
    }
}
