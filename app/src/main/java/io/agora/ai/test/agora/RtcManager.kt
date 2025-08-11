package io.agora.ai.test.agora

import android.content.Context
import android.util.Log
import io.agora.ai.test.constants.ExamplesConstants
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IAudioFrameObserver
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcConnection
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.RtcEngineConfig.LogConfig
import io.agora.rtc2.RtcEngineEx
import io.agora.rtc2.audio.AudioParams
import io.agora.rtc2.audio.AudioTrackConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

object RtcManager {
    private const val TAG = "${ExamplesConstants.TAG}-RtcManager"
    private var mRtcEngine: RtcEngine? = null
    private var mCustomAudioTrackId = -1

    private val mExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val mSingleThreadScope = CoroutineScope(mExecutor)

    private var mRtcConnection: RtcConnection? = null

    private var mEventCallback: IRtcEventCallback? = null

    private var mAudioFileName = ""

    private var rtcEventHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
            Log.d(
                TAG,
                "onJoinChannelSuccess channel:$channel uid:$uid elapsed:$elapsed"
            )

            if (mRtcConnection != null) {
                mRtcConnection = RtcConnection(channel, uid)
            }

            mEventCallback?.onJoinChannelSuccess(channel, uid, elapsed)
        }

        override fun onLeaveChannel(stats: RtcStats) {
            Log.d(TAG, "onLeaveChannel")
            mEventCallback?.onLeaveChannelSuccess()
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            Log.d(TAG, "onUserJoined uid:$uid elapsed:$elapsed")
            mEventCallback?.onUserJoined(uid, elapsed)
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            Log.d(TAG, "onUserOffline uid:$uid reason:$reason")
            mEventCallback?.onUserOffline(uid, reason)
        }
    }

    fun initialize(context: Context, appId: String, eventCallback: IRtcEventCallback): Int {
        Log.d(TAG, "RtcManager initialize")
        if (mRtcEngine != null) {
            Log.i(TAG, "initialize error: already initialized")
            return Constants.ERR_OK
        }

        mEventCallback = eventCallback

        mAudioFileName = context.externalCacheDir?.absolutePath + "/audio_"
        Log.d(TAG, "mAudioFileName:$mAudioFileName")

        try {
            Log.d(TAG, "RtcEngine version:" + RtcEngine.getSdkVersion())
            val rtcEngineConfig = RtcEngineConfig()
            rtcEngineConfig.mContext = context
            rtcEngineConfig.mAppId = appId
            rtcEngineConfig.mChannelProfile =
                Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            rtcEngineConfig.mEventHandler = rtcEventHandler

            val logConfig = LogConfig()
            logConfig.level = Constants.LOG_LEVEL_INFO
            rtcEngineConfig.mLogConfig = logConfig

            mRtcEngine = RtcEngine.create(rtcEngineConfig)

            mRtcEngine?.setAudioProfile(Constants.AUDIO_PROFILE_DEFAULT)
            mRtcEngine?.setAudioScenario(Constants.AUDIO_SCENARIO_AI_CLIENT)

            setAgoraRtcParameters("{\"rtc.enable_debug_log\":true}")
            setAgoraRtcParameters("{\"che.audio.get_burst_mode\":true}")
            setAgoraRtcParameters("{\"che.audio.neteq.max_wait_first_decode_ms\":0}")
            setAgoraRtcParameters("{\"che.audio.neteq.max_wait_ms\":0}")
//            setAgoraRtcParameters("{\"che.audio.frame_dump\":{\"location\":\"all\",\"action\":\"start\",\"max_size_bytes\":\"100000000\",\"uuid\":\"123456789\", \"duration\": \"150000\"}}")

            mRtcEngine?.setDefaultAudioRoutetoSpeakerphone(true)


            Log.d(
                TAG, "initRtcEngine success"
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(
                TAG, "initRtcEngine error:" + e.message
            )
            return -Constants.ERR_FAILED
        }
        return Constants.ERR_OK
    }

    private fun setAgoraRtcParameters(parameters: String) {
        Log.d(TAG, "setAgoraRtcParameters parameters:$parameters")
        if (mRtcEngine == null) {
            Log.e(TAG, "setAgoraRtcParameters error: not initialized")
            return
        }
        val ret = mRtcEngine?.setParameters(parameters) ?: -1
        if (ret != 0) {
            Log.e(
                TAG,
                "setAgoraRtcParameters parameters:${parameters} error: $ret"
            )
        }
    }

    private fun initCustomAudioTracker() {
        val audioTrackConfig = AudioTrackConfig()
        audioTrackConfig.enableLocalPlayback = false
        audioTrackConfig.enableAudioProcessing = false
        mCustomAudioTrackId = mRtcEngine?.createCustomAudioTrack(
            Constants.AudioTrackType.AUDIO_TRACK_DIRECT,
            audioTrackConfig
        ) ?: 0
        Log.d(
            TAG,
            "createCustomAudioTrack mCustomAudioTrackId:$mCustomAudioTrackId"
        )
    }

    private fun registerAudioFrame(
    ) {
        if (mRtcEngine == null) {
            Log.e(TAG, "registerAudioFrame error: not initialized")
            return
        }

        mRtcEngine?.setPlaybackAudioFrameBeforeMixingParameters(
            16000,
            1
        )

        mRtcEngine?.registerAudioFrameObserver(object : IAudioFrameObserver {
            override fun onRecordAudioFrame(
                channelId: String?,
                type: Int,
                samplesPerChannel: Int,
                bytesPerSample: Int,
                channels: Int,
                samplesPerSec: Int,
                buffer: ByteBuffer?,
                renderTimeMs: Long,
                avsync_type: Int
            ): Boolean {
                return true
            }

            override fun onPlaybackAudioFrame(
                channelId: String?,
                type: Int,
                samplesPerChannel: Int,
                bytesPerSample: Int,
                channels: Int,
                samplesPerSec: Int,
                buffer: ByteBuffer?,
                renderTimeMs: Long,
                avsync_type: Int
            ): Boolean {
                return true
            }

            override fun onMixedAudioFrame(
                channelId: String?,
                type: Int,
                samplesPerChannel: Int,
                bytesPerSample: Int,
                channels: Int,
                samplesPerSec: Int,
                buffer: ByteBuffer?,
                renderTimeMs: Long,
                avsync_type: Int
            ): Boolean {
                return true
            }

            override fun onEarMonitoringAudioFrame(
                type: Int,
                samplesPerChannel: Int,
                bytesPerSample: Int,
                channels: Int,
                samplesPerSec: Int,
                buffer: ByteBuffer?,
                renderTimeMs: Long,
                avsync_type: Int
            ): Boolean {
                return true
            }

            override fun onPlaybackAudioFrameBeforeMixing(
                channelId: String?,
                uid: Int,
                type: Int,
                samplesPerChannel: Int,
                bytesPerSample: Int,
                channels: Int,
                samplesPerSec: Int,
                buffer: ByteBuffer?,
                renderTimeMs: Long,
                avsync_type: Int,
                rtpTimestamp: Int,
                presentationMs: Long
            ): Boolean {
                Log.d(
                    TAG,
                    "onPlaybackAudioFrameBeforeMixing channelId:$channelId uid:$uid"
                )
                saveAudioFrame(buffer)
                return true
            }

            override fun getObservedAudioFramePosition(): Int {
                return 0
            }

            override fun getRecordAudioParams(): AudioParams {
                return AudioParams(0, 0, 0, 0)
            }

            override fun getPlaybackAudioParams(): AudioParams {
                return AudioParams(0, 0, 0, 0)
            }

            override fun getMixedAudioParams(): AudioParams {
                return AudioParams(0, 0, 0, 0)
            }

            override fun getEarMonitoringAudioParams(): AudioParams {
                return AudioParams(0, 0, 0, 0)
            }
        })
    }

    private fun saveAudioFrame(buffer: ByteBuffer?) {
        if (buffer == null) return

        buffer.rewind()
        val byteArray = ByteArray(buffer.remaining())
        buffer.get(byteArray)


        mSingleThreadScope.launch {
            saveFile(mAudioFileName, byteArray)
        }
    }

    private fun saveFile(fileName: String, byteArray: ByteArray) {
        val file = File(fileName)
        FileOutputStream(file, true).use { outputStream ->
            outputStream.write(byteArray)

        }
    }

    fun joinChannelEx(
        channelId: String,
        userId: Int,
        rtcToken: String,
        roleType: Int
    ): Int {
        Log.d(
            TAG,
            "joinChannelEx channelId:$channelId roleType:$roleType"
        )
        if (mRtcEngine == null) {
            Log.e(TAG, "joinChannelEx error: not initialized")
            return -Constants.ERR_NOT_INITIALIZED
        }
        try {
            registerAudioFrame()
            initCustomAudioTracker()

            mAudioFileName +=
                channelId + "_" + userId + "_" + System.currentTimeMillis() + ".pcm"

            val channelMediaOption = object : ChannelMediaOptions() {
                init {
                    autoSubscribeAudio = true
                    autoSubscribeVideo = false
                    clientRoleType = roleType
                    publishCustomAudioTrack = true
                    publishCustomAudioTrackId = mCustomAudioTrackId
                    publishMicrophoneTrack = false
                    enableAudioRecordingOrPlayout = false
                }
            }
            mRtcConnection = RtcConnection(channelId, userId)
            val ret = (mRtcEngine as RtcEngineEx).joinChannelEx(
                rtcToken,
                mRtcConnection,
                channelMediaOption,
                rtcEventHandler
            )


            Log.d(
                TAG, "joinChannelEx ret:$ret"
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(
                TAG, "joinChannelEx error:" + e.message
            )
            return -Constants.ERR_FAILED
        }

        return Constants.ERR_OK
    }

    fun leaveChannel(): Int {
        Log.d(TAG, "leaveChannel")
        if (mRtcEngine == null) {
            Log.e(TAG, "leaveChannel error: not initialized")
            return -Constants.ERR_NOT_INITIALIZED
        }
        try {
            mRtcEngine?.leaveChannel()
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(
                TAG, "leaveChannel error:" + e.message
            )
            return -Constants.ERR_FAILED
        }
        return Constants.ERR_OK
    }

    fun pushExternalAudioFrame(
        data: ByteArray,
        timestamp: Long,
        sampleRate: Int,
        channels: Int,
        bytesPerSample: Int
    ): Int {
        if (mRtcEngine == null) {
            Log.e(
                TAG,
                "pushExternalVideoFrameByIdInternal error: not initialized"
            )
            return -Constants.ERR_NOT_INITIALIZED
        }
        if (mCustomAudioTrackId == -1) {
            return -Constants.ERR_NOT_INITIALIZED
        }
        val ret = mRtcEngine?.pushExternalAudioFrame(
            data,
            timestamp,
            sampleRate,
            channels,
            Constants.BytesPerSample.TWO_BYTES_PER_SAMPLE,
            mCustomAudioTrackId
        )

        return if (ret == 0) {
            Constants.ERR_OK
        } else {
            Log.e(TAG, "pushExternalAudioFrame error: $ret")
            -Constants.ERR_FAILED
        }
    }

    fun destroy() {
        if (mCustomAudioTrackId != -1) {
            mRtcEngine?.destroyCustomAudioTrack(mCustomAudioTrackId)
            mCustomAudioTrackId = -1
        }
        RtcEngine.destroy()
        mRtcEngine = null
        mRtcConnection = null
        mEventCallback = null
        mAudioFileName = ""

        mExecutor.close()

        Log.d(TAG, "rtc destroy")
    }


}