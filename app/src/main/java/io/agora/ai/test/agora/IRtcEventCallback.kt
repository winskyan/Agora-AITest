package io.agora.ai.test.agora

import io.agora.rtc2.IRtcEngineEventHandler

interface IRtcEventCallback {
    fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {

    }

    fun onLeaveChannelSuccess() {

    }

    fun onUserJoined(uid: Int, elapsed: Int) {

    }

    fun onUserOffline(uid: Int, reason: Int) {

    }

    fun onAudioVolumeIndication(
        speakers: ArrayList<IRtcEngineEventHandler.AudioVolumeInfo>,
        totalVolume: Int
    ) {

    }

    fun onStreamMessage(uid: Int, data: ByteArray?) {

    }

    fun onAudioMetadataReceived(uid: Int, metadata: ByteArray?) {

    }

    fun onPlaybackAudioFrameFinished() {

    }
}