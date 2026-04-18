package cn.jarvis.hrbridge.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 已配对设备：支持多设备，首选项可在 SettingsStore 中记录当前选择。 */
@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val mac: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "lastSeen") val lastSeen: Long,
    @ColumnInfo(name = "lastRssi") val lastRssi: Int? = null
)
