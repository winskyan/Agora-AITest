package io.agora.ai.test.maas

import io.agora.ai.test.maas.model.AudioVolumeInfo


interface MaaSEngineEventHandler {
    fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int)
    fun onLeaveChannelSuccess()
    fun onUserJoined(uid: Int, elapsed: Int)
    fun onUserOffline(uid: Int, reason: Int)
    fun onAudioVolumeIndication(
        speakers: ArrayList<AudioVolumeInfo>,
        totalVolume: Int
    )

    fun onStreamMessage(uid: Int, data: ByteArray?)

    fun onAudioMetadataReceived(uid: Int, metadata: ByteArray?)
}