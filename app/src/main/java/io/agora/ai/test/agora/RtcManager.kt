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
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

object RtcManager {
    private const val TAG = "${ExamplesConstants.TAG}-RtcManager"

    private var mRtcEngine: RtcEngine? = null
    private var mCustomAudioTrackId = -1

    // 替换原本的线程池，使用专用的写线程和阻塞队列
    private val mAudioDataQueue = LinkedBlockingQueue<ByteArray>()
    private var mWriteThread: Thread? = null
    private @Volatile var mIsWriting = false

    private var mRtcConnection: RtcConnection? = null

    private var mRtcEventCallback: IRtcEventCallback? = null

    private var mAudioFileName = ""

    private var mAudioFrameIndex = 0
    private var mFrameStartTime = 0L

    private var mStreamMessageId = -1

    private val mAudioFrameCallback = object : AudioFrameManager.ICallback {

        override fun onSessionStart(sessionId: Int) {
            LogUtils.i(TAG, "onSessionStart sessionId:$sessionId")
        }

        override fun onSessionEnd(sessionId: Int) {
            LogUtils.i(TAG, "onSessionEnd sessionId:$sessionId")

            mRtcEventCallback?.onPlaybackAudioFrameFinished()
            mAudioFrameIndex = 0
            mFrameStartTime = 0L
        }

        override fun onSessionInterrupt(sessionId: Int) {
            LogUtils.i(TAG, "onSessionInterrupt sessionId:$sessionId")
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

            // Metadata registration no longer required. Kept for compatibility: ignore.
        }

        override fun onUserMuteAudio(uid: Int, muted: Boolean) {
            super.onUserMuteAudio(uid, muted)
            LogUtils.d(TAG, "onUserMuteAudio uid:$uid muted:$muted")
        }

        override fun onRemoteAudioStateChanged(uid: Int, state: Int, reason: Int, elapsed: Int) {
            super.onRemoteAudioStateChanged(uid, state, reason, elapsed)
            LogUtils.d(
                TAG,
                "onRemoteAudioStateChanged uid:$uid state:$state reason:$reason elapsed:$elapsed"
            )
        }
    }

    fun initialize(context: Context, appId: String, eventCallback: IRtcEventCallback): Int {
        LogUtils.d(TAG, "RtcManager initialize")
        if (mRtcEngine != null) {
            LogUtils.i(TAG, "initialize error: already initialized")
            return Constants.ERR_OK
        }

        // 启动音频文件写入线程
        startAudioWriteThread()

        mRtcEventCallback = eventCallback

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

            if (ExamplesConstants.ENABLE_STEREO_AUDIO) {
                mRtcEngine?.setAudioProfile(Constants.AUDIO_PROFILE_MUSIC_HIGH_QUALITY_STEREO)
            } else {
                mRtcEngine?.setAudioProfile(Constants.AUDIO_PROFILE_DEFAULT)
            }
            mRtcEngine?.setAudioScenario(Constants.AUDIO_SCENARIO_AI_CLIENT)

            setAgoraRtcParameters("{\"rtc.enable_debug_log\":true}")


            //burst mode
            setAgoraRtcParameters("{\"che.audio.get_burst_mode\":true}")
            //setAgoraRtcParameters("{\"che.audio.neteq.max_wait_first_decode_ms\":40}")
            setAgoraRtcParameters("{\"che.audio.neteq.max_wait_ms\":500}")
            setAgoraRtcParameters("{\"rtc.remote_frame_expire_threshold\":30000}")
            setAgoraRtcParameters("{\"rtc.vos_list\": [\"58.211.16.105:4070\"]}")

            setAgoraRtcParameters("{\"che.audio.frame_dump\":{\"location\":\"all\",\"action\":\"start\",\"max_size_bytes\":\"100000000\",\"uuid\":\"123456789\", \"duration\": \"150000\"}}")
            if (ExamplesConstants.ENABLE_AUDIO_TEST) {
                setAgoraRtcParameters("{\"rtc.vos_list\":[\"58.211.16.105:4063\"]}")
            }

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
                    LogUtils.i(TAG, "onPlaybackAudioFrameBeforeMixing empty data")
                    return true
                }
                AudioFrameManager.processAudioFrame(byteArray, presentationMs)
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
        if (mIsWriting) {
            mAudioDataQueue.offer(buffer)
        }
    }

    private fun startAudioWriteThread() {
        if (mIsWriting) return
        mIsWriting = true
        mWriteThread = Thread {
            LogUtils.d(TAG, "Audio write thread started")
            while (mIsWriting) {
                try {
                    // 使用 poll 带超时，避免一直阻塞无法退出，或者 take() 阻塞直到有数据
                    // take() 是阻塞的，poll(timeout) 是带超时的
                    // 这里使用 take() 比较简单，因为停止时我们可以 interrupt 或者 offer 一个特殊标记
                    // 为了简单起见，使用 take() 配合 catch InterruptedException
                    val data = mAudioDataQueue.take()
                    if (data.isNotEmpty() && mAudioFileName.isNotEmpty()) {
                        saveFile(mAudioFileName, data)
                    }
                } catch (e: InterruptedException) {
                    LogUtils.d(TAG, "Audio write thread interrupted")
                    break
                } catch (e: Exception) {
                    LogUtils.e(TAG, "Audio write error: ${e.message}")
                }
            }
            LogUtils.d(TAG, "Audio write thread stopped")
        }.apply {
            name = "AudioWriterThread"
            start()
        }
    }

    private fun stopAudioWriteThread() {
        mIsWriting = false
        mWriteThread?.interrupt()
        mWriteThread = null
        mAudioDataQueue.clear()
    }

    private fun saveFile(fileName: String, byteArray: ByteArray) {
        try {
            val file = File(fileName)
            FileOutputStream(file, true).use { outputStream ->
                outputStream.write(byteArray)
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "saveFile error: ${e.message}")
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

            mAudioFileName =
                LogUtils.getLogDir().path + "/audio_" + channelId + "_" + userId + "_" + System.currentTimeMillis() + ".pcm"
            LogUtils.d(TAG, "save audio file: $mAudioFileName")

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
            mStreamMessageId = -1
            mCustomAudioTrackId = -1
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
        val timestamp = AudioFrameManager.generatePts(data, sampleRate, channels, isSessionEnd)
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

    fun sendAudioMetadataEx(data: ByteArray) {
        (mRtcEngine as RtcEngineEx).sendAudioMetadataEx(data, mRtcConnection)
    }

    fun sendStreamMessage(message: ByteArray): Int {
        if (-1 == mStreamMessageId) {
            mStreamMessageId = (mRtcEngine as RtcEngineEx).createDataStreamEx(
                false,
                false,
                mRtcConnection
            )
            LogUtils.d(TAG, "createDataStreamEx mStreamMessageId:$mStreamMessageId")
            if (-1 == mStreamMessageId) {
                return -Constants.ERR_FAILED
            }
        }
        val ret = (mRtcEngine as RtcEngineEx).sendStreamMessageEx(
            mStreamMessageId,
            message,
            mRtcConnection
        )
        LogUtils.d(TAG, "sendStreamMessage ret:$ret message:${String(message)}")
        return ret
    }

    /**
     * 获取当前保存的音频文件路径
     * @return 音频文件路径，如果未设置则返回空字符串
     */
    fun getAudioFileName(): String {
        return mAudioFileName
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

        // 停止音频写入线程
        stopAudioWriteThread()
        LogUtils.d(TAG, "RtcManager: Destroyed audio write thread")

        LogUtils.d(TAG, "rtc destroy")
    }
}