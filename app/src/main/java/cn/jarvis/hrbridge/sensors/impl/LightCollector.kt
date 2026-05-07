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
import kotlinx.coroutines.launch

/**
 * 光线传感器采集。
 *
 * - SensorManager.TYPE_LIGHT，onChanged 只在 lux 变化 ≥ 阈值时 emit（避免刷屏）
 * - environment: <50=dark, 50-300=indoor, >300=outdoor
 * - emit(SensorType.LIGHT, json) 参见 §F2.5
 */
class LightCollector(private val ctx: Context) : SensorCollector {

    override val type: String = SensorType.LIGHT

    private val sm: SensorManager? = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_LIGHT)

    private var emitRef: Emit? = null
    private var scopeRef: CoroutineScope? = null
    private var lastEmittedLux: Float = -1f
    private val changeThreshold = 30f  // lux 变化超过此值才上报

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val lux = event.values[0]
            if (lastEmittedLux < 0f || Math.abs(lux - lastEmittedLux) > changeThreshold) {
                lastEmittedLux = lux
                val scope = scopeRef ?: return
                scope.launch { doEmit(lux) }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun isAvailable(): Boolean = sensor != null

    override fun start(scope: CoroutineScope, mode: UploadMode, emit: Emit) {
        this.emitRef = emit
        this.scopeRef = scope
        sm?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        Logger.i("Light", "started")
    }

    override fun stop() {
        sm?.unregisterListener(listener)
        emitRef = null
        scopeRef = null
        lastEmittedLux = -1f
        Logger.i("Light", "stopped")
    }

    private suspend fun doEmit(lux: Float) {
        val emit = emitRef ?: return
        val ts = System.currentTimeMillis() / 1000
        val env = classifyEnvironment(lux)
        val json = """{"lux":${lux.toInt()},"environment":"$env","ts":$ts}"""
        runCatching { emit(SensorType.LIGHT, json) }
            .onFailure { Logger.w("Light", "emit failed: ${it.message}") }
    }

    private fun classifyEnvironment(lux: Float): String = when {
        lux < 50f  -> "dark"
        lux < 300f -> "indoor"
        else       -> "outdoor"
    }
}
