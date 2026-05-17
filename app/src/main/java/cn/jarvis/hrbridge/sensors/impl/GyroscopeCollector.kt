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
import cn.jarvis.hrbridge.sensors.imu.ImuWindowAggregator
import cn.jarvis.hrbridge.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlin.math.sqrt

/**
 * 陀螺仪采集 (V2 极致优化版)。
 *
 * - 纯粹的生产者：onSensorChanged 只做极轻量赋值与写入 ServiceLocator.gyroRing。
 * - 无 Coroutine timer，无实时复杂数学计算。
 */
class GyroscopeCollector(private val ctx: Context) : SensorCollector {

    override val type: String = SensorType.GYROSCOPE

    private val sm: SensorManager? = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var mode: UploadMode = UploadMode.NORMAL
    private var freqConfig: SensorFreqConfig? = null
    private var emitRef: Emit? = null
    private var scopeRef: CoroutineScope? = null

    @Volatile var lastX: Float = 0f; private set
    @Volatile var lastY: Float = 0f; private set
    @Volatile var lastZ: Float = 0f; private set
    @Volatile var lastAngularSpeed: Float = 0f; private set

    var aggregator: ImuWindowAggregator? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val speed = sqrt(x * x + y * y + z * z)

            lastX = x
            lastY = y
            lastZ = z
            lastAngularSpeed = speed

            // 写入高性能、轻量级环形缓冲区
            cn.jarvis.hrbridge.ServiceLocator.gyroRing.put(x, y, z, speed, System.currentTimeMillis().toFloat())
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun isAvailable(): Boolean = sensor != null

    override fun start(scope: CoroutineScope, mode: UploadMode, emit: Emit) {
        this.mode = mode
        this.emitRef = emit
        this.scopeRef = scope
        registerListener()
        Logger.i("Gyro", "started (V2 RingBuffer mode), mode=$mode")
    }

    override fun onModeChanged(mode: UploadMode) {
        if (this.mode == mode) return
        this.mode = mode
        sm?.unregisterListener(listener)
        registerListener()
    }

    override fun applyFrequency(config: SensorFreqConfig) {
        if (config == freqConfig) return
        freqConfig = config
        sm?.unregisterListener(listener)
        registerListener()
        Logger.d("Gyro", "freq applied: delay=${config.gyroDelayUs}us latency=${config.gyroReportLatencyUs}us")
    }

    override fun stop() {
        sm?.unregisterListener(listener)
        emitRef = null
        scopeRef = null
        Logger.i("Gyro", "stopped")
    }

    private fun sensorDelayUs(): Int = freqConfig?.gyroDelayUs ?: when (mode) {
        UploadMode.POWER_SAVER -> 200_000
        UploadMode.NORMAL      -> 200_000
        UploadMode.REALTIME    ->  40_000
    }

    private fun maxReportLatencyUs(): Int = freqConfig?.gyroReportLatencyUs ?: when (mode) {
        UploadMode.POWER_SAVER -> 60_000_000
        UploadMode.NORMAL      -> 30_000_000
        UploadMode.REALTIME    ->  5_000_000
    }

    private fun registerListener() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            sm?.registerListener(listener, sensor, sensorDelayUs(), maxReportLatencyUs())
        } else {
            sm?.registerListener(listener, sensor, sensorDelayUs())
        }
    }
}
