package io.agora.ai.test.maas.model

import io.agora.ai.test.maas.MaaSConstants

data class RemoteUserInfo(
    val userId: Int,
    var streamType: MaaSConstants.VideoStreamType = MaaSConstants.VideoStreamType.VIDEO_STREAM_HIGH
) {
    override fun toString(): String {
        val streamTypeStr = when (streamType) {
            MaaSConstants.VideoStreamType.VIDEO_STREAM_HIGH -> "HIGH"
            MaaSConstants.VideoStreamType.VIDEO_STREAM_LOW -> "LOW"
        }
        return "RemoteUserInfo(userId=$userId, streamType=$streamTypeStr)"
    }
}
