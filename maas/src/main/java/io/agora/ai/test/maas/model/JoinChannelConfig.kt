package io.agora.ai.test.maas.model

class JoinChannelConfig {
    var enableStereoTest: Boolean = false
    var enableSaveAudio: Boolean = false
    var enablePushExternalVideo: Boolean = false
    var enableEncryption: Boolean = false
    var encryptionConfig: MassEncryptionConfig? = null
    var enablePullAudioFrame: Boolean = false
    var enableSendVideoMetadata: Boolean = false
    var enableCustomDirectAudioTracker: Boolean = false
    var enableWriteRecordingAudioFrame: Boolean = false

    override fun toString(): String {
        return "JoinChannelConfig(enableStereoTest=$enableStereoTest, enableSaveAudio=$enableSaveAudio, enablePushExternalVideo=$enablePushExternalVideo, " +
                "enableEncryption=$enableEncryption, encryptionConfig=$encryptionConfig, enablePullAudioFrame=$enablePullAudioFrame , " +
                "enableSendVideoMetadata=$enableSendVideoMetadata , enableCustomDirectAudioTracker=$enableCustomDirectAudioTracker,enableWriteRecordingAudioFrame=$enableWriteRecordingAudioFrame)"
    }
}