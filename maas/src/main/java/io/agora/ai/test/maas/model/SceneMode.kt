package io.agora.ai.test.maas.model

data class SceneMode(
    val language: String,
    val speechFrameSampleRates: Int,
    val speechFrameChannels: Int,
    val speechFrameBits: Int
)
