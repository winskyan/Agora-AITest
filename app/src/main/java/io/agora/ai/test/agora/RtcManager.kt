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

/**
 * RTC 管理器
 * 负责 Agora RTC 引擎的初始化、频道管理、音频帧处理和协程管理
 */
object RtcManager {
    private const val TAG = "${ExamplesConstants.TAG}-RtcManager"

    // ========== 音频播放配置常量 ==========
    private const val SAVE_AUDIO_CACHE_FRAME_COUNT = 20  // 缓存帧数阈值
    private const val PLAYBACK_AUDIO_SAMPLE_RATE = 16000  // 播放采样率 16kHz
    private const val PLAYBACK_AUDIO_CHANNELS = 1  // 播放声道数
    private const val PLAYBACK_AUDIO_ONE_FRAME_TIME_MS = 10L  // 每帧时长 10ms
    private const val PLAYBACK_AUDIO_FRAME_SIZE =
        (PLAYBACK_AUDIO_SAMPLE_RATE * PLAYBACK_AUDIO_CHANNELS * 2 * PLAYBACK_AUDIO_ONE_FRAME_TIME_MS / 1000).toInt()

    // ========== 内部数据类 ==========
    /**
     * 音频帧数据封装类
     * @param buffer 音频数据
     * @param channelId 频道 ID
     * @param presentationMs PTS 时间戳
     */
    private data class AudioFrameData(
        val buffer: ByteArray,
        val channelId: String,
        val presentationMs: Long
    )

    // ========== RTC 引擎相关 ==========
    private var mRtcEngine: RtcEngine? = null
    private var mRtcConnection: RtcConnection? = null
    private var mRtcEventCallback: IRtcEventCallback? = null
    private var mCustomAudioTrackId = -1
    private var mStreamMessageId = -1

    // ========== 协程和 Channel 相关 ==========
    private val mWriteScopeJob = SupervisorJob()
    private val mWriteScope = CoroutineScope(Dispatchers.IO + mWriteScopeJob)
    private var mAudioDataChannel: Channel<AudioFrameData>? = null  // 实时接收音频帧
    private var mPlaybackBufferChannel: Channel<ByteArray>? = null  // 播放缓冲队列
    private var mProcessJob: Job? = null  // 实时处理协程
    private var mPlaybackJob: Job? = null  // 定时播放协程

    // ========== 状态变量 ==========
    @Volatile
    private var mIsPlaybackStarted = false  // 播放是否已开始

    @Volatile
    private var mShouldFillSilence = true  // 是否填充静音帧

    @Volatile
    private var mAudioFileName = ""  // 音频文件名

    // ========== 统计变量 ==========
    private var mFrameStartTime = 0L
    private var playbackFrameCount = 0L
    private var channelSendAudioCount = 0L
    private var channelGetAudioCount = 0L

    // ========== 回调接口 ==========
    /**
     * AudioFrameManager 回调
     * 处理 Session 生命周期事件
     */
    private val mAudioFrameCallback = object : AudioFrameManager.ICallback {
        override fun onSessionStart(sessionId: Int) {
            LogUtils.i(TAG, "onSessionStart sessionId:$sessionId")
            mShouldFillSilence = true
        }

        override fun onSessionEnd(sessionId: Int) {
            LogUtils.i(TAG, "onSessionEnd sessionId:$sessionId")
            mShouldFillSilence = false
            LogUtils.d(
                TAG,
                "onSessionEnd: marked to stop filling silence, waiting for buffer drain"
            )
            mRtcEventCallback?.onPlaybackAudioFrameFinished()
        }

        override fun onSessionInterrupt(sessionId: Int) {
            LogUtils.i(TAG, "onSessionInterrupt sessionId:$sessionId")
            mShouldFillSilence = false
            LogUtils.d(TAG, "onSessionInterrupt: marked to stop filling silence")
        }
    }

    /**
     * RTC 引擎事件回调
     */
    private val mRtcEventHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
            LogUtils.d(TAG, "onJoinChannelSuccess channel:$channel uid:$uid elapsed:$elapsed")
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

