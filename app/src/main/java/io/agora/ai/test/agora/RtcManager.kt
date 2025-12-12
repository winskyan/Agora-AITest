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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

object RtcManager {
    private const val TAG = "${ExamplesConstants.TAG}-RtcManager"

    // 音频帧数据封装类（内部类）
    private data class AudioFrameData(
        val buffer: ByteArray,
        val channelId: String,
        val presentationMs: Long
    )

    private var mRtcEngine: RtcEngine? = null
    private var mCustomAudioTrackId = -1

    // 双 Channel 架构：实时接收 + 播放缓冲
    private var mAudioDataChannel: Channel<AudioFrameData>? = null  // 实时接收音频帧
    private var mPlaybackBufferChannel: Channel<ByteArray>? = null  // 播放缓冲队列
    private var mProcessJob: Job? = null  // 实时处理协程
    private var mPlaybackJob: Job? = null  // 定时播放协程
    private val mWriteScopeJob = SupervisorJob()  // 协程作用域的 Job，用于统一取消
    private val mWriteScope = CoroutineScope(Dispatchers.IO + mWriteScopeJob)

    // 播放配置参数
    private const val SAVE_AUDIO_CACHE_FRAME_COUNT = 20  // 缓存5帧后开始播放
    private const val PLAYBACK_AUDIO_SAMPLE_RATE = 16000  // 播放采样率16kHz
    private const val PLAYBACK_AUDIO_CHANNELS = 1  // 播放声道
    private const val PLAYBACK_AUDIO_ONE_FRAME_TIME_MS = 10L  // 每帧10ms间隔
    private const val PLAYBACK_AUDIO_FRAME_SIZE =
        (PLAYBACK_AUDIO_SAMPLE_RATE * PLAYBACK_AUDIO_CHANNELS * 2 * PLAYBACK_AUDIO_ONE_FRAME_TIME_MS / 1000).toInt()  // 每帧大小

    @Volatile
    private var mIsPlaybackStarted = false  // 播放是否已开始（多线程访问）

    @Volatile
    private var mShouldFillSilence = true  // 是否应该填充静音帧（Session End 后设为 false）

    @Volatile
    private var mAudioFileName = ""  // 音频文件名（多线程访问）

    private var mRtcConnection: RtcConnection? = null
    private var mRtcEventCallback: IRtcEventCallback? = null

    private var mFrameStartTime = 0L
    private var playbackFrameCount = 0L
    private var channelSendAudioCount = 0L
    private var channelGetAudioCount = 0L

    private var mStreamMessageId = -1


    private val mAudioFrameCallback = object : AudioFrameManager.ICallback {

        override fun onSessionStart(sessionId: Int) {
            LogUtils.i(TAG, "onSessionStart sessionId:$sessionId")
            // Session 开始时，允许填充静音帧
            mShouldFillSilence = true
        }

        override fun onSessionEnd(sessionId: Int) {
            LogUtils.i(TAG, "onSessionEnd sessionId:$sessionId")

            // Session 结束时，标记不再填充静音帧
            // 但播放协程会继续运行，直到缓冲区数据播放完毕
            mShouldFillSilence = false
            LogUtils.d(
                TAG,
                "onSessionEnd: marked to stop filling silence, waiting for buffer drain"
            )

            mRtcEventCallback?.onPlaybackAudioFrameFinished()
        }

        override fun onSessionInterrupt(sessionId: Int) {
            LogUtils.i(TAG, "onSessionInterrupt sessionId:$sessionId")
            // Session 中断时，也标记不再填充静音帧
            mShouldFillSilence = false
            LogUtils.d(TAG, "onSessionInterrupt: marked to stop filling silence")
        }
    }

    private var mRtcEventHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
            LogUtils.d(
                TAG,
                "onJoinChannelSuccess channel:$channel uid:$uid elapsed:$elapsed"
            )

