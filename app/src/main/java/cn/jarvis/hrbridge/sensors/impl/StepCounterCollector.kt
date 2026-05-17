package cn.jarvis.hrbridge.sensors.impl

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import cn.jarvis.hrbridge.sensors.Emit
import cn.jarvis.hrbridge.sensors.SensorCollector
import cn.jarvis.hrbridge.sensors.SensorFreqConfig
import cn.jarvis.hrbridge.sensors.SensorType
import cn.jarvis.hrbridge.sensors.UploadMode
import cn.jarvis.hrbridge.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Step counter collector.
 *
 * Android TYPE_STEP_COUNTER reports the boot-session accumulated counter, not
 * today's steps. We persist a daily offset and upload today's delta only.
 */
class StepCounterCollector(private val ctx: Context) : SensorCollector {

    override val type: String = SensorType.STEP_COUNT

    private val sm: SensorManager? = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val prefs by lazy { ctx.getSharedPreferences("step_counter_daily", Context.MODE_PRIVATE) }

    private var currentSteps: Int = 0
    private var todayOffset: Int = -1
    private var offsetDay: String = ""
    private var lastEmitTs: Long = 0L
    private var mode: UploadMode = UploadMode.NORMAL
    private var freqConfig: SensorFreqConfig? = null
    private var emitRef: Emit? = null
    private var scopeRef: CoroutineScope? = null
    private var timerJob: Job? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val steps = event.values[0].toInt()
            currentSteps = steps
            ensureDailyOffset(steps)
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

    override fun applyFrequency(config: SensorFreqConfig) {
        if (config == freqConfig) return
        freqConfig = config
        restartTimer()
        Logger.d("StepCounter", "freq applied: upload=${config.uploadIntervalMs}ms")
    }

    override fun stop() {
        timerJob?.cancel()
        timerJob = null
        sm?.unregisterListener(listener)
        emitRef = null
        scopeRef = null
        todayOffset = -1
        offsetDay = ""
        currentSteps = 0
        Logger.i("StepCounter", "stopped")
    }

    private fun intervalMs(): Long = freqConfig?.uploadIntervalMs ?: when (mode) {
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
        if (todayOffset < 0) return
        val emit = emitRef ?: return
        val now = System.currentTimeMillis() / 1000
        if (now - lastEmitTs < intervalMs() / 1000 - 5) return
        lastEmitTs = now
        val todaySteps = (currentSteps - todayOffset).coerceAtLeast(0)
        val json = """{"steps":$todaySteps,"raw_counter":$currentSteps,"offset":$todayOffset,"day":"$offsetDay","ts":$now}"""
        runCatching { emit(SensorType.STEP_COUNT, json) }
            .onFailure { Logger.w("StepCounter", "emit failed: ${it.message}") }
    }

    private fun ensureDailyOffset(rawSteps: Int) {
        val today = LocalDate.now().toString()
        if (todayOffset < 0) {
            offsetDay = prefs.getString("day", "") ?: ""
            todayOffset = prefs.getInt("offset", -1)
        }
        if (offsetDay != today || todayOffset < 0 || rawSteps < todayOffset) {
            offsetDay = today
            todayOffset = rawSteps
            prefs.edit().putString("day", today).putInt("offset", rawSteps).apply()
            Logger.i("StepCounter", "daily offset reset day=$today offset=$rawSteps")
        }
    }
}
