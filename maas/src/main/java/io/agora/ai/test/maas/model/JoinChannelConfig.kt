package io.agora.ai.test.maas.model

class JoinChannelConfig {
    var enableStereoTest: Boolean = false
    var enableSaveAudio: Boolean = false
    var enablePushExternalVideo: Boolean = false

    override fun toString(): String {
        return "JoinChannelConfig(enableStereoTest=$enableStereoTest, enableSaveAudio=$enableSaveAudio, enablePushExternalVideo=$enablePushExternalVideo)"
    }
}