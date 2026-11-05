package com.example.memosync.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * NOT COMPILED IN THIS POC - Android source skeleton only.
 *
 * Room database outline matching design doc §3.3. Entities/DAOs are sketches: no migrations,
 * no schema export, no instrumentation tests in this POC (see ../README.md).
 */
@Database(
    entities = [
        MemoEntity::class,
        MemoPeerBaseEntity::class,
        SyncStateEntity::class,
        ConflictEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun memoDao(): MemoDao
    abstract fun peerBaseDao(): PeerBaseDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun conflictDao(): ConflictDao

    companion object {
        private const val DB_NAME = "memo-sync.db"

        @Volatile
        private var instance: AppDatabase? = null

        /** Process-wide singleton database. */
        fun create(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                ).build().also { instance = it }
            }
        }

        // TODO(Android): add migrations for schema changes; never use destructive fallback on
        // user data. TODO(Android): enable WAL + a dedicated sync IO dispatcher.
    }
}
