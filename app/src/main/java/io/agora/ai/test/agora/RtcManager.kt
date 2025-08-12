package io.agora.ai.test.agora

import android.content.Context
import io.agora.ai.test.constants.ExamplesConstants
import io.agora.ai.test.utils.LogUtils
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

object RtcManager {
    private const val TAG = "${ExamplesConstants.TAG}-RtcManager"
    private const val PLAYBACK_AUDIO_FRAME_TIMEOUT = 200 // ms
    private var mRtcEngine: RtcEngine? = null
    private var mCustomAudioTrackId = -1

    private val mExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val mSingleThreadScope = CoroutineScope(mExecutor)

    private var mRtcConnection: RtcConnection? = null

    private var mEventCallback: IRtcEventCallback? = null

    private var mAudioFileName = ""

    private var playbackFinishJob: Job? = null

    private fun schedulePlaybackFinishTimer() {
        playbackFinishJob?.cancel()
        playbackFinishJob = mSingleThreadScope.launch {
            delay(PLAYBACK_AUDIO_FRAME_TIMEOUT.toLong())
            io.agora.ai.test.utils.LogUtils.d(
                TAG,
                "onPlaybackAudioFrame finished due to timeout ${PLAYBACK_AUDIO_FRAME_TIMEOUT}ms"
            )
            mEventCallback?.onPlaybackAudioFrameFinished()
        }
    }

    private var rtcEventHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
            LogUtils.d(
                TAG,
                "onJoinChannelSuccess channel:$channel uid:$uid elapsed:$elapsed"
            )

            if (mRtcConnection != null) {
                mRtcConnection = RtcConnection(channel, uid)
            }

            mEventCallback?.onJoinChannelSuccess(channel, uid, elapsed)
        }

        override fun onLeaveChannel(stats: RtcStats) {
            LogUtils.d(TAG, "onLeaveChannel")
            mEventCallback?.onLeaveChannelSuccess()
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            LogUtils.d(TAG, "onUserJoined uid:$uid elapsed:$elapsed")
            mEventCallback?.onUserJoined(uid, elapsed)
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            LogUtils.d(TAG, "onUserOffline uid:$uid reason:$reason")
            mEventCallback?.onUserOffline(uid, reason)
        }
    }

    fun initialize(context: Context, appId: String, eventCallback: IRtcEventCallback): Int {
        LogUtils.d(TAG, "RtcManager initialize")
        if (mRtcEngine != null) {
            LogUtils.i(TAG, "initialize error: already initialized")
            return Constants.ERR_OK
        }

        mEventCallback = eventCallback

        mAudioFileName = context.externalCacheDir?.absolutePath + "/audio_"
        LogUtils.d(TAG, "mAudioFileName:$mAudioFileName")

        try {
            LogUtils.d(TAG, "RtcEngine version:" + RtcEngine.getSdkVersion())
            val rtcEngineConfig = RtcEngineConfig()
            rtcEngineConfig.mContext = context
            rtcEngineConfig.mAppId = appId
            rtcEngineConfig.mChannelProfile =
                Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            rtcEngineConfig.mEventHandler = rtcEventHandler

            val logConfig = LogConfig()
            logConfig.level = Constants.LOG_LEVEL_INFO
            try {
                val agoraLogFile = java.io.File(LogUtils.getLogDir(), "agora_rtc.log")
                logConfig.filePath = agoraLogFile.absolutePath
                logConfig.fileSizeInKB = 20480
                LogUtils.d(TAG, "Agora log file: ${agoraLogFile.absolutePath}")
            } catch (_: Throwable) {
            }
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


            LogUtils.d(
                TAG, "initRtcEngine success"
            )
        } catch (e: Exception) {
            e.printStackTrace()
            LogUtils.e(
                TAG, "initRtcEngine error:" + e.message
            )
            return -Constants.ERR_FAILED
        }
        return Constants.ERR_OK
    }

    private fun setAgoraRtcParameters(parameters: String) {
        LogUtils.d(TAG, "setAgoraRtcParameters parameters:$parameters")
        if (mRtcEngine == null) {
            LogUtils.e(TAG, "setAgoraRtcParameters error: not initialized")
            return
        }
        val ret = mRtcEngine?.setParameters(parameters) ?: -1
        if (ret != 0) {
            LogUtils.e(
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
        LogUtils.d(
            TAG,
            "createCustomAudioTrack mCustomAudioTrackId:$mCustomAudioTrackId"
        )
    }

    private fun registerAudioFrame(
    ) {
        if (mRtcEngine == null) {
            LogUtils.e(TAG, "registerAudioFrame error: not initialized")
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
                LogUtils.d(
                    TAG,
                    "onPlaybackAudioFrameBeforeMixing channelId:$channelId uid:$uid renderTimeMs:$renderTimeMs rtpTimestamp:$rtpTimestamp presentationMs:$presentationMs"
                )
                saveAudioFrame(buffer)
                // Reset timeout timer on each frame received; callback finish if no new frame received within PLAYBACK_AUDIO_FRAME_TIMEOUT
                schedulePlaybackFinishTimer()
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

        // must get data synchronously and process data asynchronously
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
        LogUtils.d(
            TAG,
            "joinChannelEx channelId:$channelId roleType:$roleType"
        )
        if (mRtcEngine == null) {
            LogUtils.e(TAG, "joinChannelEx error: not initialized")
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


            LogUtils.d(
                TAG, "joinChannelEx ret:$ret"
            )
        } catch (e: Exception) {
            e.printStackTrace()
            LogUtils.e(
                TAG, "joinChannelEx error:" + e.message
            )
            return -Constants.ERR_FAILED
        }

        return Constants.ERR_OK
    }

    fun leaveChannel(): Int {
        LogUtils.d(TAG, "leaveChannel")
        if (mRtcEngine == null) {
            LogUtils.e(TAG, "leaveChannel error: not initialized")
            return -Constants.ERR_NOT_INITIALIZED
        }
        try {
            playbackFinishJob?.cancel()
            playbackFinishJob = null
            mRtcEngine?.leaveChannel()
        } catch (e: Exception) {
            e.printStackTrace()
            LogUtils.e(
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
    ): Int {
        if (mRtcEngine == null) {
            LogUtils.e(
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
            LogUtils.e(TAG, "pushExternalAudioFrame error: $ret")
            -Constants.ERR_FAILED
        }
    }

    fun destroy() {
        if (mCustomAudioTrackId != -1) {
            mRtcEngine?.destroyCustomAudioTrack(mCustomAudioTrackId)
            mCustomAudioTrackId = -1
        }
        playbackFinishJob?.cancel()
        playbackFinishJob = null
        RtcEngine.destroy()
        mRtcEngine = null
        mRtcConnection = null
        mEventCallback = null
        mAudioFileName = ""

        mExecutor.close()

        LogUtils.d(TAG, "rtc destroy")
    }


}