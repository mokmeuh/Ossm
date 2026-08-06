package com.ossm.remote.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PresetEntity::class, SessionEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun presetDao(): PresetDao
    abstract fun sessionDao(): SessionDao
}
