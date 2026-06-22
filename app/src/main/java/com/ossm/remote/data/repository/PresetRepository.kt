package com.ossm.remote.data.repository

import com.ossm.remote.data.db.PresetDao
import com.ossm.remote.data.db.PresetEntity
import com.ossm.remote.model.Preset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PresetRepository @Inject constructor(
    private val dao: PresetDao
) {
    val presets: Flow<List<Preset>> = dao.getAllPresets().map { list ->
        list.map { it.toModel() }
    }

    suspend fun save(preset: Preset): Long = dao.insertPreset(preset.toEntity())
    suspend fun delete(preset: Preset) = dao.deleteById(preset.id)

    private fun PresetEntity.toModel() = Preset(id, name, speed, depth, strokeLength, sensation, patternId, createdAt)
    private fun Preset.toEntity() = PresetEntity(id, name, speed, depth, strokeLength, sensation, patternId, createdAt)
}
