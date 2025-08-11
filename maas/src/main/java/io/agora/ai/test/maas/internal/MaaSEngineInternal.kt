package io.agora.ai.test.maas.internal

import android.util.Log
import android.view.View
import io.agora.ai.test.maas.MaaSConstants
import io.agora.ai.test.maas.MaaSEngine
import io.agora.ai.test.maas.MaaSEngineEventHandler
import io.agora.ai.test.maas.internal.rtm.RtmManager
import io.agora.ai.test.maas.internal.utils.Utils
import io.agora.ai.test.maas.model.JoinChannelConfig
import io.agora.ai.test.maas.model.MaaSEngineConfiguration
import io.agora.ai.test.maas.model.WatermarkOptions
import io.agora.base.JavaI420Buffer
import io.agora.base.VideoFrame
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.DataStreamConfig
import io.agora.rtc2.IAudioFrameObserver
import io.agora.rtc2.IMetadataObserver
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcConnection
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.RtcEngineConfig.LogConfig
import io.agora.rtc2.RtcEngineEx
import io.agora.rtc2.audio.AdvancedAudioOptions
import io.agora.rtc2.audio.AudioParams
import io.agora.rtc2.audio.AudioTrackConfig
import io.agora.rtc2.internal.EncryptionConfig
import io.agora.rtc2.video.AgoraMetadata
import io.agora.rtc2.video.VideoEncoderConfiguration
import io.agora.rtc2.video.VideoEncoderConfiguration.VideoDimensions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

@OptIn(ExperimentalStdlibApi::class)
class MaaSEngineInternal : MaaSEngine(), AutoCloseable {
    private var mRtcEngine: RtcEngine? = null
    private var mMaaSEngineConfiguration: MaaSEngineConfiguration? = null
    private var mEventCallback: MaaSEngineEventHandler? = null
    private var mDataStreamId: Int = -1
    private var mAudioFileName = ""

    private var mVideoTrackerId = 0

    private val executor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(executor)

    private var mVideoMetadataObserver: IMetadataObserver? = null
    private var mJoinChannelConfig: JoinChannelConfig? = null
    private var mChannelId = ""
    private var mLocalUserId = 0
    private var mRemoteUserId = 0

    private var mCustomAudioTrackId = -1

    // For reading audio file from assets
    private var mAudioFileInputStream: InputStream? = null
    private var mAudioFileData: ByteArray? = null
    private var mAudioFilePosition: Int = 0

    private var delayFrameCount = 100
    private var bufferSize = 160 * 2
    private var ringBuffer: Array<ByteArray>? = null
    private var writeIndex = 0
    private var readIndex = 0
    private var filledCount = 0
    private var silence: ByteArray? = null
    private var currentAudioByteArray: ByteArray? = null
    private var rtcConnection: RtcConnection? = null

