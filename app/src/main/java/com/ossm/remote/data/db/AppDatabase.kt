package com.ossm.remote.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PresetEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun presetDao(): PresetDao
}