    // ========== 公共 API ==========
    /**
     * 初始化 RTC 引擎
     * @param context Android Context
     * @param appId Agora App ID
     * @param eventCallback 事件回调接口
     * @return 错误码，0 表示成功
     */
    fun initialize(context: Context, appId: String, eventCallback: IRtcEventCallback): Int {
        LogUtils.d(TAG, "RtcManager initialize")
        if (mRtcEngine != null) {
            LogUtils.i(TAG, "initialize error: already initialized")
            return Constants.ERR_OK
        }

        startAudioWriteCoroutine()
        mRtcEventCallback = eventCallback

        try {
            LogUtils.d(TAG, "RtcEngine version:" + RtcEngine.getSdkVersion())

            // 配置 RTC 引擎
            val rtcEngineConfig = RtcEngineConfig()
            rtcEngineConfig.mContext = context
            rtcEngineConfig.mAppId = appId
            rtcEngineConfig.mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            rtcEngineConfig.mEventHandler = mRtcEventHandler

            // 配置日志
            val logConfig = LogConfig()
            logConfig.level = Constants.LOG_LEVEL_INFO
            try {
                val agoraLogFile = File(LogUtils.getLogDir(), "agora_rtc.log")
                logConfig.filePath = agoraLogFile.absolutePath
                logConfig.fileSizeInKB = 20480
                LogUtils.d(TAG, "Agora log file: ${agoraLogFile.absolutePath}")
            } catch (_: Throwable) {
            }
            rtcEngineConfig.mLogConfig = logConfig

            // 创建引擎
            mRtcEngine = RtcEngine.create(rtcEngineConfig)

            // 设置音频配置
            if (ExamplesConstants.ENABLE_STEREO_AUDIO) {
                mRtcEngine?.setAudioProfile(Constants.AUDIO_PROFILE_MUSIC_HIGH_QUALITY_STEREO)
            } else {
                mRtcEngine?.setAudioProfile(Constants.AUDIO_PROFILE_DEFAULT)
            }
            mRtcEngine?.setAudioScenario(Constants.AUDIO_SCENARIO_AI_CLIENT)
            mRtcEngine?.setDefaultAudioRoutetoSpeakerphone(true)

            // 设置 RTC 参数
            setAgoraRtcParameters("{\"rtc.enable_debug_log\":true}")
            setAgoraRtcParameters("{\"che.audio.get_burst_mode\":true}")
            //setAgoraRtcParameters("{\"che.audio.neteq.max_wait_first_decode_ms\":40}")
            setAgoraRtcParameters("{\"che.audio.neteq.max_wait_ms\":500}")
            setAgoraRtcParameters("{\"rtc.remote_frame_expire_threshold\":30000}")
            //setAgoraRtcParameters("{\"rtc.vos_list\": [\"58.211.16.105:4070\"]}")
            //setAgoraRtcParameters("{\"che.audio.frame_dump\":{\"location\":\"all\",\"action\":\"start\",\"max_size_bytes\":\"100000000\",\"uuid\":\"123456789\", \"duration\": \"150000\"}}")

            if (ExamplesConstants.ENABLE_AUDIO_TEST) {
                setAgoraRtcParameters("{\"rtc.vos_list\":[\"58.211.16.105:4063\"]}")
            }

            AudioFrameManager.init(mAudioFrameCallback)
            LogUtils.d(TAG, "initRtcEngine success")
        } catch (e: Exception) {
            e.printStackTrace()
            LogUtils.e(TAG, "initRtcEngine error:" + e.message)
            return -Constants.ERR_FAILED
        }
        return Constants.ERR_OK
    }

    /**
     * 加入频道
     * @param channelId 频道 ID
     * @param userId 用户 ID
     * @param rtcToken RTC Token
     * @param roleType 角色类型
     * @return 错误码，0 表示成功
     */
    fun joinChannelEx(channelId: String, userId: Int, rtcToken: String, roleType: Int): Int {
        LogUtils.d(TAG, "joinChannelEx channelId:$channelId roleType:$roleType")
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

            LogUtils.d(TAG, "joinChannelEx ret:$ret")
        } catch (e: Exception) {
            e.printStackTrace()
            LogUtils.e(TAG, "joinChannelEx error:" + e.message)
            return -Constants.ERR_FAILED
        }

