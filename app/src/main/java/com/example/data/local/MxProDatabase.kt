package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cached_json")
data class CachedJsonEntity(
    @PrimaryKey val cacheKey: String,
    val jsonPayload: String,
    val updatedAtMs: Long
)

@Entity(tableName = "pending_laps")
data class PendingLapEntity(
    @PrimaryKey val lapId: String,
    val circuitId: String,
    val jsonPayload: String,
    val createdAtMs: Long
)

@Dao
interface MxCacheDao {
    @Query("SELECT * FROM cached_json WHERE cacheKey = :key")
    suspend fun getCache(key: String): CachedJsonEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCache(entity: CachedJsonEntity)

    @Query("SELECT * FROM pending_laps ORDER BY createdAtMs ASC")
    fun observePendingLaps(): Flow<List<PendingLapEntity>>

    @Query("SELECT * FROM pending_laps ORDER BY createdAtMs ASC")
    suspend fun getPendingLaps(): List<PendingLapEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueuePendingLap(entity: PendingLapEntity)

    @Query("DELETE FROM pending_laps WHERE lapId = :lapId")
    suspend fun removePendingLap(lapId: String)
}

@Database(
    entities = [CachedJsonEntity::class, PendingLapEntity::class],
    version = 1,
    exportSchema = false
)
abstract class MxProDatabase : RoomDatabase() {
    abstract fun cacheDao(): MxCacheDao

    companion object {
        @Volatile
        private var INSTANCE: MxProDatabase? = null

        fun getInstance(context: Context): MxProDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MxProDatabase::class.java,
                    "metropolitano_mx_pro.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
