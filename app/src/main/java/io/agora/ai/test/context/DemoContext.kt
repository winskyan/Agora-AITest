package io.agora.ai.test.context

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.agora.ai.test.model.Config

object DemoContext {
    private var params: Array<String> = emptyArray()
    private var audioProfile: Int = 0//Constants.AUDIO_PROFILE_DEFAULT
    private var audioScenario: Int = 7 //Constants.AUDIO_SCENARIO_CHORUS

    private var enableAudio = false
    private var enableVideo = false
    private var enableRtm = true
    private var enableStereoTest = false
    private var enableSaveAudio = false

    private var enableTestRtcDataStreamMessage = true
    private var enableTestRtmMessage = true
    private var enableTestRtcAudioMetadata = false

    fun clearParams() {
        params = emptyArray()
    }

    fun addParams(params: String) {
        DemoContext.params += params
    }

    fun removeParams(param: String) {
        params = params.filter { it != param }.toTypedArray()
    }

    fun getParams(): Array<String> {
        return params
    }

    fun setAudioProfile(audioProfile: Int) {
        DemoContext.audioProfile = audioProfile
    }

    fun getAudioProfile(): Int {
        return audioProfile
    }

    fun setAudioScenario(audioScenario: Int) {
        DemoContext.audioScenario = audioScenario
    }

    fun getAudioScenario(): Int {
        return audioScenario
    }

    fun parseConfigJson(jsonString: String): Config {
        val gson = Gson()
        val configType = object : TypeToken<Config>() {}.type
        return gson.fromJson(jsonString, configType)
    }

    fun setEnableAudio(enableAudio: Boolean) {
        DemoContext.enableAudio = enableAudio
    }

    fun isEnableAudio(): Boolean {
        return enableAudio
    }

    fun setEnableVideo(enableVideo: Boolean) {
        DemoContext.enableVideo = enableVideo
    }

    fun isEnableVideo(): Boolean {
        return enableVideo
    }

    fun setEnableRtm(enableRtm: Boolean) {
        DemoContext.enableRtm = enableRtm
    }

    fun isEnableRtm(): Boolean {
        return enableRtm
    }

    fun setEnableStereoTest(enableStereoTest: Boolean) {
        DemoContext.enableStereoTest = enableStereoTest
    }

    fun isEnableStereoTest(): Boolean {
        return enableStereoTest
    }

    fun setEnableSaveAudio(enableSaveAudio: Boolean) {
        DemoContext.enableSaveAudio = enableSaveAudio
    }

    fun isEnableSaveAudio(): Boolean {
        return enableSaveAudio
    }

    fun setEnableTestRtcDataStreamMessage(enableTestRtcDataStreamMessage: Boolean) {
        DemoContext.enableTestRtcDataStreamMessage = enableTestRtcDataStreamMessage
    }

    fun isEnableTestRtcDataStreamMessage(): Boolean {
        return enableTestRtcDataStreamMessage
    }

    fun setEnableTestRtmMessage(enableTestRtmMessage: Boolean) {
        DemoContext.enableTestRtmMessage = enableTestRtmMessage
    }

    fun isEnableTestRtmMessage(): Boolean {
        return enableTestRtmMessage
    }

    fun setEnableTestRtcAudioMetadata(enableTestRtcAudioMetadata: Boolean) {
        DemoContext.enableTestRtcAudioMetadata = enableTestRtcAudioMetadata
    }

    fun isEnableTestRtcAudioMetadata(): Boolean {
        return enableTestRtcAudioMetadata
    }

}