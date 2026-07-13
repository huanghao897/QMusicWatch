package com.ronan.qmusicwatch.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "recent")
data class RecentEntity(
    @PrimaryKey val trackId: String, val title: String, val artists: String, val album: String,
    val artworkUrl: String, val playedAt: Long,
)

@Entity(tableName = "downloads", primaryKeys = ["trackId", "ownerAccountId"])
data class DownloadEntity(
    val trackId: String, val ownerAccountId: String, val title: String, val artists: String,
    val artworkUrl: String, val filePath: String, val status: String, val downloadedBytes: Long = 0,
    val totalBytes: Long = -1, val updatedAt: Long = System.currentTimeMillis(),
    val groupName: String = "单曲缓存",
)

@Dao interface RecentDao {
    @Query("SELECT * FROM recent ORDER BY playedAt DESC LIMIT 100") suspend fun all(): List<RecentEntity>
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

@Database(entities = [RecentEntity::class, DownloadEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recent(): RecentDao
    abstract fun downloads(): DownloadDao
    companion object {
        private val migration1To2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN groupName TEXT NOT NULL DEFAULT '单曲缓存'")
            }
        }
        fun create(context: Context) = Room.databaseBuilder(context, AppDatabase::class.java, "qmusic-watch.db").addMigrations(migration1To2).build()
    }
}
