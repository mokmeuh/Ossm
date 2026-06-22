package com.ossm.remote.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val speed: Float,
    val depth: Float,
    val strokeLength: Float,
    val sensation: Float,
    val patternId: Int,
    val createdAt: Long
)
