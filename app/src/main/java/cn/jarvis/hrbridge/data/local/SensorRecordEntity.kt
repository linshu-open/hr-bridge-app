package cn.jarvis.hrbridge.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 通用传感器记录（Sensor Bridge 2.0 通用上传通道）。
 *
 * - `sensorType` 对应服务端 `/jarvis/sensor/{type}` 的路径段
 *   （heart_rate / step_count / location / accelerometer / gyroscope / light / bluetooth / sleep ...）
 * - `dataJson` 为即将 POST 的 JSON body（UTF-8 字符串），由 Collector 组装后写入
 * - `timestamp` 秒级 epoch
 * - 心率保留专用表 [HrRecordEntity]，不走这张通用表；其他传感器全部走这里
 */
@Entity(
    tableName = "sensor_records",
    indices = [Index(value = ["uploaded", "timestamp"]), Index(value = ["sensorType"])]
)
data class SensorRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sensorType: String,
    val dataJson: String,
    val timestamp: Long,
    val uploaded: Boolean = false,
    val syncedAt: Long? = null,
    val retryCount: Int = 0,
    val lastError: String? = null
)