    private var rtcEventHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
            Log.d(
                MaaSConstants.TAG,
                "onJoinChannelSuccess channel:$channel uid:$uid elapsed:$elapsed"
            )
            mLocalUserId = uid
            if (-1 == mDataStreamId) {
                val cfg = DataStreamConfig()
                cfg.syncWithAudio = false
                cfg.ordered = true
                mDataStreamId = mRtcEngine?.createDataStream(cfg) ?: -1
            }
            if (rtcConnection != null) {
                rtcConnection = RtcConnection(channel, uid)
            }
            mEventCallback?.onJoinChannelSuccess(channel, uid, elapsed)
        }

        override fun onLeaveChannel(stats: RtcStats) {
            Log.d(MaaSConstants.TAG, "onLeaveChannel")
            mEventCallback?.onLeaveChannelSuccess()
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            Log.d(MaaSConstants.TAG, "onUserJoined uid:$uid elapsed:$elapsed")
            mRemoteUserId = uid
            mEventCallback?.onUserJoined(uid, elapsed)
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            Log.d(MaaSConstants.TAG, "onUserOffline uid:$uid reason:$reason")
            mEventCallback?.onUserOffline(uid, reason)
        }

        override fun onAudioVolumeIndication(
            speakers: Array<out AudioVolumeInfo>?,
            totalVolume: Int
        ) {
//                    Log.d(
//                        MaasConstants.TAG,
//                        "onAudioVolumeIndication totalVolume:$totalVolume"
//                    )
            val allSpeakers = ArrayList<io.agora.ai.test.maas.model.AudioVolumeInfo>()
            speakers?.forEach {
                allSpeakers.add(
                    io.agora.ai.test.maas.model.AudioVolumeInfo(
                        it.uid,
                        it.volume
                    )
                )
            }
            mEventCallback?.onAudioVolumeIndication(allSpeakers, totalVolume)
        }

        override fun onStreamMessage(uid: Int, streamId: Int, data: ByteArray?) {
            super.onStreamMessage(uid, streamId, data)
            Log.d(
                MaaSConstants.TAG,
                "onStreamMessage uid:$uid streamId:$streamId byte array data:${
                    Utils.bytesToHex(
                        data
                    )
                } string data:${String(data!!)}"
            )
            mEventCallback?.onStreamMessage(uid, data)
        }

        override fun onAudioMetadataReceived(uid: Int, data: ByteArray?) {
            super.onAudioMetadataReceived(uid, data)
            Log.d(
                MaaSConstants.TAG,
                "onAudioMetadataReceived uid:$uid data:${
                    Utils.bytesToHex(
                        data
                    )
                } string data:${String(data!!)}"
            )
            mEventCallback?.onAudioMetadataReceived(uid, data)
        }
    }

    override fun initialize(configuration: MaaSEngineConfiguration): Int {
        Log.d(MaaSConstants.TAG, "initialize configuration:$configuration")
        if (configuration.context == null || configuration.eventHandler == null) {
            Log.e(MaaSConstants.TAG, "initialize error: context or eventHandler is null")
            return MaaSConstants.ERROR_INVALID_PARAMS
        }
        if (mRtcEngine != null) {
            Log.i(MaaSConstants.TAG, "initialize error: already initialized")
            return MaaSConstants.OK
        }
        Log.d(MaaSConstants.TAG, "maas version:" + getSdkVersion())

        mAudioFileName = configuration.context?.externalCacheDir?.absolutePath + "/audio_"
        Log.d(MaaSConstants.TAG, "mAudioFileName:$mAudioFileName")
        mMaaSEngineConfiguration = configuration
        mEventCallback = configuration.eventHandler
        try {
            Log.d(MaaSConstants.TAG, "RtcEngine version:" + RtcEngine.getSdkVersion())
            val rtcEngineConfig = RtcEngineConfig()
            rtcEngineConfig.mContext = configuration.context
            rtcEngineConfig.mAppId = configuration.appId
            rtcEngineConfig.mChannelProfile =
                Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            rtcEngineConfig.mEventHandler = rtcEventHandler

            val logConfig = LogConfig()
            logConfig.level = Constants.LOG_LEVEL_INFO
            rtcEngineConfig.mLogConfig = logConfig

            mRtcEngine = RtcEngine.create(rtcEngineConfig)

            Log.d(
                MaaSConstants.TAG,
                "initRtcEngine audio profile:${configuration.audioProfile} audio scenario:${configuration.audioScenario}"
            )
            mRtcEngine?.setAudioProfile(configuration.audioProfile)
            mRtcEngine?.setAudioScenario(configuration.audioScenario)

            setAgoraRtcParameters("{\"rtc.enable_debug_log\":true}")

            for (params in configuration.params) {
                setAgoraRtcParameters(params)
            }

//            setAgoraRtcParameters("{\"che.audio.get_burst_mode\":true}")
//            setAgoraRtcParameters("{\"che.audio.neteq.max_wait_first_decode_ms\":0}")
//            setAgoraRtcParameters("{\"che.audio.neteq.max_wait_ms\":0}")
//            setAgoraRtcParameters("{\"che.audio.frame_dump\":{\"location\":\"all\",\"action\":\"start\",\"max_size_bytes\":\"100000000\",\"uuid\":\"123456789\", \"duration\": \"150000\"}}")

//            setAgoraRtcParameters("{\"che.audio.aec.split_srate_for_48k\":16000}")
//            setAgoraRtcParameters("{\"che.audio.sf.enabled\":true}")
//            setAgoraRtcParameters("{\"che.audio.sf.stftType\":6}")
//            setAgoraRtcParameters("{\"che.audio.sf.ainlpLowLatencyFlag\":1}")
//            setAgoraRtcParameters("{\"che.audio.sf.ainsLowLatencyFlag \":1}")
//            setAgoraRtcParameters("{\"che.audio.sf.procChainMode\":1}")
//            setAgoraRtcParameters("{\"che.audio.sf.nlpDynamicMode\":1}")
//            setAgoraRtcParameters("{\"che.audio.sf.nlpAlgRoute\":0}")
//            setAgoraRtcParameters("{\"che.audio.sf.ainlpModelPref\":10}")
//            setAgoraRtcParameters("{\"che.audio.sf.nsngAlgRoute\":12}")
//            setAgoraRtcParameters("{\"che.audio.sf.ainsModelPref\":10}")
//            setAgoraRtcParameters("{\"che.audio.sf.nsngPredefAgg\":11}")
//            setAgoraRtcParameters("{\"che.audio.agc.enable\":false}")

//            setAgoraRtcParameters("{\"che.audio.aec.enable\":false}")
//            setAgoraRtcParameters("{\"che.audio.ans.enable\":false}")
//            setAgoraRtcParameters("{\"che.audio.agc.enable\":false}")
//            setAgoraRtcParameters("{\"che.audio.custom_payload_type\":78}")
//            setAgoraRtcParameters("{\"che.audio.custom_bitrate\":128000}")
//            setAgoraRtcParameters("{\"che.audio.frame_dump\":{\"location\":\"all\",\"action\":\"start\",\"max_size_bytes\":\"100000000\",\"uuid\":\"123456789\", \"duration\": \"150000\"}}")

            mRtcEngine?.adjustRecordingSignalVolume(128)

            if (configuration.audioProfile == Constants.AUDIO_PROFILE_MUSIC_HIGH_QUALITY_STEREO) {
                val options = AdvancedAudioOptions()
                options.audioProcessingChannels =
                    AdvancedAudioOptions.AudioProcessingChannelsEnum.AGORA_AUDIO_STEREO_PROCESSING
                mRtcEngine?.setAdvancedAudioOptions(options)
            }

            mRtcEngine?.setDefaultAudioRoutetoSpeakerphone(true)
            if (configuration.enableRtm) {
                RtmManager.initialize(configuration)
            }

            Log.d(
                MaaSConstants.TAG, "initRtcEngine success"
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(
                MaaSConstants.TAG, "initRtcEngine error:" + e.message
            )
            return MaaSConstants.ERROR_GENERIC
        }
        return MaaSConstants.OK
    }

    private fun setAgoraRtcParameters(parameters: String) {
        Log.d(MaaSConstants.TAG, "setAgoraRtcParameters parameters:$parameters")
        if (mRtcEngine == null) {
            Log.e(MaaSConstants.TAG, "setAgoraRtcParameters error: not initialized")
            return
        }
        val ret = mRtcEngine?.setParameters(parameters) ?: -1
        if (ret != 0) {
            Log.e(MaaSConstants.TAG, "setAgoraRtcParameters parameters:${parameters} error: $ret")
        }
    }


    fun processBuffer(buffer: ByteBuffer?) {
        if (buffer == null) return

        buffer.rewind()
        val byteArray = ByteArray(buffer.remaining())
        buffer.get(byteArray)


        for (i in byteArray.indices step 4) {
            Log.d(
                MaaSConstants.TAG,
                "byteArray[$i]:${byteArray[i + 2]} $byteArray[i + 1]}:${byteArray[i + 3]}"
            )

            byteArray[i] = 0
            byteArray[i + 1] = 0
//            byteArray[i + 2] = 0
//            byteArray[i + 3] = 0
        }

        buffer.clear()
        buffer.put(byteArray)
        buffer.rewind()
    }

    private fun writeRecordingAudioFrame(buffer: ByteBuffer?) {
        if (buffer == null || mAudioFileData == null) return

        buffer.rewind()
        val byteArray = ByteArray(buffer.remaining())

        // Read data from audio file in circular manner
        val audioFileData = mAudioFileData!!
        val totalSize = audioFileData.size
        val needSize = byteArray.size

        var copied = 0
        while (copied < needSize) {
            val remainingInFile = totalSize - mAudioFilePosition
            val copySize = minOf(needSize - copied, remainingInFile)

            // Copy data from current position
            System.arraycopy(audioFileData, mAudioFilePosition, byteArray, copied, copySize)

            copied += copySize
            mAudioFilePosition += copySize

            // If reached end of file, reset to beginning
            if (mAudioFilePosition >= totalSize) {
                mAudioFilePosition = 0
                Log.d(MaaSConstants.TAG, "Audio file reached end, resetting to beginning")
            }
        }

        buffer.clear()
        buffer.put(byteArray)
        buffer.rewind()
    }

    private fun saveAudioFrame(buffer: ByteBuffer?) {
        if (buffer == null) return

        buffer.rewind()
        val byteArray = ByteArray(buffer.remaining())
        buffer.get(byteArray)


        scope.launch {
            saveFile(mAudioFileName, byteArray)
        }
    }

    private fun delayPlayback(buffer: ByteBuffer?) {
        if (buffer == null) return
        if (currentAudioByteArray == null) {
            currentAudioByteArray = ByteArray(buffer.remaining())
        }
        if (silence == null) {
            silence = ByteArray(buffer.remaining())
        }

        currentAudioByteArray?.let { buffer.get(it, 0, it.size) }
        buffer.clear()

        if (filledCount < delayFrameCount) {
            buffer.put(silence!!)
            filledCount++
        } else {
            val readBuf = ringBuffer?.get(readIndex)
            if (readBuf != null) {
                buffer.put(readBuf)
            }
            readIndex = (readIndex + 1) % delayFrameCount
        }

        val writeBuf = ringBuffer?.get(writeIndex)
        currentAudioByteArray?.let {
            if (writeBuf != null) {
                System.arraycopy(it, 0, writeBuf, 0, it.size)
            }
        }

        writeIndex = (writeIndex + 1) % delayFrameCount

        buffer.rewind()
    }

    private fun saveFile(fileName: String, byteArray: ByteArray) {
        val file = File(fileName)
        FileOutputStream(file, true).use { outputStream ->
            outputStream.write(byteArray)

        }
    }

    private fun registerAudioFrame(
        enableStereoTest: Boolean,
        enableSaveAudio: Boolean,
        enableWriteRecordingAudioFrame: Boolean,
        enableDelayPlayback: Boolean,
        delayFrameCount: Int
    ) {
        if (!enableStereoTest && !enableSaveAudio && !enableWriteRecordingAudioFrame && !enableDelayPlayback) {
            Log.d(MaaSConstants.TAG, "registerAudioFrame: no audio frame observer enabled")
            return
        }

        if (mRtcEngine == null) {
            Log.e(MaaSConstants.TAG, "registerAudioFrame error: not initialized")
            return
        }

        if (enableStereoTest) {
            mRtcEngine?.setRecordingAudioFrameParameters(
                16000,
                2,
                Constants.RAW_AUDIO_FRAME_OP_MODE_READ_WRITE,
                320
            )
        }

        if (enableWriteRecordingAudioFrame) {
            mRtcEngine?.setRecordingAudioFrameParameters(
                48000,
                1,
                Constants.RAW_AUDIO_FRAME_OP_MODE_READ_WRITE,
                480
            )
        }

        if (enableSaveAudio) {
            mRtcEngine?.setPlaybackAudioFrameBeforeMixingParameters(
                16000,
                1
            )
        }

        if (enableDelayPlayback) {
            mRtcEngine?.setPlaybackAudioFrameParameters(
                16000,
                1, Constants.RAW_AUDIO_FRAME_OP_MODE_READ_WRITE, 160
            )
            this.delayFrameCount = delayFrameCount
            this.bufferSize = 160 * 2 // 160 samples * 2 bytes per sample (16-bit)
            ringBuffer = Array(delayFrameCount) { ByteArray(bufferSize) }
            silence = ByteArray(bufferSize)
            currentAudioByteArray = ByteArray(bufferSize)
        }
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
                if (enableWriteRecordingAudioFrame) {
                    writeRecordingAudioFrame(buffer)
                } else if (enableStereoTest) {
                    processBuffer(buffer)
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
                if (enableDelayPlayback) {
                    delayPlayback(buffer)
                }
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
                if (enableSaveAudio) {
                    saveAudioFrame(buffer)
                }
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

    override fun joinChannel(
        channelId: String,
        roleType: Int,
        joinChannelConfig: JoinChannelConfig
    ): Int {
        Log.d(
            MaaSConstants.TAG,
            "joinChannel channelId:$channelId roleType:$roleType joinChannelConfig:$joinChannelConfig"
        )
        mChannelId = channelId
        mJoinChannelConfig = joinChannelConfig
        if (mRtcEngine == null) {
            Log.e(MaaSConstants.TAG, "joinChannel error: not initialized")
            return MaaSConstants.ERROR_NOT_INITIALIZED
        }
        try {
            val handleConfigRet = handleJoinChannelConfig(joinChannelConfig)
            if (handleConfigRet != MaaSConstants.OK) {
                return handleConfigRet
            }
            val ret = mMaaSEngineConfiguration?.userId?.let {
                mAudioFileName +=
                    channelId + "_" + it + "_" + System.currentTimeMillis() + ".pcm"
                val rtcToken =
                    if (mMaaSEngineConfiguration?.rtcToken?.isEmpty() == true) mMaaSEngineConfiguration?.appId else mMaaSEngineConfiguration?.rtcToken
                val channelMediaOption = object : ChannelMediaOptions() {
                    init {
                        autoSubscribeAudio = true
                        autoSubscribeVideo = false
                        publishCustomVideoTrack = joinChannelConfig.enablePushExternalVideo
                        customVideoTrackId = mVideoTrackerId
                        clientRoleType = roleType
                        publishCustomAudioTrack =
                            joinChannelConfig.enableCustomDirectAudioTracker
                        publishCustomAudioTrackId = mCustomAudioTrackId
                        publishMicrophoneTrack =
                            !joinChannelConfig.enableCustomDirectAudioTracker
                        enableAudioRecordingOrPlayout =
                            joinChannelConfig.enableCustomDirectAudioTracker
                    }
                }
                if (joinChannelConfig.enableJoinChannelEx) {
                    rtcConnection = RtcConnection(channelId, it)
                    (mRtcEngine as RtcEngineEx).joinChannelEx(
                        rtcToken,
                        rtcConnection,
                        channelMediaOption,
                        rtcEventHandler
                    )
                } else {
                    rtcConnection = null
                    mRtcEngine?.joinChannel(
                        rtcToken,
                        channelId,
                        it,
                        channelMediaOption
                    )
                } ?: MaaSConstants.ERROR_INVALID_PARAMS

            }

            val joinParams =
                "{\"che.audio.playout_uid_anonymous\":{\"channelId\":\"${mChannelId}\", \"localUid\":${mLocalUserId}, \"remoteUid\": ${mRemoteUserId}, \"anonymous\": true}}"
            setAgoraRtcParameters(joinParams)
            Log.d(
                MaaSConstants.TAG, "joinChannel ret:$ret"
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(
                MaaSConstants.TAG, "joinChannel error:" + e.message
            )
            return MaaSConstants.ERROR_GENERIC
        }
        if (mMaaSEngineConfiguration?.enableRtm == true) {
            RtmManager.joinChannel(channelId)
        }
        return MaaSConstants.OK
    }

    private fun handleJoinChannelConfig(joinChannelConfig: JoinChannelConfig): Int {
        // Load audio file from assets if enableWriteRecordingAudioFrame is enabled
        if (joinChannelConfig.enableWriteRecordingAudioFrame) {
            try {
                val inputStream =
                    mMaaSEngineConfiguration?.context?.assets?.open("delay_yuliao_48k_1ch.pcm")
                mAudioFileData = inputStream?.readBytes()
                inputStream?.close()
                mAudioFilePosition = 0
                Log.d(
                    MaaSConstants.TAG,
                    "Audio file loaded from assets for writeRecordingAudioFrame, size: ${mAudioFileData?.size}"
                )
            } catch (e: Exception) {
                Log.e(MaaSConstants.TAG, "Failed to load audio file from assets: ${e.message}")
                mAudioFileData = null
            }
        }

        registerAudioFrame(
            joinChannelConfig.enableStereoTest,
            joinChannelConfig.enableSaveAudio,
            joinChannelConfig.enableWriteRecordingAudioFrame,
            joinChannelConfig.enableDelayPlayback, joinChannelConfig.delayFrameCount
        )



        if (joinChannelConfig.enablePushExternalVideo) {
            mVideoTrackerId = mRtcEngine?.createCustomVideoTrack() ?: 0
            Log.d(
                MaaSConstants.TAG,
                "createCustomVideoTrack mVideoTrackerId:$mVideoTrackerId"
            )
        }

        if (joinChannelConfig.enableEncryption) {
            val encryptionConfig = EncryptionConfig()
            if (joinChannelConfig.encryptionConfig != null) {
                encryptionConfig.encryptionKey =
                    joinChannelConfig.encryptionConfig?.encryptionKey
                encryptionConfig.encryptionMode = Utils.getEncryptionMode(
                    joinChannelConfig.encryptionConfig?.encryptionMode?.value ?: 0
                )
                joinChannelConfig.encryptionConfig?.encryptionKdfSalt?.let {
                    System.arraycopy(
                        it,
                        0,
                        encryptionConfig.encryptionKdfSalt,
                        0,
                        encryptionConfig.encryptionKdfSalt.size
                    )
                }
                Log.d(
                    MaaSConstants.TAG,
                    "encryptionConfig mode:${encryptionConfig.encryptionMode} key:${encryptionConfig.encryptionKey} salt:${encryptionConfig.encryptionKdfSalt.contentToString()}"
                )

            }
            val ret = mRtcEngine?.enableEncryption(
                joinChannelConfig.enableEncryption,
                encryptionConfig
            )
            if (ret != 0) {
                Log.e(MaaSConstants.TAG, "enableEncryption error: $ret")
                return ret ?: MaaSConstants.ERROR_GENERIC
            }
            Log.d(
                MaaSConstants.TAG,
                "enableEncryption success"
            )
        }

        if (joinChannelConfig.enablePullAudioFrame) {
            mRtcEngine?.setExternalAudioSink(true, 16000, 1)
            CoroutineScope(Dispatchers.IO).launch {
                mMaaSEngineConfiguration?.context?.let {
                    PullAudioFrameManager.start(
                        it,
                        mRtcEngine!!,
                        10,
                        16000,
                        1,
                        true
                    )
                }
            }
        }

        if (joinChannelConfig.enableSendVideoMetadata) {
            mVideoMetadataObserver = object : IMetadataObserver {
                override fun getMaxMetadataSize(): Int {
                    return 1024
                }

                override fun onReadyToSendMetadata(
                    timeStampMs: Long,
                    sourceType: Int
                ): ByteArray {
                    val metadataString = "Timestamp:${System.currentTimeMillis()}"
                    val metadata = metadataString.toByteArray(Charsets.UTF_8)
                    Log.d(MaaSConstants.TAG, "onReadyToSendMetadata:$metadataString")
                    return metadata
                }

                override fun onMetadataReceived(metadata: AgoraMetadata?) {
                    Log.d(
                        MaaSConstants.TAG,
                        "onMetadataReceived channelId:${metadata?.channelId},uid:${metadata?.uid},data:${metadata?.data?.contentToString()},timeStampMs:${metadata?.timeStampMs}"
                    )
                }
            }
            mRtcEngine?.registerMediaMetadataObserver(
                mVideoMetadataObserver,
                IMetadataObserver.VIDEO_METADATA
            )
            Log.d(MaaSConstants.TAG, "registerMediaMetadataObserver success")
        }

        if (joinChannelConfig.enableCustomDirectAudioTracker) {
            val audioTrackConfig = AudioTrackConfig()
            audioTrackConfig.enableLocalPlayback = false
            //4.5.1
            audioTrackConfig.enableAudioProcessing = false
            mCustomAudioTrackId = mRtcEngine?.createCustomAudioTrack(
                Constants.AudioTrackType.AUDIO_TRACK_DIRECT,
                audioTrackConfig
            ) ?: 0
            Log.d(
                MaaSConstants.TAG,
                "createCustomAudioTrack mCustomAudioTrackId:$mCustomAudioTrackId"
            )
        }

        return MaaSConstants.OK
    }

    override fun leaveChannel(): Int {
        Log.d(MaaSConstants.TAG, "leaveChannel")
        if (mRtcEngine == null) {
            Log.e(MaaSConstants.TAG, "leaveChannel error: not initialized")
            return MaaSConstants.ERROR_NOT_INITIALIZED
        }
        PullAudioFrameManager.stop()
        try {
            val leaveParams =
                "{\"che.audio.playout_uid_anonymous\":{\"channelId\":\"${mChannelId}\", \"localUid\":${mLocalUserId}, \"remoteUid\": ${mRemoteUserId}, \"anonymous\": false}}"
            setAgoraRtcParameters(leaveParams)
            Log.d(MaaSConstants.TAG, "setParameters $leaveParams")
            Thread.sleep(300)

            mRtcEngine?.leaveChannel()
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(
                MaaSConstants.TAG, "leaveChannel error:" + e.message
            )
            return MaaSConstants.ERROR_GENERIC
        }
        if (mMaaSEngineConfiguration?.enableRtm == true) {
            RtmManager.leaveChannel()
        }

        if (mVideoTrackerId != 0) {
            mRtcEngine?.destroyCustomVideoTrack(mVideoTrackerId)
            mVideoTrackerId = 0
        }

        // Clean up audio file resources when leaving channel
        mAudioFileData = null
        mAudioFilePosition = 0

        return MaaSConstants.OK
    }

    override fun startVideo(
        view: View?,
        renderMode: MaaSConstants.RenderMode?,
        position: MaaSConstants.VideoModulePosition
    ): Int {
        Log.d(
            MaaSConstants.TAG, "startVideo view:$view renderMode:$renderMode position:$position"
        )
        if (mRtcEngine == null) {
            Log.e(MaaSConstants.TAG, "startVideo error: not initialized")
            return MaaSConstants.ERROR_NOT_INITIALIZED
        }
        var ret = mRtcEngine?.enableVideo()
        Log.d(
            MaaSConstants.TAG, "enableVideo ret:$ret"
        )

        ret = mRtcEngine?.updateChannelMediaOptions(object : ChannelMediaOptions() {
            init {
                publishCameraTrack = true
            }
        })
        Log.d(
            MaaSConstants.TAG, "updateChannelMediaOptions ret:$ret"
        )


        if (null != view) {
            ret = mRtcEngine?.startPreview()
            Log.d(
                MaaSConstants.TAG, "startPreview ret:$ret"
            )

            val local = renderMode?.value?.let { io.agora.rtc2.video.VideoCanvas(view, it, 0) }
                ?: io.agora.rtc2.video.VideoCanvas(view)
            local.position = Utils.getRtcVideoModulePosition(position.value)
            ret = mRtcEngine?.setupLocalVideo(local)
            Log.d(
                MaaSConstants.TAG, "setupLocalVideo ret:$ret"
            )
        }

        return if (ret == 0) {
            MaaSConstants.OK
        } else {
            MaaSConstants.ERROR_GENERIC
        }
    }

    override fun stopVideo(): Int {
        Log.d(MaaSConstants.TAG, "stopVideo")
        if (mRtcEngine == null) {
            Log.e(MaaSConstants.TAG, "stopVideo error: not initialized")
            return MaaSConstants.ERROR_NOT_INITIALIZED
        }
        var ret = mRtcEngine?.stopPreview()
        Log.d(
            MaaSConstants.TAG, "stopPreview ret:$ret"
        )
        ret = mRtcEngine?.disableVideo()
        Log.d(
            MaaSConstants.TAG, "disableVideo ret:$ret"
        )

        ret = mRtcEngine?.updateChannelMediaOptions(object : ChannelMediaOptions() {
            init {
                publishCameraTrack = false
            }
        })
        Log.d(
            MaaSConstants.TAG, "updateChannelMediaOptions ret:$ret"
        )
        return if (ret == 0) {
            MaaSConstants.OK
        } else {
            MaaSConstants.ERROR_GENERIC
        }
    }

    override fun setVideoEncoderConfiguration(
        width: Int,
        height: Int,
        frameRate: MaaSConstants.FrameRate,
        orientationMode: MaaSConstants.OrientationMode,
        enableMirrorMode: Boolean
    ): Int {
        Log.d(
            MaaSConstants.TAG,
            "setVideoEncoderConfiguration width:$width height:$height frameRate:$frameRate orientationMode:$orientationMode enableMirrorMode:$enableMirrorMode"
        )
        if (mRtcEngine == null) {
            Log.e(MaaSConstants.TAG, "setVideoEncoderConfiguration error: not initialized")
            return MaaSConstants.ERROR_NOT_INITIALIZED
        }
        val ret = mRtcEngine?.setVideoEncoderConfiguration(
            VideoEncoderConfiguration(
                VideoDimensions(width, height),
                Utils.getRtcFrameRate(frameRate.value),
                VideoEncoderConfiguration.STANDARD_BITRATE,
                Utils.getRtcOrientationMode(orientationMode.value),
                if (enableMirrorMode) {
                    VideoEncoderConfiguration.MIRROR_MODE_TYPE.MIRROR_MODE_ENABLED
                } else {
                    VideoEncoderConfiguration.MIRROR_MODE_TYPE.MIRROR_MODE_DISABLED
                }
            )
        )
        return if (ret == 0) {
            MaaSConstants.OK
        } else {
            MaaSConstants.ERROR_GENERIC
        }
    }

    override fun setupRemoteVideo(
        view: View?,
        renderMode: MaaSConstants.RenderMode?,
        remoteUid: Int
    ): Int {
        Log.d(
            MaaSConstants.TAG,
            "setupRemoteVideo view:$view renderMode:$renderMode remoteUid:$remoteUid"
        )
        if (mRtcEngine == null) {
            Log.e(MaaSConstants.TAG, "setupRemoteVideo error: not initialized")
            return MaaSConstants.ERROR_NOT_INITIALIZED
        }

        if (null != view) {
            val remote =
                renderMode?.value?.let { io.agora.rtc2.video.VideoCanvas(view, it, remoteUid) }
                    ?: io.agora.rtc2.video.VideoCanvas(view)
            val ret = mRtcEngine?.setupRemoteVideo(remote)
            Log.d(
                MaaSConstants.TAG, "setupRemoteVideo ret:$ret"
            )
        }

        return MaaSConstants.OK
    }

    override fun switchCamera(): Int {
        Log.d(MaaSConstants.TAG, "switchCamera")
        if (mRtcEngine == null) {
            Log.e(MaaSConstants.TAG, "switchCamera error: not initialized")
            return MaaSConstants.ERROR_NOT_INITIALIZED
        }
        val ret = mRtcEngine?.switchCamera()
        return if (ret == 0) {
            MaaSConstants.OK
        } else {
            MaaSConstants.ERROR_GENERIC
        }
    }

    override fun addVideoWatermark(
        watermarkUrl: String,
        watermarkOptions: WatermarkOptions
    ): Int {
        Log.d(
            MaaSConstants.TAG,
            "addVideoWatermark watermarkUrl:$watermarkUrl watermarkOptions:$watermarkOptions"
        )
        if (mRtcEngine == null) {
            Log.e(MaaSConstants.TAG, "addVideoWatermark error: not initialized")
            return MaaSConstants.ERROR_NOT_INITIALIZED
        }

        val ret = mRtcEngine?.addVideoWatermark(
            watermarkUrl,
            Utils.getRtcWatermarkOptions(watermarkOptions)
        )
        Log.d(
            MaaSConstants.TAG,
            "addVideoWatermark ret:$ret"
        )
        return if (ret == 0) {
            MaaSConstants.OK
        } else {
            MaaSConstants.ERROR_GENERIC
        }
    }

    override fun addVideoWatermark(
        data: ByteBuffer,
        width: Int,
        height: Int,
        format: MaaSConstants.VideoFormat,
        options: WatermarkOptions
    ): Int {
        Log.d(
            MaaSConstants.TAG,
            "addVideoWatermark data:$data width:$width height:$height format:$format options:$options"
        )
        if (mRtcEngine == null) {
            Log.e(MaaSConstants.TAG, "addVideoWatermark error: not initialized")
            return MaaSConstants.ERROR_NOT_INITIALIZED
        }

//        val ret = mRtcEngine?.addVideoWatermark(
//            data,
//            width,
//            height,
//            format.value,
//            Utils.getRtcWatermarkOptions(options)
//        )
        val ret = MaaSConstants.ERROR_GENERIC
        Log.d(
            MaaSConstants.TAG,
            "addVideoWatermark ret:$ret"
        )
        return if (ret == 0) {
            MaaSConstants.OK
        } else {
            MaaSConstants.ERROR_GENERIC
        }
    }

    override fun clearVideoWatermarks(): Int {
        Log.d(MaaSConstants.TAG, "clearVideoWatermarks")
        if (mRtcEngine == null) {
            Log.e(MaaSConstants.TAG, "clearVideoWatermarks error: not initialized")
            return MaaSConstants.ERROR_NOT_INITIALIZED
        }

        val ret = mRtcEngine?.clearVideoWatermarks()
        return if (ret == 0) {
            MaaSConstants.OK
        } else {
            MaaSConstants.ERROR_GENERIC
        }
    }

    override fun enableAudio(): Int {
        Log.d(MaaSConstants.TAG, "enableAudio")
        if (mRtcEngine == null) {
            Log.e(MaaSConstants.TAG, "enableAudio error: not initialized")
            return MaaSConstants.ERROR_NOT_INITIALIZED
        }
        mRtcEngine?.enableAudio()
        val ret = mRtcEngine?.updateChannelMediaOptions(object : ChannelMediaOptions() {
            init {
                publishMicrophoneTrack = true
            }
        })
        //min 50ms
        mRtcEngine?.enableAudioVolumeIndication(
            50,
            3,
            true
        )
        return if (ret == 0) {
            MaaSConstants.OK
        } else {
            MaaSConstants.ERROR_GENERIC
        }
    }

    override fun disableAudio(): Int {
        Log.d(MaaSConstants.TAG, "disableAudio")
        if (mRtcEngine == null) {
            Log.e(MaaSConstants.TAG, "disableAudio error: not initialized")
            return MaaSConstants.ERROR_NOT_INITIALIZED
        }
        mRtcEngine?.disableAudio()
        val ret = mRtcEngine?.updateChannelMediaOptions(object : ChannelMediaOptions() {
            init {
                publishMicrophoneTrack = false
            }
        })
        return if (ret == 0) {
            MaaSConstants.OK
        } else {
            MaaSConstants.ERROR_GENERIC
        }
    }

    override fun adjustPlaybackSignalVolume(volume: Int): Int {
        Log.d(MaaSConstants.TAG, "adjustPlaybackSignalVolume volume:$volume")
        if (mRtcEngine == null) {
            Log.e(MaaSConstants.TAG, "adjustPlaybackSignalVolume error: not initialized")
            return MaaSConstants.ERROR_NOT_INITIALIZED
        }
        val ret = mRtcEngine?.adjustPlaybackSignalVolume(volume)
        return if (ret == 0) {
            MaaSConstants.OK
        } else {
            MaaSConstants.ERROR_GENERIC
        }
    }

    override fun adjustRecordingSignalVolume(volume: Int): Int {
        Log.d(MaaSConstants.TAG, "adjustRecordingSignalVolume volume:$volume")
        if (mRtcEngine == null) {
            Log.e(MaaSConstants.TAG, "adjustRecordingSignalVolume error: not initialized")
            return MaaSConstants.ERROR_NOT_INITIALIZED
        }
        val ret = mRtcEngine?.adjustRecordingSignalVolume(volume)
        return if (ret == 0) {
            MaaSConstants.OK
        } else {
            MaaSConstants.ERROR_GENERIC
        }
    }

    override fun sendText(text: String): Int {
        Log.d(MaaSConstants.TAG, "sendText text:$text")
        if (mRtcEngine == null || mDataStreamId == -1) {
            Log.e(MaaSConstants.TAG, "sendText error: not initialized")
            return MaaSConstants.ERROR_NOT_INITIALIZED
        }
        val ret = mRtcEngine?.sendStreamMessage(mDataStreamId, text.toByteArray(Charsets.UTF_8))
        return if (ret == 0) {
            MaaSConstants.OK
        } else {
            MaaSConstants.ERROR_GENERIC
        }
    }

    override fun sendAudioMetadata(metadata: ByteArray): Int {
        Log.d(MaaSConstants.TAG, "sendAudioMetadata metadata:${String(metadata)}")
        if (mRtcEngine == null) {
            Log.e(MaaSConstants.TAG, "sendAudioMetadata error: not initialized")
            return MaaSConstants.ERROR_NOT_INITIALIZED
        }

        val ret = if (rtcConnection != null) {
            (mRtcEngine as RtcEngineEx).sendAudioMetadataEx(metadata, rtcConnection)
        } else {
            mRtcEngine?.sendAudioMetadata(metadata) ?: -1
        }
        return if (ret == 0) {
            MaaSConstants.OK
        } else {
            MaaSConstants.ERROR_GENERIC
        }
    }

    override fun sendRtmMessage(
        message: ByteArray,
        channelType: MaaSConstants.RtmChannelType,
        userId: String

    ): Int {
        if (mMaaSEngineConfiguration?.enableRtm == true) {
            RtmManager.sendRtmMessage(message, channelType, userId)
        } else {
            Log.e(MaaSConstants.TAG, "sendRtmMessage error: not enabled")
            return MaaSConstants.ERROR_NOT_INITIALIZED
        }
        return MaaSConstants.OK
    }


    override fun pushVideoFrame(
        data: ByteArray,
        width: Int,
        height: Int,
        type: MaaSConstants.ViewFrameType
    ): Int {
        if (mRtcEngine == null) {
            Log.e(MaaSConstants.TAG, "pushVideoFrame error: not initialized")
            return MaaSConstants.ERROR_NOT_INITIALIZED
        }
        when (type) {
            MaaSConstants.ViewFrameType.I420 -> {
                val i420Buffer = JavaI420Buffer.allocate(width, height)
                i420Buffer.dataY.put(data, 0, i420Buffer.dataY.limit())
                i420Buffer.dataU.put(data, i420Buffer.dataY.limit(), i420Buffer.dataU.limit())
                i420Buffer.dataV.put(
                    data,
                    i420Buffer.dataY.limit() + i420Buffer.dataU.limit(),
                    i420Buffer.dataV.limit()
                )

                /*
              * Get monotonic time in ms which can be used by capture time,
              * typical scenario is as follows:
              */
                val currentMonotonicTimeInMs: Long = mRtcEngine?.currentMonotonicTimeInMs ?: 0
                /*
                 * Create a video frame to push.
                 */
                val videoFrame = VideoFrame(i420Buffer, 0, currentMonotonicTimeInMs * 1000000)

                val ret = pushExternalVideoFrameByIdInternal(videoFrame, mVideoTrackerId)
                i420Buffer.release()
                return ret
            }

            MaaSConstants.ViewFrameType.NV21 -> TODO()
            MaaSConstants.ViewFrameType.NV12 -> TODO()
        }
    }

    private fun pushExternalVideoFrameByIdInternal(videoFrame: VideoFrame, trackId: Int): Int {
        if (mRtcEngine == null) {
            Log.e(
                MaaSConstants.TAG,
                "pushExternalVideoFrameByIdInternal error: not initialized"
            )
            return MaaSConstants.ERROR_NOT_INITIALIZED
        }
        val ret = mRtcEngine?.pushExternalVideoFrameById(videoFrame, trackId)
        Log.d(
            MaaSConstants.TAG,
            "pushExternalVideoFrameByIdInternal ret:$ret"
        )
        return if (ret == 0) {
            MaaSConstants.OK
        } else {
            Log.e(MaaSConstants.TAG, "pushExternalVideoFrameByIdInternal error: $ret")
            MaaSConstants.ERROR_GENERIC
        }
    }

    override fun pushExternalAudioFrame(
        data: ByteArray,
        timestamp: Long,
        sampleRate: Int,
        channels: Int,
        bytesPerSample: Int
    ): Int {
        if (mRtcEngine == null) {
            Log.e(
                MaaSConstants.TAG,
                "pushExternalVideoFrameByIdInternal error: not initialized"
            )
            return MaaSConstants.ERROR_NOT_INITIALIZED
        }
        if (mCustomAudioTrackId == -1) {
            return MaaSConstants.ERROR_NOT_INITIALIZED
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
            MaaSConstants.OK
        } else {
            Log.e(MaaSConstants.TAG, "pushExternalAudioFrame error: $ret")
            MaaSConstants.ERROR_GENERIC
        }
    }

    override fun close() {
        scope.cancel()
        runBlocking(Dispatchers.Default) {
            executor.close()
        }
    }

    override fun doDestroy() {
        if (null != mVideoMetadataObserver) {
            mRtcEngine?.unregisterMediaMetadataObserver(
                mVideoMetadataObserver,
                IMetadataObserver.VIDEO_METADATA
            )
            mVideoMetadataObserver = null
        }
        if (mCustomAudioTrackId != -1) {
            mRtcEngine?.destroyCustomAudioTrack(mCustomAudioTrackId)
            mCustomAudioTrackId = -1
        }
        RtcEngine.destroy()
        if (mMaaSEngineConfiguration?.enableRtm == true) {
            RtmManager.rtmLogout()
        }
        mRtcEngine = null
        mMaaSEngineConfiguration = null
        mEventCallback = null
        mDataStreamId = -1
        mAudioFileName = ""
        mLocalUserId = 0
        mRemoteUserId = 0
        mChannelId = ""
        mJoinChannelConfig = null

        // Final clean up audio file resources
        mAudioFileData = null
        mAudioFilePosition = 0
        mAudioFileInputStream?.close()
        mAudioFileInputStream = null

        Log.d(MaaSConstants.TAG, "rtc destroy")
    }


}