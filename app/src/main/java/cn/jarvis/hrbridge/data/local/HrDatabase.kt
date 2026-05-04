package cn.jarvis.hrbridge.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [HrRecordEntity::class, DeviceEntity::class, SensorRecordEntity::class],
    version = 2,
    exportSchema = false
)
abstract class HrDatabase : RoomDatabase() {
    abstract fun hrDao(): HrDao
    abstract fun deviceDao(): DeviceDao
    abstract fun sensorDao(): SensorDao

    companion object {
        private const val DB_NAME = "hr_bridge.db"

        @Volatile private var instance: HrDatabase? = null

        fun get(ctx: Context): HrDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    ctx.applicationContext,
                    HrDatabase::class.java,
                    DB_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
