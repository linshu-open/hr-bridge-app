package cn.jarvis.hrbridge.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SensorDao {

    @Insert
    suspend fun insert(record: SensorRecordEntity): Long

    @Query("SELECT * FROM sensor_records WHERE uploaded = 0 ORDER BY timestamp ASC LIMIT :limit")
    suspend fun fetchPending(limit: Int): List<SensorRecordEntity>

    @Query("SELECT * FROM sensor_records WHERE uploaded = 0 AND sensorType = :type ORDER BY timestamp ASC LIMIT :limit")
    suspend fun fetchPendingByType(type: String, limit: Int): List<SensorRecordEntity>

    @Query("UPDATE sensor_records SET uploaded = 1, syncedAt = :now WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<Long>, now: Long)

    @Query("UPDATE sensor_records SET retryCount = retryCount + 1, lastError = :err WHERE id IN (:ids)")
    suspend fun markFailed(ids: List<Long>, err: String?)

    @Query("DELETE FROM sensor_records WHERE uploaded = 1 AND syncedAt IS NOT NULL AND syncedAt < :cutoff")
    suspend fun purgeUploaded(cutoff: Long)

    @Query("DELETE FROM sensor_records WHERE uploaded = 0 AND timestamp < :cutoff")
    suspend fun purgeExpired(cutoff: Long)

    @Query("SELECT COUNT(*) FROM sensor_records WHERE uploaded = 0")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sensor_records WHERE uploaded = 0 AND sensorType = :type")
    suspend fun countPendingByType(type: String): Int
}
