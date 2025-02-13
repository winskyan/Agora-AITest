package io.agora.ai.test.maas.model

import android.content.Context
import io.agora.ai.test.maas.MaaSConstants
import io.agora.ai.test.maas.MaaSEngineEventHandler
import io.agora.rtc2.Constants

class MaaSEngineConfiguration(
    var context: Context?,
    var eventHandler: MaaSEngineEventHandler?,
    var enableConsoleLog: Boolean,
    var enableSaveLogToFile: Boolean,
    var appId: String,
    var userId: Int,
    var rtcToken: String,
    var rtmToken: String,
    var enableMultiTurnShortTermMemory: Boolean,
    var userName: String,
    var agentVoiceName: String,
    var input: SceneMode,
    var output: SceneMode,
    var vadConfiguration: VadConfiguration,
    var noiseEnvironment: MaaSConstants.NoiseEnvironment = MaaSConstants.NoiseEnvironment.NOISE,
    var speechRecognitionCompletenessLevel: MaaSConstants.SpeechRecognitionCompletenessLevel = MaaSConstants.SpeechRecognitionCompletenessLevel.NORMAL,
    var params: List<String>,
    var audioProfile: Int,
    var audioScenario: Int,
    var enableRtm: Boolean = false
) {
    constructor() : this(
        context = null,
        eventHandler = null,
        enableConsoleLog = false,
        enableSaveLogToFile = false,
        appId = "",
        rtcToken = "",
        rtmToken = "",
        userId = 0,
        enableMultiTurnShortTermMemory = false,
        userName = "",
        input = SceneMode("zh-CN", 16000, 1, 16),
        output = SceneMode("zh-CN", 16000, 1, 16),
        agentVoiceName = "",
        vadConfiguration = VadConfiguration(500),
        params = emptyList<String>(),
        audioProfile = Constants.AUDIO_PROFILE_DEFAULT,
        audioScenario = Constants.AUDIO_SCENARIO_CHORUS,
        enableRtm = false
    ) {

    }

    override fun toString(): String {
        return "MaaSEngineConfiguration(context=$context, eventHandler=$eventHandler, enableConsoleLog=$enableConsoleLog, enableSaveLogToFile=$enableSaveLogToFile, appId='$appId', userId=$userId, rtcToken='$rtcToken', rtmToken='$rtmToken', enableMultiTurnShortTermMemory=$enableMultiTurnShortTermMemory, userName='$userName', agentVoiceName='$agentVoiceName', input=$input, output=$output, vadConfiguration=$vadConfiguration, noiseEnvironment=$noiseEnvironment, speechRecognitionCompletenessLevel=$speechRecognitionCompletenessLevel, params=$params, audioProfile=$audioProfile, audioScenario=$audioScenario , enableRtm=$enableRtm)"
    }
}