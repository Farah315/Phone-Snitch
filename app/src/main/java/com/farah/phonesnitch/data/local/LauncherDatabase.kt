package com.farah.phonesnitch.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** One row per app opening from the launcher. */
@Entity(
    tableName = "app_launches",
    indices = [Index("packageName"), Index("timestamp")],
)
data class AppLaunchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val timestamp: Long,
    val slot: String,
)

/** Latest AI classification per app. peakSlots is a comma separated list of slot keys. */
@Entity(tableName = "app_categories")
data class AppCategoryEntity(
    @PrimaryKey val packageName: String,
    val category: String,
    val confidence: Float,
    val peakSlots: String,
    val updatedAt: Long,
)

/** The user's own choices per app: pinned to the top, and/or a manual category. */
@Entity(tableName = "app_prefs")
data class AppPrefEntity(
    @PrimaryKey val packageName: String,
    val pinned: Boolean = false,
    val categoryOverride: String? = null,
    val dailyLimitMinutes: Int? = null,
)

data class UsageRow(val packageName: String, val slot: String, val cnt: Int)

@Dao
interface LauncherDao {
    @Insert
    suspend fun insertLaunch(entity: AppLaunchEntity)

    @Insert
    suspend fun insertLaunches(items: List<AppLaunchEntity>)

    @Query("DELETE FROM app_launches WHERE timestamp >= :since")
    suspend fun deleteLaunchesSince(since: Long)

    @Query(
        "SELECT packageName, slot, COUNT(*) AS cnt FROM app_launches " +
            "WHERE timestamp >= :since GROUP BY packageName, slot"
    )
    fun observeUsage(since: Long): Flow<List<UsageRow>>

    @Query(
        "SELECT packageName, slot, COUNT(*) AS cnt FROM app_launches " +
            "WHERE timestamp >= :since GROUP BY packageName, slot"
    )
    suspend fun usage(since: Long): List<UsageRow>

    @Query("SELECT * FROM app_launches WHERE timestamp >= :since ORDER BY timestamp DESC")
    fun observeLaunches(since: Long): Flow<List<AppLaunchEntity>>

    @Query("SELECT * FROM app_categories")
    fun observeCategories(): Flow<List<AppCategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(items: List<AppCategoryEntity>)

    @Query("SELECT * FROM app_prefs")
    fun observePrefs(): Flow<List<AppPrefEntity>>

    @Query("SELECT * FROM app_prefs WHERE packageName = :packageName")
    suspend fun getPref(packageName: String): AppPrefEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPref(pref: AppPrefEntity)
}

@Database(
    entities = [AppLaunchEntity::class, AppCategoryEntity::class, AppPrefEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class LauncherDatabase : RoomDatabase() {
    abstract fun dao(): LauncherDao
}

/** v2 -> v3: per-app daily limit in minutes (null = no limit). */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `app_prefs` ADD COLUMN `dailyLimitMinutes` INTEGER")
    }
}

/** v1 -> v2: adds the user preferences table. Existing launch history is kept. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `app_prefs` (" +
                "`packageName` TEXT NOT NULL, " +
                "`pinned` INTEGER NOT NULL, " +
                "`categoryOverride` TEXT, " +
                "PRIMARY KEY(`packageName`))"
        )
    }
}
