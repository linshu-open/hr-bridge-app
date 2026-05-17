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
import cn.jarvis.hrbridge.sensors.MotionStateDetector
import cn.jarvis.hrbridge.sensors.imu.ImuWindowAggregator
import cn.jarvis.hrbridge.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlin.math.sqrt

/**
 * 加速度计采集 (V2 极致优化版)。
 *
 * - 纯粹的生产者：onSensorChanged 只做极轻量赋值与写入 ServiceLocator.accelRing。
 * - 无 Coroutine timer，无实时复杂数学计算（运动/跌倒检测全部移至 60s 唤醒周期内进行批量处理）。
 */
class AccelerometerCollector(private val ctx: Context) : SensorCollector {

    override val type: String = SensorType.ACCELEROMETER

    private val sm: SensorManager? = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var mode: UploadMode = UploadMode.NORMAL
    private var freqConfig: SensorFreqConfig? = null
    private var emitRef: Emit? = null
    private var scopeRef: CoroutineScope? = null

    // 保留这几个 volatile 变量，以防外部或日志有极速查询需要
    @Volatile var lastX: Float = 0f; private set
    @Volatile var lastY: Float = 0f; private set
    @Volatile var lastZ: Float = 0f; private set
    @Volatile var lastMagnitude: Float = 9.81f; private set

    // 以下两个为了保持 ServiceLocator 初始化处的 API 兼容性，在 V2 中实际计算转移到 Alarm 周期内
    var aggregator: ImuWindowAggregator? = null
    var motionDetector: MotionStateDetector? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val mag = sqrt(x * x + y * y + z * z)

            lastX = x
            lastY = y
            lastZ = z
            lastMagnitude = mag

            // 写入高性能、无锁/轻量级环形缓冲区
            cn.jarvis.hrbridge.ServiceLocator.accelRing.put(x, y, z, mag, System.currentTimeMillis().toFloat())
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    override fun isAvailable(): Boolean = sensor != null

    override fun start(scope: CoroutineScope, mode: UploadMode, emit: Emit) {
        this.mode = mode
        this.emitRef = emit
        this.scopeRef = scope
        registerListener()
        Logger.i("Accel", "started (V2 RingBuffer mode), mode=$mode")
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
        Logger.d("Accel", "freq applied: delay=${config.accelDelayUs}us latency=${config.accelReportLatencyUs}us")
    }

    override fun stop() {
        sm?.unregisterListener(listener)
        emitRef = null
        scopeRef = null
        Logger.i("Accel", "stopped")
    }

    private fun sensorDelayUs(): Int = freqConfig?.accelDelayUs ?: when (mode) {
        UploadMode.POWER_SAVER -> 200_000   // ~5Hz
        UploadMode.NORMAL      -> 100_000   // ~10Hz
        UploadMode.REALTIME    ->  20_000   // ~50Hz
    }

    private fun maxReportLatencyUs(): Int = freqConfig?.accelReportLatencyUs ?: when (mode) {
        UploadMode.POWER_SAVER -> 60_000_000 // 60s
        UploadMode.NORMAL      -> 30_000_000 // 30s
        UploadMode.REALTIME    ->  5_000_000 // 5s
    }

    private fun registerListener() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            sm?.registerListener(listener, sensor, sensorDelayUs(), maxReportLatencyUs())
        } else {
            sm?.registerListener(listener, sensor, sensorDelayUs())
        }
    }
}