        return Constants.ERR_OK
    }

    /**
     * 离开频道
     * 会等待所有缓冲的音频数据播放完毕后再完全停止
     * @return 错误码，0 表示成功
     */
    fun leaveChannel(): Int {
        LogUtils.d(TAG, "leaveChannel")
        if (mRtcEngine == null) {
            LogUtils.e(TAG, "leaveChannel error: not initialized")
            return -Constants.ERR_NOT_INITIALIZED
        }

        try {
            mRtcEngine?.leaveChannel()
            stopAudioWriteCoroutineGracefully()
            AudioFrameManager.release()
            mStreamMessageId = -1
            mCustomAudioTrackId = -1
        } catch (e: Exception) {
            e.printStackTrace()
            LogUtils.e(TAG, "leaveChannel error:" + e.message)
            return -Constants.ERR_FAILED
        }

        return Constants.ERR_OK
    }

    /**
     * 推送外部音频帧
     * @param data 音频数据
     * @param sampleRate 采样率
     * @param channels 声道数
     * @param isSessionEnd 是否为 Session 结束标记
     * @return 错误码，0 表示成功
     */
    fun pushExternalAudioFrame(
        data: ByteArray,
        sampleRate: Int,
        channels: Int,
        isSessionEnd: Boolean
    ): Int {
        if (mRtcEngine == null) {
            LogUtils.e(TAG, "pushExternalAudioFrame error: not initialized")
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

    /**
     * 发送音频元数据
     * @param data 元数据
     */
    fun sendAudioMetadataEx(data: ByteArray) {
        (mRtcEngine as RtcEngineEx).sendAudioMetadataEx(data, mRtcConnection)
    }

    /**
     * 发送流消息
     * @param message 消息内容
     * @return 错误码
     */
    fun sendStreamMessage(message: ByteArray): Int {
        if (mStreamMessageId == -1) {
            mStreamMessageId =
                (mRtcEngine as RtcEngineEx).createDataStreamEx(false, false, mRtcConnection)
            LogUtils.d(TAG, "createDataStreamEx mStreamMessageId:$mStreamMessageId")
            if (mStreamMessageId == -1) {
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
     * @return 音频文件路径
     */
    fun getAudioFileName(): String {
        return mAudioFileName
    }

    /**
     * 销毁 RTC 引擎
     * 立即停止所有操作并释放资源
     */
    fun destroy() {
        LogUtils.d(TAG, "RtcManager destroy")

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

        stopAudioWriteCoroutine()
        mWriteScopeJob.cancel()

        LogUtils.d(TAG, "RtcManager destroyed")
    }

    // ========== 私有方法：RTC 配置 ==========
    /**
     * 设置 Agora RTC 参数
     * @param parameters JSON 格式的参数字符串
     */
    private fun setAgoraRtcParameters(parameters: String) {
        LogUtils.d(TAG, "setAgoraRtcParameters parameters:$parameters")
        if (mRtcEngine == null) {
            LogUtils.e(TAG, "setAgoraRtcParameters error: not initialized")
            return
        }

        val ret = mRtcEngine?.setParameters(parameters) ?: -1
        if (ret != 0) {
            LogUtils.e(TAG, "setAgoraRtcParameters parameters:$parameters error: $ret")
        }
    }

    /**
     * 初始化自定义音频轨道
     */
    private fun initCustomAudioTracker() {
        val audioTrackConfig = AudioTrackConfig()
        audioTrackConfig.enableLocalPlayback = false
        audioTrackConfig.enableAudioProcessing = false

        mCustomAudioTrackId = mRtcEngine?.createCustomAudioTrack(
            Constants.AudioTrackType.AUDIO_TRACK_DIRECT,
            audioTrackConfig
        ) ?: 0

        LogUtils.d(TAG, "createCustomAudioTrack mCustomAudioTrackId:$mCustomAudioTrackId")
    }

    /**
     * 注册音频帧观察器
     */
    private fun registerAudioFrame() {
        if (mRtcEngine == null) {
            LogUtils.e(TAG, "registerAudioFrame error: not initialized")
            return
        }

        mRtcEngine?.setPlaybackAudioFrameBeforeMixingParameters(
            PLAYBACK_AUDIO_SAMPLE_RATE,
            PLAYBACK_AUDIO_CHANNELS
        )

        mRtcEngine?.registerAudioFrameObserver(object : IAudioFrameObserver {
            override fun onRecordAudioFrame(
                channelId: String?, type: Int, samplesPerChannel: Int,
                bytesPerSample: Int, channels: Int, samplesPerSec: Int,
                buffer: ByteBuffer?, renderTimeMs: Long, avsync_type: Int
            ): Boolean = true

            override fun onPlaybackAudioFrame(
                channelId: String?, type: Int, samplesPerChannel: Int,
                bytesPerSample: Int, channels: Int, samplesPerSec: Int,
                buffer: ByteBuffer?, renderTimeMs: Long, avsync_type: Int
            ): Boolean = true

            override fun onMixedAudioFrame(
                channelId: String?, type: Int, samplesPerChannel: Int,
                bytesPerSample: Int, channels: Int, samplesPerSec: Int,
                buffer: ByteBuffer?, renderTimeMs: Long, avsync_type: Int
            ): Boolean = true

            override fun onEarMonitoringAudioFrame(
                type: Int, samplesPerChannel: Int, bytesPerSample: Int,
                channels: Int, samplesPerSec: Int, buffer: ByteBuffer?,
                renderTimeMs: Long, avsync_type: Int
            ): Boolean = true

            override fun onPlaybackAudioFrameBeforeMixing(
                channelId: String?, uid: Int, type: Int, samplesPerChannel: Int,
                bytesPerSample: Int, channels: Int, samplesPerSec: Int,
                buffer: ByteBuffer?, renderTimeMs: Long, avsync_type: Int,
                rtpTimestamp: Int, presentationMs: Long
            ): Boolean {
                // 同步获取数据
                buffer?.rewind()
                val byteArray = ByteArray(buffer?.remaining() ?: 0)
                buffer?.get(byteArray)

                if (mFrameStartTime == 0L) {
                    mFrameStartTime = System.currentTimeMillis()
                }

                playbackFrameCount++

                LogUtils.d(
                    TAG,
                    "onPlaybackAudioFrameBeforeMixing channelId:$channelId uid:$uid " +
                            "renderTimeMs:$renderTimeMs rtpTimestamp:$rtpTimestamp " +
                            "presentationMs:$presentationMs ${
                                String.format(
                                    "0x%016X",
                                    presentationMs
                                )
                            } " +
                            "playbackFrameCount:$playbackFrameCount dataSize:${byteArray.size}"
                )


                if ((playbackFrameCount % 50).toInt() == 0) {
                    LogUtils.d(
                        TAG,
                        "onPlaybackAudioFrameBeforeMixing playbackFrameCount:$playbackFrameCount " +
                                "per 50 frame time:${System.currentTimeMillis() - mFrameStartTime}ms"
                    )
                    mFrameStartTime = System.currentTimeMillis()
                }

                if (byteArray.isEmpty()) {
                    LogUtils.i(TAG, "onPlaybackAudioFrameBeforeMixing empty data")
                    return true
                }

                // 异步发送到 Channel
                val sendResult = mAudioDataChannel?.trySend(
                    AudioFrameData(byteArray, channelId ?: "", presentationMs)
                )

                if (sendResult?.isFailure == true) {
                    LogUtils.w(
                        TAG,
                        "[frame fail] trySend failed: ${sendResult.exceptionOrNull()?.message} pts:$presentationMs"
                    )
                    return true
                }

                channelSendAudioCount++
                LogUtils.d(
                    TAG,
                    "[frame] send audio channelSendAudioCount:$channelSendAudioCount " +
                            "playbackFrameCount:$playbackFrameCount pts:$presentationMs"
                )

                return true
            }

            override fun getObservedAudioFramePosition(): Int = 0
            override fun getRecordAudioParams(): AudioParams = AudioParams(0, 0, 0, 0)
            override fun getPlaybackAudioParams(): AudioParams = AudioParams(0, 0, 0, 0)
            override fun getMixedAudioParams(): AudioParams = AudioParams(0, 0, 0, 0)
            override fun getEarMonitoringAudioParams(): AudioParams = AudioParams(0, 0, 0, 0)
        })
    }

    // ========== 私有方法：协程管理 ==========
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
        mAudioDataChannel = Channel(Channel.UNLIMITED)
        mPlaybackBufferChannel = Channel(Channel.UNLIMITED)

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

                        // 实时处理音频帧
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
            val silentFrame = ByteArray(PLAYBACK_AUDIO_FRAME_SIZE)

            try {
                while (true) {
                    delay(PLAYBACK_AUDIO_ONE_FRAME_TIME_MS)

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
                            // Session 进行中，填充静音帧
                            localPlaybackFrameCount++
                            if (mAudioFileName.isNotEmpty()) {
                                saveFile(mAudioFileName, silentFrame)
                            }
                            LogUtils.w(
                                TAG,
                                "[playback] Buffer empty! Saved silent frame #$localPlaybackFrameCount"
                            )
                        } else {
                            // Session 结束，停止播放
                            break
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

    /**
     * 优雅地停止音频写入协程（leaveChannel 时调用）
     * 1. 关闭实时接收 Channel，停止接收新音频帧
     * 2. 等待处理协程完成
     * 3. 标记停止填充静音帧
     * 4. 等待播放协程自然结束（缓冲区清空）
     * 5. 清理所有资源
     */
    private fun stopAudioWriteCoroutineGracefully() {
        LogUtils.d(TAG, "stopAudioWriteCoroutineGracefully: start")

        // 步骤1: 关闭实时接收 Channel
        mAudioDataChannel?.close()
        LogUtils.d(TAG, "stopAudioWriteCoroutineGracefully: closed audio data channel")

        // 步骤2-5: 在协程中等待完成并清理
        mWriteScope.launch {
            try {
                // 等待处理协程完成
                mProcessJob?.join()
                LogUtils.d(TAG, "stopAudioWriteCoroutineGracefully: process job completed")
            } catch (e: Exception) {
                LogUtils.e(
                    TAG,
                    "stopAudioWriteCoroutineGracefully: process job error: ${e.message}"
                )
            }

            // 标记停止填充静音帧
            mShouldFillSilence = false
            LogUtils.d(TAG, "stopAudioWriteCoroutineGracefully: marked to stop filling silence")

            try {
                // 等待播放协程自然结束
                mPlaybackJob?.join()
                LogUtils.d(TAG, "stopAudioWriteCoroutineGracefully: playback job completed")
            } catch (e: Exception) {
                LogUtils.e(
                    TAG,
                    "stopAudioWriteCoroutineGracefully: playback job error: ${e.message}"
                )
            }

            // 清理所有资源
            mAudioDataChannel = null
            mPlaybackBufferChannel?.close()
            mPlaybackBufferChannel = null
            mProcessJob = null
            mPlaybackJob = null
            mIsPlaybackStarted = false
            channelSendAudioCount = 0L
            channelGetAudioCount = 0L

            LogUtils.i(TAG, "stopAudioWriteCoroutineGracefully: completed, all audio saved")
        }
    }

    /**
     * 强制停止音频写入协程（destroy 时调用）
     * 立即取消所有协程，不等待缓冲区清空
     */
    private fun stopAudioWriteCoroutine() {
        LogUtils.d(TAG, "stopAudioWriteCoroutine: force stop")

        mShouldFillSilence = false

        mAudioDataChannel?.close()
        mAudioDataChannel = null
        mPlaybackBufferChannel?.close()
        mPlaybackBufferChannel = null

        mProcessJob?.cancel()
        mProcessJob = null
        mPlaybackJob?.cancel()
        mPlaybackJob = null

        mIsPlaybackStarted = false
        channelSendAudioCount = 0L
        channelGetAudioCount = 0L
    }

    // ========== 私有方法：工具函数 ==========
    /**
     * 保存音频数据到文件
     * @param fileName 文件路径
     * @param byteArray 音频数据
     */
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

    /**
     * 重置统计数据
     */
    private fun resetData() {
        mFrameStartTime = 0L
        playbackFrameCount = 0L
        channelSendAudioCount = 0L
        channelGetAudioCount = 0L
        mIsPlaybackStarted = false
        mShouldFillSilence = true
    }
}
