package cn.jarvis.hrbridge.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import cn.jarvis.hrbridge.ServiceLocator
import cn.jarvis.hrbridge.data.local.HrDatabase
import cn.jarvis.hrbridge.data.local.SensorRecordEntity
import cn.jarvis.hrbridge.sensors.SensorType
import cn.jarvis.hrbridge.sensors.imu.AccelSample
import cn.jarvis.hrbridge.sensors.imu.GyroSample
import cn.jarvis.hrbridge.sensors.imu.ImuWindowJson
import cn.jarvis.hrbridge.util.Logger
import kotlin.math.sqrt

/**
 * 核心定时器：由 AlarmManager 精确唤醒。
 * 在 WakeLock 保护下执行：
 *  1. 从 RingBuffer 中 snapshot 原始加速度计/陀螺仪数据
 *  2. 批量聚合、分析并写入本地 Room DB
 *  3. 执行同步 OkHttp 批量上传 (SyncUploader.flushSync)
 *  4. 自动调度下一次 Alarm
 */
class SensorFlushReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wl = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis:SensorFlush")
        wl?.acquire(15_000L) // 锁定最多 15 秒

        Logger.i("SensorFlushReceiver", "Alarm wake-up triggered, processing sensor buffers...")

        try {
            // 确保 ServiceLocator 已初始化（保活可能发生）
            ServiceLocator.init(context.applicationContext)

            val settings = ServiceLocator.settingsStore.cache
            val enabledSensors = settings.enabledSensors

            // 1. 读取并清空 RingBuffer 原始数据
            val (accelData, accelN) = ServiceLocator.accelRing.snapshotAndClear()
            val (gyroData, gyroN) = ServiceLocator.gyroRing.snapshotAndClear()

            Logger.d("SensorFlushReceiver", "Drained ring buffers: accelN=$accelN, gyroN=$gyroN")

            val nowMs = System.currentTimeMillis()
            val ts = nowMs / 1000

            // 2. 如果启用了 IMU_WINDOW，则将其喂入 ImuWindowAggregator 批量聚合
            val isImuEnabled = SensorType.IMU_WINDOW in enabledSensors
            val hasRawAccelEnabled = SensorType.ACCELEROMETER in enabledSensors
            val hasRawGyroEnabled = SensorType.GYROSCOPE in enabledSensors

            // M1B shared aggregator
            val aggregator = ServiceLocator.sensorHub.imuAggregator

            // Feed raw accel to aggregator
            if (accelN > 0) {
                for (i in 0 until accelN) {
                    val offset = i * 5
                    val x = accelData[offset + 0]
                    val y = accelData[offset + 1]
                    val z = accelData[offset + 2]
                    val mag = accelData[offset + 3]
                    val tMs = accelData[offset + 4].toLong()
                    aggregator.addAccel(AccelSample(tMs, x, y, z, mag))
                }
            }

            // Feed raw gyro to aggregator
            if (gyroN > 0) {
                for (i in 0 until gyroN) {
                    val offset = i * 5
                    val x = gyroData[offset + 0]
                    val y = gyroData[offset + 1]
                    val z = gyroData[offset + 2]
                    val speed = gyroData[offset + 3]
                    val tMs = gyroData[offset + 4].toLong()
                    aggregator.addGyro(GyroSample(tMs, x, y, z, speed))
                }
            }

            // 判定并生成 IMU_WINDOW
            if (isImuEnabled) {
                val feature = aggregator.pollCompleted(nowMs)
                if (feature != null) {
                    val imuJson = ImuWindowJson.toJson(feature, "android-phone")
                    ServiceLocator.sensorRepository.ingestSync(SensorType.IMU_WINDOW, imuJson)
                    aggregator.clearLocked()
                    Logger.i("SensorFlushReceiver", "Generated & saved IMU window feature")
                }
            } else {
                // 如果用户没有开启，清空聚合器以防内存泄露
                aggregator.clearLocked()
            }

            // 3. 计算并生成 ACCELEROMETER 汇总数据并入库
            if (hasRawAccelEnabled && accelN > 0) {
                var sumMag = 0f
                var lastX = 0f
                var lastY = 0f
                var lastZ = 0f
                var maxMag = 0f
                var minMag = 9.8f
                for (i in 0 until accelN) {
                    val mag = accelData[i * 5 + 3]
                    sumMag += mag
                    if (mag > maxMag) maxMag = mag
                    if (mag < minMag) minMag = mag
                    if (i == accelN - 1) {
                        lastX = accelData[i * 5 + 0]
                        lastY = accelData[i * 5 + 1]
                        lastZ = accelData[i * 5 + 2]
                    }
                }
                val avgMag = sumMag / accelN

                // 运动状态判定
                val currentActivity = classifyActivity(avgMag)
                if (currentActivity == "still") {
                    stillDurationMs += 60_000L
                } else {
                    stillDurationMs = 0L
                }
                val stillMin = (stillDurationMs / 60_000L).toInt()

                // 跌倒检测批量分析
                var fallDetected = false
                var prevMagnitude = 9.81f
                var fallCandidateTs = 0L
                for (i in 0 until accelN) {
                    val mag = accelData[i * 5 + 3]
                    val tMs = accelData[i * 5 + 4].toLong()
                    if (prevMagnitude > 15f && mag < 5f) {
                        fallCandidateTs = tMs
                    }
                    if (fallCandidateTs > 0L && tMs - fallCandidateTs in 500..3000 && mag < 3f) {
                        fallDetected = true
                        fallCandidateTs = 0L
                    }
                    prevMagnitude = mag
                }

                // 喂入 MotionStateDetector（触发 SensorHub 的动态采样率调整）
                ServiceLocator.sensorHub.motionDetector.feedMagnitude(avgMag)

                val accelJson = buildString {
                    append("{")
                    append("\"magnitude\":${"%.2f".format(avgMag)},")
                    append("\"x\":${"%.2f".format(lastX)},")
                    append("\"y\":${"%.2f".format(lastY)},")
                    append("\"z\":${"%.2f".format(lastZ)},")
                    append("\"activity\":\"$currentActivity\",")
                    append("\"fall_detected\":$fallDetected,")
                    append("\"still_duration_min\":$stillMin,")
                    append("\"ts\":$ts")
                    append("}")
                }
                ServiceLocator.sensorRepository.ingestSync(SensorType.ACCELEROMETER, accelJson)
                Logger.d("SensorFlushReceiver", "Generated raw accelerometer summary")
            }

            // 4. 计算并生成 GYROSCOPE 汇总数据并入库
            if (hasRawGyroEnabled && gyroN > 0) {
                var lastGyroX = 0f
                var lastGyroY = 0f
                var lastGyroZ = 0f
                var lastSpeed = 0f
                val lastOffset = (gyroN - 1) * 5
                lastGyroX = gyroData[lastOffset + 0]
                lastGyroY = gyroData[lastOffset + 1]
                lastGyroZ = gyroData[lastOffset + 2]
                lastSpeed = gyroData[lastOffset + 3]

                val posture = when {
                    lastSpeed < 0.1f -> "upright"
                    lastGyroY > 0.5f -> "lying"
                    else            -> "tilted"
                }

                val gyroJson = buildString {
                    append("{")
                    append("\"angular_speed\":${"%.2f".format(lastSpeed)},")
                    append("\"x\":${"%.2f".format(lastGyroX)},")
                    append("\"y\":${"%.2f".format(lastGyroY)},")
                    append("\"z\":${"%.2f".format(lastGyroZ)},")
                    append("\"posture\":\"$posture\",")
                    append("\"ts\":$ts")
                    append("}")
                }
                ServiceLocator.sensorRepository.ingestSync(SensorType.GYROSCOPE, gyroJson)
                Logger.d("SensorFlushReceiver", "Generated raw gyroscope summary")
            }

            // 5. 执行同步 HTTP 数据刷新上传
            val db = HrDatabase.get(context)
            val uploadedCount = SyncUploader.flushSync(db)
            Logger.i("SensorFlushReceiver", "Flush complete, successfully uploaded $uploadedCount records.")

        } catch (e: Exception) {
            Logger.e("SensorFlushReceiver", "Error during sensor flush alarm: ${e.message}", e)
        } finally {
            wl?.release()

            // 6. 再次精确调度自己（持续工作），从 settings 获取动态上传频率
            val settings = ServiceLocator.settingsStore.cache
            val intervalMs = when (settings.uploadMode) {
                cn.jarvis.hrbridge.sensors.UploadMode.POWER_SAVER -> 5 * 60_000L
                cn.jarvis.hrbridge.sensors.UploadMode.NORMAL      -> 60_000L
                cn.jarvis.hrbridge.sensors.UploadMode.REALTIME    -> 10_000L
            }
            scheduleNext(context, intervalMs)
        }
    }

    private fun classifyActivity(mag: Float): String = when {
        mag < 0.5f -> "still"
        mag <= 11.5f -> "still"
        mag < 14f -> "walking"
        mag < 20f -> "running"
        else -> "vigorous"
    }

    companion object {
        @Volatile
        private var stillDurationMs: Long = 0L

        fun scheduleNext(context: Context, intervalMs: Long) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, SensorFlushReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context,
                9002,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerTime = SystemClock.elapsedRealtime() + intervalMs
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pi)
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pi)
            }
            Logger.d("SensorFlushReceiver", "Scheduled next flush alarm in ${intervalMs / 1000} seconds.")
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, SensorFlushReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context,
                9002,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
            Logger.d("SensorFlushReceiver", "Cancelled pending flush alarms.")
        }
    }
}
