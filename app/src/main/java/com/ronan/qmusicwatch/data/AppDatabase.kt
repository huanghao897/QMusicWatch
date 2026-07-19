package com.ronan.qmusicwatch.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "recent", primaryKeys = ["trackId", "ownerAccountId"])
data class RecentEntity(
    val trackId: String, val ownerAccountId: String, val title: String, val artists: String, val album: String,
    val artworkUrl: String, val playedAt: Long,
)

@Entity(tableName = "downloads", primaryKeys = ["trackId", "ownerAccountId"])
data class DownloadEntity(
    val trackId: String, val ownerAccountId: String, val title: String, val artists: String,
    val artworkUrl: String, val filePath: String, val status: String, val downloadedBytes: Long = 0,
    val totalBytes: Long = -1, val updatedAt: Long = System.currentTimeMillis(),
    val groupName: String = "单曲缓存",
    @ColumnInfo(defaultValue = "'standard'") val quality: String = "standard",
)

@Dao interface RecentDao {
    @Query("SELECT * FROM recent WHERE ownerAccountId = :owner ORDER BY playedAt DESC LIMIT 100") suspend fun all(owner: String): List<RecentEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: RecentEntity)
}

@Dao interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY updatedAt DESC") fun observeAll(): Flow<List<DownloadEntity>>
    @Query("SELECT * FROM downloads") suspend fun all(): List<DownloadEntity>
    @Query("SELECT * FROM downloads WHERE trackId = :id AND ownerAccountId = :owner LIMIT 1") suspend fun find(id: String, owner: String): DownloadEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: DownloadEntity)
    @Query("UPDATE downloads SET status = :status, downloadedBytes = :bytes, totalBytes = :total, updatedAt = :now WHERE trackId = :id AND ownerAccountId = :owner")
    suspend fun progress(id: String, owner: String, status: String, bytes: Long, total: Long, now: Long = System.currentTimeMillis())
    @Query("DELETE FROM downloads WHERE trackId = :id AND ownerAccountId = :owner") suspend fun delete(id: String, owner: String)
}

@Database(entities = [RecentEntity::class, DownloadEntity::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recent(): RecentDao
    abstract fun downloads(): DownloadDao
    companion object {
        private val migration1To2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN groupName TEXT NOT NULL DEFAULT '单曲缓存'")
            }
        }
        private val migration2To3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE recent")
                db.execSQL("CREATE TABLE IF NOT EXISTS recent (trackId TEXT NOT NULL, ownerAccountId TEXT NOT NULL, title TEXT NOT NULL, artists TEXT NOT NULL, album TEXT NOT NULL, artworkUrl TEXT NOT NULL, playedAt INTEGER NOT NULL, PRIMARY KEY(trackId, ownerAccountId))")
            }
        }
        private val migration3To4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN quality TEXT NOT NULL DEFAULT 'standard'")
                db.execSQL("UPDATE downloads SET quality = 'legacy_unknown'")
            }
        }
        private val migration4To5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("UPDATE downloads SET quality = 'legacy_unknown' WHERE status != 'complete'")
            }
        }
        fun create(context: Context) = Room.databaseBuilder(context, AppDatabase::class.java, "qmusic-watch.db")
            .addMigrations(migration1To2, migration2To3, migration3To4, migration4To5)
            .build()
    }
}
