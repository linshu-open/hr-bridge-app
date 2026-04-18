package cn.jarvis.hrbridge.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: DeviceEntity)

    @Query("SELECT * FROM devices ORDER BY lastSeen DESC")
    fun observeAll(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM devices WHERE mac = :mac LIMIT 1")
    suspend fun findByMac(mac: String): DeviceEntity?

    @Query("DELETE FROM devices WHERE mac = :mac")
    suspend fun delete(mac: String)
}
