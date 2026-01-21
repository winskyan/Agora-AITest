package io.agora.ai.test.model

import com.google.gson.annotations.SerializedName

data class Config(
    @SerializedName("params")
    val params: List<Map<String, Any>>,
    @SerializedName("audioProfile")
    val audioProfile: List<Map<String, Int>>,
    @SerializedName("audioScenario")
    val audioScenario: List<Map<String, Int>>,
    @SerializedName("codecType")
    val codecType: List<Map<String, Int>>
)