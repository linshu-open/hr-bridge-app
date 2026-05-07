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
import java.util.Calendar

/**
 * 计步器采集。
 *
 * - SensorManager.TYPE_STEP_COUNTER 返回的是自上次开机以来的累计步数
 * - 每日首次记录 todayOffset = 当前累计值，作为当日基线
 * - 上报 todaySteps = currentSteps - todayOffset（当日净步数）
 * - 跨天自动重设 offset
 * - 按 UploadMode 节流：省电 15min / 常规 5min / 实时 1min
 */
class StepCounterCollector(private val ctx: Context) : SensorCollector {

    override val type: String = SensorType.STEP_COUNT

    private val sm: SensorManager? = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    private var currentSteps: Int = 0
    private var todayOffset: Int = -1    // 当日零点的开机累计值，-1=未初始化
    private var lastOffsetDay: Int = -1  // offset 对应的日期 day-of-year
    private var lastEmitTs: Long = 0L
    private var mode: UploadMode = UploadMode.NORMAL
    private var emitRef: Emit? = null
    private var scopeRef: CoroutineScope? = null
    private var timerJob: Job? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val steps = event.values[0].toInt()
            currentSteps = steps
            // 初始化或跨天时重设 offset
            val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
            if (todayOffset < 0 || today != lastOffsetDay) {
                todayOffset = steps
                lastOffsetDay = today
                Logger.i("StepCounter", "offset reset: day=$today offset=$todayOffset")
            }
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
        todayOffset = -1
        lastOffsetDay = -1
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

    override fun syncNow(): Boolean {
        val scope = scopeRef ?: return false
        scope.launch { maybeEmit() }
        return true
    }

    private suspend fun maybeEmit() {
        if (todayOffset < 0) return  // 还没读到首值
        val emit = emitRef ?: return
        val now = System.currentTimeMillis() / 1000
        if (now - lastEmitTs < intervalMs() / 1000 - 5) return  // 防抖
        lastEmitTs = now

        // 检查是否跨天，跨天则重设 offset
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        if (today != lastOffsetDay) {
            todayOffset = currentSteps
            lastOffsetDay = today
        }

        val todaySteps = (currentSteps - todayOffset).coerceAtLeast(0)
        val json = """{"steps":$todaySteps,"raw_counter":$currentSteps,"ts":$now}"""
        runCatching { emit(SensorType.STEP_COUNT, json) }
            .onFailure { Logger.w("StepCounter", "emit failed: ${it.message}") }
    }
}
