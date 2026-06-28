package com.ossm.remote.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val patternKey: String,
    val patternName: String,
    val speed: Float,
    val depthMin: Float,
    val depthMax: Float,
    val createdAt: Long
)