            if (mRtcConnection == null) {
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

        // 启动音频文件写入协程
        startAudioWriteCoroutine()

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
            //setAgoraRtcParameters("{\"rtc.vos_list\": [\"58.211.16.105:4070\"]}")

            //setAgoraRtcParameters("{\"che.audio.frame_dump\":{\"location\":\"all\",\"action\":\"start\",\"max_size_bytes\":\"100000000\",\"uuid\":\"123456789\", \"duration\": \"150000\"}}")
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
            PLAYBACK_AUDIO_SAMPLE_RATE, PLAYBACK_AUDIO_CHANNELS/*,
            (PLAYBACK_AUDIO_SAMPLE_RATE / 1000 * PLAYBACK_AUDIO_ONE_FRAME_TIME_MS).toInt()*/
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
                    } playbackFrameCount:$playbackFrameCount dataSize:${byteArray.size}"
                )
                playbackFrameCount++

                if ((playbackFrameCount % 50).toInt() == 0) {
                    LogUtils.d(
                        TAG,
                        "onPlaybackAudioFrameBeforeMixing playbackFrameCount:$playbackFrameCount per 50 frame time:${System.currentTimeMillis() - mFrameStartTime}ms"
                    )
                    mFrameStartTime = System.currentTimeMillis()
                }

                if (byteArray.isEmpty()) {
                    LogUtils.i(TAG, "onPlaybackAudioFrameBeforeMixing empty data")
                    return true
                }

                val sendResult = mAudioDataChannel?.trySend(
                    AudioFrameData(
                        byteArray,
                        channelId ?: "",
                        presentationMs
                    )
                )

                if (sendResult?.isFailure == true) {
                    LogUtils.w(
                        TAG,
                        "[frame fail]onPlaybackAudioFrameBeforeMixing trySend Failed to send audio frame to channel: ${sendResult.exceptionOrNull()?.message} pts:$presentationMs"
                    )
                    return true
                }
                channelSendAudioCount++
                LogUtils.d(
                    TAG,
                    "[frame]onPlaybackAudioFrameBeforeMixing send audio channelSendAudioCount:$channelSendAudioCount playbackFrameCount:${playbackFrameCount} pts:$presentationMs"
                )
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

    /**
     * 启动音频处理协程（双协程架构）
     * 1. 实时处理协程：接收音频帧 -> processAudioFrame -> 放入播放缓冲
     * 2. 定时播放协程：定时从缓冲读取 -> 保存到文件（模拟播放）
     */
    private fun startAudioWriteCoroutine() {
        LogUtils.d(TAG, "startAudioWriteCoroutine")
        channelSendAudioCount = 0L
        channelGetAudioCount = 0L
        mIsPlaybackStarted = false

        if (mAudioDataChannel != null) return

        // 创建双 Channel
        mAudioDataChannel = Channel(Channel.UNLIMITED)  // 实时接收，无限容量
        mPlaybackBufferChannel = Channel(Channel.UNLIMITED)  // 播放缓冲，无限容量

        // 协程1：实时处理音频帧
        mProcessJob = mWriteScope.launch {
            LogUtils.d(TAG, "Audio process coroutine started")
            try {
                for (frameData in mAudioDataChannel!!) {
                    if (frameData.buffer.isNotEmpty()) {
                        channelGetAudioCount++
                        LogUtils.d(
                            TAG,
                            "[process] Received frame #$channelGetAudioCount, pts:${frameData.presentationMs}"
                        )

                        // 实时处理音频帧（不阻塞）
                        AudioFrameManager.processAudioFrame(
                            frameData.buffer,
                            frameData.presentationMs
                        )

                        // 放入播放缓冲队列
                        mPlaybackBufferChannel?.trySend(frameData.buffer)

                        // 检查是否达到缓存阈值，启动播放协程
                        if (!mIsPlaybackStarted && channelGetAudioCount >= SAVE_AUDIO_CACHE_FRAME_COUNT) {
                            mIsPlaybackStarted = true
                            startPlaybackCoroutine()
                        }
                    }
                }
            } catch (e: Exception) {
                LogUtils.d(TAG, "Audio process coroutine cancelled: ${e.message}")
            }
            LogUtils.d(TAG, "Audio process coroutine stopped")
        }
    }

    /**
     * 启动定时播放协程
     * 每隔 PLAYBACK_AUDIO_ONE_FRAME_TIME_MS 从缓冲读取一帧并保存
     * - Session 进行中（mShouldFillSilence = true）：缓冲为空则填充静音帧
     * - Session 结束后（mShouldFillSilence = false）：缓冲为空则停止播放
     */
    private fun startPlaybackCoroutine() {
        LogUtils.d(
            TAG,
            "startPlaybackCoroutine: buffer reached $SAVE_AUDIO_CACHE_FRAME_COUNT frames"
        )

        mPlaybackJob = mWriteScope.launch {
            var localPlaybackFrameCount = 0L
            val silentFrame = ByteArray(PLAYBACK_AUDIO_FRAME_SIZE)  // 静音帧（全0）

            try {
                while (true) {
                    delay(PLAYBACK_AUDIO_ONE_FRAME_TIME_MS)

                    // 尝试从缓冲读取一帧（非阻塞）
                    val frame = mPlaybackBufferChannel?.tryReceive()?.getOrNull()

                    if (frame != null) {
                        // 有数据，保存真实音频帧
                        localPlaybackFrameCount++

                        if (mAudioFileName.isNotEmpty()) {
                            saveFile(mAudioFileName, frame)
                        }
                        LogUtils.d(
                            TAG,
                            "[playback] Saved real frame #$localPlaybackFrameCount, size:${frame.size}"
                        )
                    } else {
                        // 缓冲为空
                        if (mShouldFillSilence) {
                            // Session 进行中，填充静音帧（无限制）
                            localPlaybackFrameCount++
                            if (mAudioFileName.isNotEmpty()) {
                                saveFile(mAudioFileName, silentFrame)
                            }
                            LogUtils.w(
                                TAG,
                                "[playback] Buffer empty! Saved silent frame #$localPlaybackFrameCount"
                            )
                        } else {
                            // Session 已结束且缓冲为空，停止播放
                            LogUtils.i(
                                TAG,
                                "[playback] Session ended and buffer drained, stopping gracefully"
                            )
                            break  // 优雅停止
                        }
                    }
                }
            } catch (e: Exception) {
                LogUtils.d(TAG, "Playback coroutine cancelled: ${e.message}")
            }

            // 协程结束时重置状态
            mIsPlaybackStarted = false
            mShouldFillSilence = true
            LogUtils.i(
                TAG,
                "Playback coroutine stopped gracefully, total frames: $localPlaybackFrameCount"
            )
        }
    }

    private fun stopAudioWriteCoroutine() {
        LogUtils.d(TAG, "stopAudioWriteCoroutine")

        // 先标记停止填充静音，让播放协程自然结束
        mShouldFillSilence = false

        // 关闭 Channel（会导致协程的 for 循环退出）
        mAudioDataChannel?.close()
        mAudioDataChannel = null
        mPlaybackBufferChannel?.close()
        mPlaybackBufferChannel = null

        // 取消协程（强制停止，防止泄漏）
        mProcessJob?.cancel()
        mProcessJob = null
        mPlaybackJob?.cancel()
        mPlaybackJob = null

        mIsPlaybackStarted = false
        channelSendAudioCount = 0L
        channelGetAudioCount = 0L
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
            resetData()

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

        // 停止音频写入协程
        stopAudioWriteCoroutine()

        // 取消整个协程作用域，确保所有协程都被停止
        mWriteScopeJob.cancel()
        LogUtils.d(TAG, "RtcManager: Destroyed audio write coroutine and cancelled scope")

        LogUtils.d(TAG, "rtc destroy")
    }

    private fun resetData() {
        mFrameStartTime = 0L
        playbackFrameCount = 0L
        channelSendAudioCount = 0L
        channelGetAudioCount = 0L

        mIsPlaybackStarted = false
        mShouldFillSilence = true
    }
}