package com.ossm.remote.model

import com.google.gson.annotations.SerializedName

data class FunscriptAction(
    @SerializedName("at") val atMs: Long,   // milliseconds
    @SerializedName("pos") val pos: Int      // 0-100
)

data class Funscript(
    @SerializedName("actions") val actions: List<FunscriptAction>,
    @SerializedName("metadata") val metadata: FunscriptMetadata? = null
)

data class FunscriptMetadata(
    @SerializedName("title") val title: String? = null,
    @SerializedName("duration") val duration: Double? = null
)
