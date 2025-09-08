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
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object RtcManager {
    private const val TAG = "${ExamplesConstants.TAG}-RtcManager"

    private var mRtcEngine: RtcEngine? = null
    private var mCustomAudioTrackId = -1
    private var mCustomAudioDirectTrackId = -1

    private val mExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val mRecordingExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val mPushExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val mSingleThreadScope = CoroutineScope(mExecutor)

    private var mRtcConnection: RtcConnection? = null

    private var mRtcEventCallback: IRtcEventCallback? = null

    private var mAudioFileName = ""

    private var mAudioFrameIndex = 0
    private var mFrameStartTime = 0L

    private var mDirectChannelId = ""
    private var mRecordingChannelId = ""

    // Playback mixing queue and 50ms ticker
    private val mPlaybackQueueLock = Any()
    private val mPlaybackQueue: ArrayDeque<ByteArray> = ArrayDeque()
    private var mPlaybackQueueHeadOffset = 0
    private var mPlaybackQueuedBytes = 0
    private var mPlaybackSampleRate = 16000
    private var mPlaybackChannels = 1
    private val mPlaybackScheduler = Executors.newSingleThreadScheduledExecutor()
    private var mPlaybackTimerFuture: ScheduledFuture<*>? = null

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
            if (isSessionEnd) {
                mFrameStartTime = 0L
                mRtcEventCallback?.onPlaybackAudioFrameFinished()
                mAudioFileName =
                    LogUtils.getLogDir().path + "/app_audio_" + mRtcConnection?.channelId + "_" + mRtcConnection?.localUid + "_" + System.currentTimeMillis() + ".pcm"
            }
        }
    }

    private var mRtcEventHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
            LogUtils.d(
                TAG,
                "onJoinChannelSuccess channel:$channel uid:$uid elapsed:$elapsed"
            )

            if (channel == mDirectChannelId) {
                if (mRtcConnection != null) {
                    mRtcConnection = RtcConnection(channel, uid)
                }
            } else if (channel == mRecordingChannelId) {
                mRtcEngine?.muteLocalAudioStream(true)
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

            mRtcEngine?.setAudioProfile(Constants.AUDIO_PROFILE_DEFAULT)
            mRtcEngine?.setAudioScenario(Constants.AUDIO_SCENARIO_AI_CLIENT)

            setAgoraRtcParameters("{\"rtc.enable_debug_log\":true}")
            setAgoraRtcParameters("{\"che.audio.get_burst_mode\":true}")
            setAgoraRtcParameters("{\"che.audio.neteq.max_wait_first_decode_ms\":120}")
            setAgoraRtcParameters("{\"che.audio.neteq.max_wait_ms\":150}")
            //setAgoraRtcParameters("{\"che.audio.frame_dump\":{\"location\":\"all\",\"action\":\"start\",\"max_size_bytes\":\"100000000\",\"uuid\":\"123456789\", \"duration\": \"150000\"}}")

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

    private fun initCustomAudioDirectTracker() {
        val audioTrackConfig = AudioTrackConfig()
        audioTrackConfig.enableLocalPlayback = false
        audioTrackConfig.enableAudioProcessing = false
        mCustomAudioDirectTrackId = mRtcEngine?.createCustomAudioTrack(
            Constants.AudioTrackType.AUDIO_TRACK_DIRECT,
            audioTrackConfig
        ) ?: 0
        LogUtils.d(
            TAG,
            "createCustomAudioTrack mCustomAudimCustomAudioDirectTrackIdoTrackId:$mCustomAudioDirectTrackId"
        )
    }

    private fun initCustomAudioTracker() {
        val audioTrackConfig = AudioTrackConfig()
        audioTrackConfig.enableLocalPlayback = true
        audioTrackConfig.enableAudioProcessing = false
        mCustomAudioTrackId = mRtcEngine?.createCustomAudioTrack(
            Constants.AudioTrackType.AUDIO_TRACK_MIXABLE,
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

        mRtcEngine?.setRecordingAudioFrameParameters(
            16000,
            1, Constants.RAW_AUDIO_FRAME_OP_MODE_READ_ONLY, 320
        )

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
//                LogUtils.d(
//                    TAG,
//                    "onRecordAudioFrame channelId:$channelId mRecordingChannelId:$mRecordingChannelId"
//                )
                if (channelId == mRecordingChannelId) {
                    buffer?.rewind()
                    val byteArray = ByteArray(buffer?.remaining() ?: 0)
                    buffer?.get(byteArray)
                    mPushExecutor.asExecutor().execute {
                        pushExternalDirectAudioFrame(byteArray, samplesPerSec, channels, false)
                    }
                }
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
                if (channelId != null && channelId != mDirectChannelId) {
                    return true
                }
                // must get data synchronously and process data asynchronously
                buffer?.rewind()
                val byteArray = ByteArray(buffer?.remaining() ?: 0)
                buffer?.get(byteArray)

                if (mFrameStartTime == 0L) {
                    mFrameStartTime = System.currentTimeMillis()
                    clearPlaybackQueue()
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
                    return true
                }
                // Enqueue data and ensure 50ms ticker is running
                enqueuePlaybackData(byteArray)
                mPlaybackSampleRate = samplesPerSec
                mPlaybackChannels = channels
                ensurePlaybackTicker()
                AudioFrameManager.processAudioFrameForLab(byteArray, presentationMs)
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
            stopPlaybackTicker()
            clearPlaybackQueue()
            mDirectChannelId = channelId
            mAudioFrameIndex = 0
            mFrameStartTime = 0L
            initCustomAudioDirectTracker()

            mAudioFileName =
                LogUtils.getLogDir().path + "/app_audio_" + channelId + "_" + userId + "_" + System.currentTimeMillis() + ".pcm"
            LogUtils.d(TAG, "save audio file: $mAudioFileName")

            val channelMediaOption = object : ChannelMediaOptions() {
                init {
                    autoSubscribeAudio = true
                    autoSubscribeVideo = false
                    clientRoleType = roleType
                    publishCustomAudioTrack = true
                    publishCustomAudioTrackId = mCustomAudioDirectTrackId
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

    fun joinChannel(
        channelId: String,
        userId: Int,
        rtcToken: String,
        roleType: Int
    ): Int {
        LogUtils.d(
            TAG,
            "joinChannel channelId:$channelId userId:$userId roleType:$roleType"
        )

        if (mRtcEngine == null) {
            LogUtils.e(TAG, "joinChannel error: not initialized")
            return -Constants.ERR_NOT_INITIALIZED
        }
        initCustomAudioTracker()
        registerAudioFrame()
        mRecordingChannelId = channelId
        try {
            mRtcEngine?.joinChannel(rtcToken, channelId, userId, object : ChannelMediaOptions() {
                init {
                    autoSubscribeAudio = true
                    autoSubscribeVideo = false
                    clientRoleType = roleType
                    publishCustomAudioTrack = false
                    publishCustomAudioTrackId = mCustomAudioTrackId
                    publishMicrophoneTrack = true
                    enableAudioRecordingOrPlayout = true
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
            LogUtils.e(
                TAG, "joinChannel error:" + e.message
            )
            return -Constants.ERR_FAILED
        }

        return 0
    }

    fun leaveChannel(): Int {
        LogUtils.d(TAG, "leaveChannel")
        if (mRtcEngine == null) {
            LogUtils.e(TAG, "leaveChannel error: not initialized")
            return -Constants.ERR_NOT_INITIALIZED
        }
        try {
            stopPlaybackTicker()
            clearPlaybackQueue()
            mRtcEngine?.leaveChannel()
            if (mRtcConnection != null) {
                (mRtcEngine as RtcEngineEx).leaveChannelEx(mRtcConnection)
            }
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

    fun pushExternalDirectAudioFrame(
        data: ByteArray,
        sampleRate: Int,
        channels: Int,
        isSessionEnd: Boolean
    ): Int {
        if (mRtcEngine == null) {
            LogUtils.e(
                TAG,
                "pushExternalDirectAudioFrameByIdInternal error: not initialized"
            )
            return -Constants.ERR_NOT_INITIALIZED
        }
        if (mCustomAudioDirectTrackId == -1) {
            return -Constants.ERR_NOT_INITIALIZED
        }
        val timestamp = 0L//AudioFrameManager.generatePts(data, sampleRate, channels, isSessionEnd)
        val ret = mRtcEngine?.pushExternalAudioFrame(
            data,
            timestamp,
            sampleRate,
            channels,
            Constants.BytesPerSample.TWO_BYTES_PER_SAMPLE,
            mCustomAudioDirectTrackId
        )

        return if (ret == 0) {
            Constants.ERR_OK
        } else {
            LogUtils.e(TAG, "pushExternalDirectAudioFrame error: $ret")
            -Constants.ERR_FAILED
        }
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
                "pushExternalAudioFrameByIdInternal error: not initialized"
            )
            return -Constants.ERR_NOT_INITIALIZED
        }
        if (mCustomAudioTrackId == -1) {
            return -Constants.ERR_NOT_INITIALIZED
        }
        val timestamp = 0L
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
        if (mRtcEngine == null) {
            LogUtils.e(
                TAG,
                "sendAudioMetadataEx error: not initialized"
            )
            return
        }
        if (mRtcConnection == null) {
            return
        }
        (mRtcEngine as RtcEngineEx).sendAudioMetadataEx(data, mRtcConnection)
    }

    fun destroy() {
        if (mCustomAudioTrackId != -1) {
            mRtcEngine?.destroyCustomAudioTrack(mCustomAudioTrackId)
            mCustomAudioTrackId = -1
        }

        if (mCustomAudioDirectTrackId != -1) {
            mRtcEngine?.destroyCustomAudioTrack(mCustomAudioDirectTrackId)
            mCustomAudioDirectTrackId = -1
        }
        AudioFrameManager.release()
        RtcEngine.destroy()
        mRtcEngine = null
        mRtcConnection = null
        mRtcEventCallback = null
        mAudioFileName = ""

        mExecutor.close()
        mRecordingExecutor.close()
        mPushExecutor.close()
        stopPlaybackTicker()
        try {
            mPlaybackScheduler.shutdownNow()
        } catch (_: Throwable) {
        }

        LogUtils.d(TAG, "rtc destroy")
    }

    private fun enqueuePlaybackData(data: ByteArray) {
        synchronized(mPlaybackQueueLock) {
            mPlaybackQueue.addLast(data)
            mPlaybackQueuedBytes += data.size
        }
    }

    private fun clearPlaybackQueue() {
        synchronized(mPlaybackQueueLock) {
            mPlaybackQueue.clear()
            mPlaybackQueueHeadOffset = 0
            mPlaybackQueuedBytes = 0
        }
    }

    private fun ensurePlaybackTicker() {
        if (mPlaybackTimerFuture != null && !mPlaybackTimerFuture!!.isCancelled) {
            return
        }
        mPlaybackTimerFuture = mPlaybackScheduler.scheduleAtFixedRate({
            try {
                val chunk = dequeuePlaybackChunk()
                if (chunk != null) {
                    mPushExecutor.asExecutor().execute {
                        pushExternalAudioFrame(chunk, mPlaybackSampleRate, mPlaybackChannels, false)
                    }
                }
            } catch (_: Throwable) {
            }
        }, 50, 50, TimeUnit.MILLISECONDS)
    }

    private fun stopPlaybackTicker() {
        try {
            mPlaybackTimerFuture?.cancel(true)
        } catch (_: Throwable) {
        } finally {
            mPlaybackTimerFuture = null
        }
    }

    private fun dequeuePlaybackChunk(): ByteArray? {
        val sampleRate: Int
        val channels: Int
        synchronized(mPlaybackQueueLock) {
            sampleRate = mPlaybackSampleRate
            channels = mPlaybackChannels
        }
        if (sampleRate <= 0 || channels <= 0) {
            return null
        }
        val bytesNeeded = ((sampleRate * channels * 2L * 50L) / 1000L).toInt()
        synchronized(mPlaybackQueueLock) {
            if (mPlaybackQueuedBytes < bytesNeeded) {
                return null
            }
            val output = ByteArray(bytesNeeded)
            var bytesCopied = 0
            while (bytesCopied < bytesNeeded && mPlaybackQueue.isNotEmpty()) {
                val head = mPlaybackQueue.peekFirst()
                if (head == null) {
                    break
                }
                val availableInHead = head.size - mPlaybackQueueHeadOffset
                val toCopy = minOf(bytesNeeded - bytesCopied, availableInHead)
                System.arraycopy(head, mPlaybackQueueHeadOffset, output, bytesCopied, toCopy)
                bytesCopied += toCopy
                mPlaybackQueueHeadOffset += toCopy
                if (mPlaybackQueueHeadOffset >= head.size) {
                    mPlaybackQueue.removeFirst()
                    mPlaybackQueueHeadOffset = 0
                }
            }
            mPlaybackQueuedBytes -= bytesCopied
            if (bytesCopied == bytesNeeded) {
                return output
            } else {
                // Should not happen due to size check, but guard anyway
                // Put back the partially consumed bytes at the front (simple approach: prepend to queue)
                // For simplicity, if partial, re-prepend and return null. This case is unlikely.
                val restored = ByteArray(bytesCopied)
                System.arraycopy(output, 0, restored, 0, bytesCopied)
                if (restored.isNotEmpty()) {
                    // Put back to the front by creating a new array that concatenates
                    // However ArrayDeque has no addFirst for arrays with offset. We'll rebuild queue state.
                    val remaining = ArrayDeque<ByteArray>()
                    remaining.add(restored)
                    while (mPlaybackQueue.isNotEmpty()) {
                        remaining.add(mPlaybackQueue.removeFirst())
                    }
                    mPlaybackQueue.clear()
                    for (b in remaining) {
                        mPlaybackQueue.addLast(b)
                    }
                    mPlaybackQueuedBytes += bytesCopied
                    mPlaybackQueueHeadOffset = 0
                }
                return null
            }
        }
    }
}