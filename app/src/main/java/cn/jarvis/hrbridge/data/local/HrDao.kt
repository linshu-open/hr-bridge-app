package cn.jarvis.hrbridge.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HrDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: HrRecordEntity): Long

    @Query("SELECT * FROM hr_records WHERE timestamp >= :sinceSec ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(sinceSec: Long, limit: Int = 360): Flow<List<HrRecordEntity>>

    @Query("SELECT * FROM hr_records WHERE uploadState IN (:states) ORDER BY timestamp ASC LIMIT :limit")
    suspend fun fetchByStates(states: List<String>, limit: Int = 100): List<HrRecordEntity>

    @Query("UPDATE hr_records SET uploadState = :state, attempts = attempts + 1, lastError = :error WHERE id IN (:ids)")
    suspend fun updateState(ids: List<Long>, state: String, error: String? = null)

    @Query("UPDATE hr_records SET uploadState = :state, uploadedAt = :uploadedAt WHERE id IN (:ids)")
    suspend fun markUploaded(ids: List<Long>, state: String = HrRecordEntity.STATE_UPLOADED, uploadedAt: Long)

    @Query("UPDATE hr_records SET uploadState = :expiredState WHERE uploadState != :uploadedState AND timestamp < :cutoffSec")
    suspend fun expireOldRecords(
        cutoffSec: Long,
        expiredState: String = HrRecordEntity.STATE_EXPIRED,
        uploadedState: String = HrRecordEntity.STATE_UPLOADED
    ): Int

    @Query("DELETE FROM hr_records WHERE uploadState = :state AND uploadedAt < :cutoffSec")
    suspend fun purgeUploaded(cutoffSec: Long, state: String = HrRecordEntity.STATE_UPLOADED): Int

    @Query("SELECT COUNT(*) FROM hr_records WHERE uploadState IN (:states)")
    fun observePendingCount(states: List<String> = listOf(HrRecordEntity.STATE_PENDING, HrRecordEntity.STATE_FAILED)): Flow<Int>

    @Query("SELECT MIN(hr) AS min, MAX(hr) AS max, AVG(hr) AS avg, COUNT(*) AS count FROM hr_records WHERE timestamp >= :sinceSec")
    suspend fun statsSince(sinceSec: Long): HrStatsRow?
}

data class HrStatsRow(
    val min: Int,
    val max: Int,
    val avg: Double,
    val count: Int
)
