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

/**
 * 计步器采集。
 *
 * - SensorManager.TYPE_STEP_COUNTER 返回的是自上次开机以来的累计步数
 * - 首次读到的值作为基准（baseSteps），后续上报 delta 或绝对值均可；
 *   服务端期望的是当日累计，这里上报绝对值，服务端自行计算增量
 * - 按 UploadMode 节流：省电 15min / 常规 5min / 实时 1min
 */
class StepCounterCollector(private val ctx: Context) : SensorCollector {

    override val type: String = SensorType.STEP_COUNT

    private val sm: SensorManager? = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    private var currentSteps: Int = 0
    private var baseSteps: Int = -1   // 首次读到的值，用于判断是否已初始化
    private var lastEmitTs: Long = 0L
    private var mode: UploadMode = UploadMode.NORMAL
    private var emitRef: Emit? = null
    private var scopeRef: CoroutineScope? = null
    private var timerJob: Job? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val steps = event.values[0].toInt()
            if (baseSteps < 0) baseSteps = steps
            currentSteps = steps
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun isAvailable(): Boolean = sensor != null

    override fun start(scope: CoroutineScope, mode: UploadMode, emit: Emit) {
        this.mode = mode
        this.emitRef = emit
        this.scopeRef = scope
        sm?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        startTimer()
        Logger.i("StepCounter", "started, mode=$mode")
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
        baseSteps = -1
        currentSteps = 0
        Logger.i("StepCounter", "stopped")
    }

    // ---- internal ----

    private fun intervalMs(): Long = when (mode) {
        UploadMode.POWER_SAVER -> 15 * 60_000L
        UploadMode.NORMAL      ->  5 * 60_000L
        UploadMode.REALTIME    ->      60_000L
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
        if (baseSteps < 0) return  // 还没读到首值
        val emit = emitRef ?: return
        val now = System.currentTimeMillis() / 1000
        if (now - lastEmitTs < intervalMs() / 1000 - 5) return  // 防抖
        lastEmitTs = now
        val json = """{"steps":$currentSteps,"ts":$now}"""
        runCatching { emit(SensorType.STEP_COUNT, json) }
            .onFailure { Logger.w("StepCounter", "emit failed: ${it.message}") }
    }
}
