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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
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

    private var mRtcEventCallback: IRtcEventCallback? = null

    private var mAudioFileName = ""

    private var mAudioFrameIndex = 0
    private var mFrameStartTime = 0L
    private var mPreFrameTime = 0L

    private var mStreamId = -1

    private var mHandlePts = false

    private var mIncrementalModeFirstSuccess = false
    private var mIncrementalModeSecondSuccess = false
    private var mIncrementalModeTotalFrameSuccess = false
    private var mHandleSessionEnd = false

    private val mAudioFrameCallback = object : AudioFrameManager.ICallback {
        override fun onSentenceEnd(
            sessionId: Int,
            sentenceId: Int,
            chunkId: Int,
            isSessionEnd: Boolean
        ) {
            super.onSentenceEnd(sessionId, sentenceId, chunkId, isSessionEnd)
            LogUtils.i(
                TAG,
                "onSentenceEnd sessionId:$sessionId sentenceId:$sentenceId chunkId:$chunkId isSessionEnd:$isSessionEnd"
            )
            if (mHandleSessionEnd && isSessionEnd) {
                mRtcEventCallback?.onPlaybackAudioFrameFinished()
                CoroutineScope(mExecutor).launch {
                    mHandlePts = true
                    sendStreamMessage(ExamplesConstants.TEST_PASS.toByteArray())
                }
            }
        }
    }

    private var mRtcEventHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
            LogUtils.d(
                TAG,
                "onJoinChannelSuccess channel:$channel uid:$uid elapsed:$elapsed"
            )

            if (mRtcConnection != null) {
                mRtcConnection = RtcConnection(channel, uid)
            }

            mRtcEventCallback?.onJoinChannelSuccess(channel, uid, elapsed)
        }

        override fun onLeaveChannel(stats: RtcStats) {
            LogUtils.d(TAG, "onLeaveChannel")
            mRtcEventCallback?.onLeaveChannelSuccess()
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            LogUtils.d(TAG, "onUserJoined uid:$uid elapsed:$elapsed")
            mRtcEventCallback?.onUserJoined(uid, elapsed)
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            LogUtils.d(TAG, "onUserOffline uid:$uid reason:$reason")
            mRtcEventCallback?.onUserOffline(uid, reason)
        }

        override fun onStreamMessage(uid: Int, streamId: Int, data: ByteArray?) {
            super.onStreamMessage(uid, streamId, data)
            LogUtils.d(
                TAG,
                "onStreamMessage uid:$uid streamId:$streamId data:${String(data ?: ByteArray(0))}"
            )

            val message = data?.let { String(it) } ?: ""
            if (message == ExamplesConstants.TEST_TASK_SEND_PCM_AI_WITH_PTS || message == ExamplesConstants.TEST_TASK_RECEIVE_PCM_AI_WITH_PTS
                || message == ExamplesConstants.TEST_TASK_SEND_PCM_INCREMENTAL_MODE
            ) {
                CoroutineScope(mExecutor).launch {
                    mHandleSessionEnd = false
                    mAudioFrameIndex = 0
                    mHandlePts = true
                    mIncrementalModeFirstSuccess = false
                    mIncrementalModeSecondSuccess = false
                    mIncrementalModeTotalFrameSuccess = false

                    if (message == ExamplesConstants.TEST_TASK_SEND_PCM_AI_WITH_PTS || message == ExamplesConstants.TEST_TASK_RECEIVE_PCM_AI_WITH_PTS) {
                        mHandleSessionEnd = true
                    }

                    sendStreamMessage(ExamplesConstants.TEST_START.toByteArray())

                    if (message == ExamplesConstants.TEST_TASK_RECEIVE_PCM_AI_WITH_PTS) {
                        delay(1000)
                        mRtcEventCallback?.onPushExternalAudioFrameStart()
                    }
                }
            } else if (message == ExamplesConstants.TEST_TASK_SEND_PCM_INCREMENTAL_MODE_FINISHED) {
                CoroutineScope(mExecutor).launch {
                    mIncrementalModeTotalFrameSuccess = mAudioFrameIndex < 2800
                    LogUtils.d(
                        TAG,
                        "onStreamMessage: TEST_TASK_SEND_PCM_INCREMENTAL_MODE_FINISHED  delay 1s and send TEST_PASS mIncrementalModeFirstSuccess:$mIncrementalModeFirstSuccess mIncrementalModeSecondSuccess:$mIncrementalModeSecondSuccess mIncrementalModeTotalFrameSuccess:$mIncrementalModeTotalFrameSuccess"
                    )
                    delay(1000)
                    if (mIncrementalModeFirstSuccess && mIncrementalModeSecondSuccess && mIncrementalModeTotalFrameSuccess) {
                        sendStreamMessage(ExamplesConstants.TEST_PASS.toByteArray())
                    } else {
                        LogUtils.e(
                            TAG,
                            "onStreamMessage: TEST_TASK_SEND_PCM_INCREMENTAL_MODE_FINISHED  fail delay 1s and send TEST_PASS mIncrementalModeFirstSuccess:$mIncrementalModeFirstSuccess mIncrementalModeSecondSuccess:$mIncrementalModeSecondSuccess mIncrementalModeTotalFrameSuccess:$mIncrementalModeTotalFrameSuccess"
                        )
                    }
                }
            }
        }
    }

    fun initialize(context: Context, appId: String, eventCallback: IRtcEventCallback): Int {
        LogUtils.d(TAG, "RtcManager initialize")
        if (mRtcEngine != null) {
            LogUtils.i(TAG, "initialize error: already initialized")
            return Constants.ERR_OK
        }

        mRtcEventCallback = eventCallback

        mAudioFileName = context.externalCacheDir?.absolutePath + "/audio_"
        LogUtils.d(TAG, "mAudioFileName:$mAudioFileName")

        try {
            LogUtils.d(TAG, "RtcEngine version:" + RtcEngine.getSdkVersion())
            val rtcEngineConfig = RtcEngineConfig()
            rtcEngineConfig.mContext = context
            rtcEngineConfig.mAppId = appId
            rtcEngineConfig.mChannelProfile =
                Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            rtcEngineConfig.mEventHandler = mRtcEventHandler

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
            //setAgoraRtcParameters("{\"che.audio.neteq.max_wait_first_decode_ms\":40}")
            setAgoraRtcParameters("{\"che.audio.neteq.max_wait_ms\":500}")
            setAgoraRtcParameters("{\"rtc.remote_frame_expire_threshold\":30000}")
//            setAgoraRtcParameters("{\"che.audio.frame_dump\":{\"location\":\"all\",\"action\":\"start\",\"max_size_bytes\":\"100000000\",\"uuid\":\"123456789\", \"duration\": \"150000\"}}")

            mRtcEngine?.setDefaultAudioRoutetoSpeakerphone(true)


            LogUtils.d(
                TAG, "initRtcEngine success"
            )

            AudioFrameManager.init(mAudioFrameCallback)
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

                // must get data synchronously and process data asynchronously
                buffer?.rewind()
                val byteArray = ByteArray(buffer?.remaining() ?: 0)
                buffer?.get(byteArray)

                if (mFrameStartTime == 0L) {
                    mFrameStartTime = System.currentTimeMillis()
                }

                if (mPreFrameTime != 0L) {
                    val now = System.currentTimeMillis()
                    val diff = now - mPreFrameTime
                    if (diff < 5) {
                        mIncrementalModeFirstSuccess = true
                    } else if (diff >= 10) {
                        mIncrementalModeSecondSuccess = true
                    }
                }

                mPreFrameTime = System.currentTimeMillis()

                LogUtils.d(
                    TAG,
                    "onPlaybackAudioFrameBeforeMixing channelId:$channelId uid:$uid renderTimeMs:$renderTimeMs rtpTimestamp:$rtpTimestamp presentationMs:$presentationMs ${
                        String.format(
                            "0x%016X",
                            presentationMs
                        )
                    } index:$mAudioFrameIndex dataSize:${byteArray.size}"
                )
                mAudioFrameIndex++

                if (mAudioFrameIndex % 50 == 0) {
                    LogUtils.d(
                        TAG,
                        "onPlaybackAudioFrameBeforeMixing index:$mAudioFrameIndex per 50 frame time:${System.currentTimeMillis() - mFrameStartTime}ms"
                    )
                    mFrameStartTime = System.currentTimeMillis()
                }

                if (byteArray.isEmpty()) {
                    return true
                }
                if (mHandlePts) {
                    AudioFrameManager.processAudioFrame(byteArray, presentationMs)
                }
                saveAudioFrame(byteArray)
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

    private fun saveAudioFrame(buffer: ByteArray) {
        mSingleThreadScope.launch {
            saveFile(mAudioFileName, buffer)
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
            mAudioFrameIndex = 0
            mFrameStartTime = 0L
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
                mRtcEventHandler
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
            mRtcEngine?.leaveChannel()
            AudioFrameManager.release()
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
        sampleRate: Int,
        channels: Int,
        isSessionEnd: Boolean
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
        val timestamp = if (mHandlePts) AudioFrameManager.generatePts(
            data,
            sampleRate,
            channels,
            isSessionEnd
        ) else 0L
        val ret = mRtcEngine?.pushExternalAudioFrame(
            data,
            timestamp,
            sampleRate,
            channels,
            Constants.BytesPerSample.TWO_BYTES_PER_SAMPLE,
            mCustomAudioTrackId
        )

        if (isSessionEnd) {
            mHandlePts = false
        }
        return if (ret == 0) {
            Constants.ERR_OK
        } else {
            LogUtils.e(TAG, "pushExternalAudioFrame error: $ret")
            -Constants.ERR_FAILED
        }
    }

    fun sendAudioMetadataEx(data: ByteArray) {
        (mRtcEngine as RtcEngineEx).sendAudioMetadataEx(data, mRtcConnection)
    }

    fun sendStreamMessage(messageByte: ByteArray) {
        if (mRtcEngine == null) {
            LogUtils.e(TAG, "sendStreamMessage error: not initialized")
            return
        }
        if (mRtcConnection == null) {
            LogUtils.e(TAG, "sendStreamMessage error: not joined channel")
            return
        }
        if (mStreamId == -1) {
            mStreamId = (mRtcEngine as RtcEngineEx).createDataStreamEx(false, false, mRtcConnection)
        }
        val ret =
            (mRtcEngine as RtcEngineEx).sendStreamMessageEx(mStreamId, messageByte, mRtcConnection)
        LogUtils.d(
            TAG,
            "sendStreamMessage streamId:$mStreamId ret:$ret message:${String(messageByte)}"
        )
    }

    fun destroy() {
        if (mCustomAudioTrackId != -1) {
            mRtcEngine?.destroyCustomAudioTrack(mCustomAudioTrackId)
            mCustomAudioTrackId = -1
        }
        AudioFrameManager.release()
        RtcEngine.destroy()
        mRtcEngine = null
        mRtcConnection = null
        mRtcEventCallback = null
        mAudioFileName = ""
        mStreamId = -1

        mExecutor.close()

        LogUtils.d(TAG, "rtc destroy")
    }
}