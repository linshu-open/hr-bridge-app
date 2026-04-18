package cn.jarvis.hrbridge.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 心率记录：本地缓存表，断网/失败时留存，用于 WorkManager 重试上送。
 *
 * 状态机：
 *   pending  → uploading → uploaded  (正常)
 *            ↘ failed → pending (重试)
 *            ↘ expired (> 24h 仍未送达)
 */
@Entity(
    tableName = "hr_records",
    indices = [
        Index(value = ["uploadState", "timestamp"]),
        Index(value = ["timestamp"])
    ]
)
data class HrRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "hr") val hr: Int,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "trend") val trend: String,
    @ColumnInfo(name = "device") val device: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long,          // Unix 秒
    @ColumnInfo(name = "uploadState") val uploadState: String = STATE_PENDING,
    @ColumnInfo(name = "attempts") val attempts: Int = 0,
    @ColumnInfo(name = "uploadedAt") val uploadedAt: Long? = null,
    @ColumnInfo(name = "lastError") val lastError: String? = null
) {
    companion object {
        const val STATE_PENDING   = "pending"
        const val STATE_UPLOADING = "uploading"
        const val STATE_UPLOADED  = "uploaded"
        const val STATE_FAILED    = "failed"
        const val STATE_EXPIRED   = "expired"
    }
}
