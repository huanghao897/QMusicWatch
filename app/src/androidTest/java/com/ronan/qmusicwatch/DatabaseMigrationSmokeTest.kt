package com.ronan.qmusicwatch

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ronan.qmusicwatch.data.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationSmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun reset() { context.deleteDatabase("qmusic-watch.db") }
    @After fun cleanUp() { context.deleteDatabase("qmusic-watch.db") }

    @Test fun migrationFromV2ClearsUnownedHistoryAndAddsAccountKey() {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("qmusic-watch.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE recent (trackId TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, artists TEXT NOT NULL, album TEXT NOT NULL, artworkUrl TEXT NOT NULL, playedAt INTEGER NOT NULL)")
                    db.execSQL("CREATE TABLE downloads (trackId TEXT NOT NULL, ownerAccountId TEXT NOT NULL, title TEXT NOT NULL, artists TEXT NOT NULL, artworkUrl TEXT NOT NULL, filePath TEXT NOT NULL, status TEXT NOT NULL, downloadedBytes INTEGER NOT NULL, totalBytes INTEGER NOT NULL, updatedAt INTEGER NOT NULL, groupName TEXT NOT NULL, PRIMARY KEY(trackId, ownerAccountId))")
                    db.execSQL("INSERT INTO recent VALUES ('old','Old','Artist','Album','',1)")
                    db.execSQL("INSERT INTO downloads VALUES ('partial','owner','Partial','Artist','','/data/local/tmp/partial.audio','paused',42,-1,1,'单曲缓存')")
                    db.execSQL("INSERT INTO downloads VALUES ('complete','owner','Complete','Artist','','/data/local/tmp/complete.audio','complete',84,84,1,'单曲缓存')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).also {
            it.writableDatabase
            it.close()
        }

        val database = AppDatabase.create(context)
        try {
            val names = buildList {
                database.openHelper.writableDatabase.query("PRAGMA table_info(`recent`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }
            assertTrue("ownerAccountId column missing", "ownerAccountId" in names)
            database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM recent").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            database.openHelper.writableDatabase.query("SELECT quality FROM downloads WHERE trackId = 'partial'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("legacy_unknown", cursor.getString(0))
            }
            database.openHelper.writableDatabase.query("SELECT quality FROM downloads WHERE trackId = 'complete'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("legacy_unknown", cursor.getString(0))
            }
        } finally {
            database.close()
        }
    }
}
