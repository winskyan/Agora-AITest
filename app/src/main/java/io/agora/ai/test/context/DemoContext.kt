package io.agora.ai.test.context

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.agora.ai.test.constants.Constants
import io.agora.ai.test.maas.MaaSConstants
import io.agora.ai.test.model.Config
import io.agora.ai.test.utils.MMKVUtils

object DemoContext {
    var params: Array<String>
        get() = MMKVUtils.getStringSet(Constants.MMKV_KEY_PARAMS, emptySet())?.toTypedArray()
            ?: emptyArray()
        set(value) = MMKVUtils.putStringSet(Constants.MMKV_KEY_PARAMS, value.toSet())

    var audioProfile: Int
        get() = MMKVUtils.getInt(Constants.MMKV_KEY_AUDIO_PROFILE, 0)
        set(value) = MMKVUtils.putInt(Constants.MMKV_KEY_AUDIO_PROFILE, value)

    var audioScenario: Int
        get() = MMKVUtils.getInt(Constants.MMKV_KEY_AUDIO_SCENARIO, 7)
        set(value) = MMKVUtils.putInt(Constants.MMKV_KEY_AUDIO_SCENARIO, value)

    var enableAudio: Boolean
        get() = MMKVUtils.getBoolean(Constants.MMKV_KEY_ENABLE_AUDIO, true)
        set(value) = MMKVUtils.putBoolean(Constants.MMKV_KEY_ENABLE_AUDIO, value)

    var enableVideo: Boolean
        get() = MMKVUtils.getBoolean(Constants.MMKV_KEY_ENABLE_VIDEO, false)
        set(value) = MMKVUtils.putBoolean(Constants.MMKV_KEY_ENABLE_VIDEO, value)

    var enableRtm: Boolean
        get() = MMKVUtils.getBoolean(Constants.MMKV_KEY_ENABLE_RTM, true)
        set(value) = MMKVUtils.putBoolean(Constants.MMKV_KEY_ENABLE_RTM, value)

    var enableStereoTest: Boolean
        get() = MMKVUtils.getBoolean(Constants.MMKV_KEY_ENABLE_STEREO_TEST, false)
        set(value) = MMKVUtils.putBoolean(Constants.MMKV_KEY_ENABLE_STEREO_TEST, value)

    var enableSaveAudio: Boolean
        get() = MMKVUtils.getBoolean(Constants.MMKV_KEY_ENABLE_SAVE_AUDIO, false)
        set(value) = MMKVUtils.putBoolean(Constants.MMKV_KEY_ENABLE_SAVE_AUDIO, value)

    var enableTestRtcDataStreamMessage: Boolean
        get() = MMKVUtils.getBoolean(Constants.MMKV_KEY_ENABLE_TEST_RTC_DATA_STREAM_MESSAGE, true)
        set(value) = MMKVUtils.putBoolean(
            Constants.MMKV_KEY_ENABLE_TEST_RTC_DATA_STREAM_MESSAGE,
            value
        )

    var enableTestRtmMessage: Boolean
        get() = MMKVUtils.getBoolean(Constants.MMKV_KEY_ENABLE_TEST_RTM_MESSAGE, true)
        set(value) = MMKVUtils.putBoolean(Constants.MMKV_KEY_ENABLE_TEST_RTM_MESSAGE, value)

    var enableTestRtcAudioMetadata: Boolean
        get() = MMKVUtils.getBoolean(Constants.MMKV_KEY_ENABLE_TEST_RTC_AUDIO_METADATA, false)
        set(value) = MMKVUtils.putBoolean(Constants.MMKV_KEY_ENABLE_TEST_RTC_AUDIO_METADATA, value)

    var clientRoleType: Int
        get() = MMKVUtils.getInt(
            Constants.MMKV_KEY_CLIENT_ROLE_TYPE,
            MaaSConstants.CLIENT_ROLE_BROADCASTER
        )
        set(value) = MMKVUtils.putInt(Constants.MMKV_KEY_CLIENT_ROLE_TYPE, value)

    var appId: String
        get() = MMKVUtils.getString(Constants.MMKV_KEY_APP_ID, "")
        set(value) = MMKVUtils.putString(Constants.MMKV_KEY_APP_ID, value)

    var rtcToken: String
        get() = MMKVUtils.getString(Constants.MMKV_KEY_RTC_TOKEN, "")
        set(value) = MMKVUtils.putString(Constants.MMKV_KEY_RTC_TOKEN, value)

    var appCertificate: String
        get() = MMKVUtils.getString(Constants.MMKV_KEY_APP_CERTIFICATE, "")
        set(value) = MMKVUtils.putString(Constants.MMKV_KEY_APP_CERTIFICATE, value)


    var enablePushExternalVideo: Boolean
        get() = MMKVUtils.getBoolean(Constants.MMKV_KEY_ENABLE_PUSH_EXTERNAL_VIDEO, false)
        set(value) = MMKVUtils.putBoolean(Constants.MMKV_KEY_ENABLE_PUSH_EXTERNAL_VIDEO, value)

    var fps: Float
        get() = MMKVUtils.getFloat(Constants.MMKV_KEY_FPS, 15.0f).toFloat()
        set(value) = MMKVUtils.putFloat(Constants.MMKV_KEY_FPS, value)

    var enableEncryption: Boolean
        get() = MMKVUtils.getBoolean(Constants.MMKV_KEY_ENABLE_ENCRYPTION, false)
        set(value) = MMKVUtils.putBoolean(Constants.MMKV_KEY_ENABLE_ENCRYPTION, value)

    var enablePullAudioFrame: Boolean
        get() = MMKVUtils.getBoolean(Constants.MMKV_KEY_ENABLE_PULL_AUDIO_FRAME, false)
        set(value) = MMKVUtils.putBoolean(Constants.MMKV_KEY_ENABLE_PULL_AUDIO_FRAME, value)

    var enableSendVideoMetadata: Boolean
        get() = MMKVUtils.getBoolean(Constants.MMKV_KEY_ENABLE_SEND_VIDEO_METADATA, false)
        set(value) = MMKVUtils.putBoolean(Constants.MMKV_KEY_ENABLE_SEND_VIDEO_METADATA, value)

    var enableLeaveChannelWithoutDestroy: Boolean
        get() = MMKVUtils.getBoolean(Constants.MMKV_KEY_ENABLE_LEAVE_CHANNEL_WITHOUT_DESTROY, false)
        set(value) = MMKVUtils.putBoolean(Constants.MMKV_KEY_ENABLE_LEAVE_CHANNEL_WITHOUT_DESTROY, value)

    var enableCustomDirectAudioTracker: Boolean
        get() = MMKVUtils.getBoolean(Constants.MMKV_KEY_ENABLE_CUSTOM_DIRECT_AUDIO_TRACKER, false)
        set(value) = MMKVUtils.putBoolean(Constants.MMKV_KEY_ENABLE_CUSTOM_DIRECT_AUDIO_TRACKER, value)

    var appIdSelectionMode: Int
        get() = MMKVUtils.getInt(
            Constants.MMKV_KEY_APP_ID_SELECTION_MODE,
            Constants.APP_ID_MODE_SELECT
        )
        set(value) = MMKVUtils.putInt(Constants.MMKV_KEY_APP_ID_SELECTION_MODE, value)

    fun parseConfigJson(jsonString: String): Config {
        val gson = Gson()
        val configType = object : TypeToken<Config>() {}.type
        return gson.fromJson(jsonString, configType)
    }

}
