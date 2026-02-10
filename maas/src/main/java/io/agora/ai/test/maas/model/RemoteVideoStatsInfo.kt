package io.agora.ai.test.maas.model

import io.agora.ai.test.maas.MaaSConstants

data class RemoteVideoStatsInfo(
    val uid: Int,
    val width: Int,
    val height: Int,
    val receivedBitrate: Int, // 码率，单位 kbps
    val streamType: MaaSConstants.VideoStreamType
)
