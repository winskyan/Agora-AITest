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
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.audio.AdvancedAudioOptions
import io.agora.rtc2.audio.AudioParams
import io.agora.rtc2.internal.EncryptionConfig
import io.agora.rtc2.video.VideoEncoderConfiguration
import io.agora.rtc2.video.VideoEncoderConfiguration.VideoDimensions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class MaaSEngineInternal : MaaSEngine(), AutoCloseable {
    private var mRtcEngine: RtcEngine? = null
    private var mMaaSEngineConfiguration: MaaSEngineConfiguration? = null
    private var mEventCallback: MaaSEngineEventHandler? = null
    private var mDataStreamId: Int = -1
    private var mAudioFileName = ""

    private var mVideoTrackerId = 0

    private val executor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(executor)

    override fun initialize(configuration: MaaSEngineConfiguration): Int {
        Log.d(MaaSConstants.TAG, "initialize configuration:$configuration")
        if (configuration.context == null || configuration.eventHandler == null) {
            Log.e(MaaSConstants.TAG, "initialize error: already initialized")
            return MaaSConstants.ERROR_INVALID_PARAMS
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
            rtcEngineConfig.mEventHandler = object : IRtcEngineEventHandler() {
                override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
                    Log.d(
                        MaaSConstants.TAG,
                        "onJoinChannelSuccess channel:$channel uid:$uid elapsed:$elapsed"
                    )
                    if (-1 == mDataStreamId) {
                        val cfg = DataStreamConfig()
                        cfg.syncWithAudio = false
                        cfg.ordered = true
                        mDataStreamId = mRtcEngine?.createDataStream(cfg) ?: -1
                    }
                    mEventCallback?.onJoinChannelSuccess(channel, uid, elapsed)
                }

                override fun onLeaveChannel(stats: RtcStats) {
                    Log.d(MaaSConstants.TAG, "onLeaveChannel")
                    mEventCallback?.onLeaveChannelSuccess()
                }

                override fun onUserJoined(uid: Int, elapsed: Int) {
                    Log.d(MaaSConstants.TAG, "onUserJoined uid:$uid elapsed:$elapsed")
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
                        "onStreamMessage uid:$uid streamId:$streamId data:${data?.toString()}"
                    )
                    mEventCallback?.onStreamMessage(uid, data)
                }

                override fun onAudioMetadataReceived(uid: Int, data: ByteArray?) {
                    super.onAudioMetadataReceived(uid, data)
                    Log.d(
                        MaaSConstants.TAG,
                        "onAudioMetadataReceived uid:$uid data:${data?.toString()}"
                    )
                    mEventCallback?.onAudioMetadataReceived(uid, data)
                }
            }

            mRtcEngine = RtcEngine.create(rtcEngineConfig)

            Log.d(
                MaaSConstants.TAG,
                "initRtcEngine audio profile:${configuration.audioProfile} audio scenario:${configuration.audioScenario}"
            )
            mRtcEngine?.setAudioProfile(configuration.audioProfile)
            mRtcEngine?.setAudioScenario(configuration.audioScenario)

            mRtcEngine?.setParameters("{\"rtc.enable_debug_log\":true}")

            for (params in configuration.params) {
                mRtcEngine?.setParameters(params)
                Log.d(MaaSConstants.TAG, "setParameters:$params")
            }

//            mRtcEngine?.setParameters("{\"che.audio.aec.enable\":false}")
//            mRtcEngine?.setParameters("{\"che.audio.ans.enable\":false}")
//            mRtcEngine?.setParameters("{\"che.audio.agc.enable\":false}")
//            mRtcEngine?.setParameters("{\"che.audio.custom_payload_type\":78}")
//            mRtcEngine?.setParameters("{\"che.audio.custom_bitrate\":128000}")
//            mRtcEngine?.setParameters("{\"che.audio.frame_dump\":{\"location\":\"all\",\"action\":\"start\",\"max_size_bytes\":\"100000000\",\"uuid\":\"123456789\", \"duration\": \"150000\"}}")

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

    private fun saveAudioFrame(buffer: ByteBuffer?) {
        if (buffer == null) return

        buffer.rewind()
        val byteArray = ByteArray(buffer.remaining())
        buffer.get(byteArray)


        scope.launch {
            saveFile(mAudioFileName, byteArray)
        }
    }

    private suspend fun saveFile(fileName: String, byteArray: ByteArray) {
        val file = File(fileName)
        withContext(Dispatchers.IO) {
            FileOutputStream(file, true).use { outputStream ->
                outputStream.write(byteArray)
            }
        }
    }


    private fun registerAudioFrame(
        enableStereoTest: Boolean,
        enableSaveAudio: Boolean
    ) {
        if (!enableStereoTest && !enableSaveAudio) {
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

        if (enableSaveAudio) {
            mRtcEngine?.setPlaybackAudioFrameBeforeMixingParameters(
                16000,
                2
            )
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
                processBuffer(buffer)
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
                rtpTimestamp: Int
            ): Boolean {
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

    override fun joinChannel(
        channelId: String,
        roleType: Int,
        joinChannelConfig: JoinChannelConfig
    ): Int {
        Log.d(
            MaaSConstants.TAG,
            "joinChannel channelId:$channelId roleType:$roleType joinChannelConfig:$joinChannelConfig"
        )
        if (mRtcEngine == null) {
            Log.e(MaaSConstants.TAG, "joinChannel error: not initialized")
            return MaaSConstants.ERROR_NOT_INITIALIZED
        }
        try {
            registerAudioFrame(
                joinChannelConfig.enableStereoTest,
                joinChannelConfig.enableSaveAudio
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

            val ret = mMaaSEngineConfiguration?.userId?.let {
                mAudioFileName +=
                    channelId + "_" + it + "_" + System.currentTimeMillis() + ".pcm"
                val rtcToken =
                    if (mMaaSEngineConfiguration?.rtcToken?.isEmpty() == true) mMaaSEngineConfiguration?.appId else mMaaSEngineConfiguration?.rtcToken
                mRtcEngine?.joinChannel(
                    rtcToken,
                    channelId,
                    it,
                    object : ChannelMediaOptions() {
                        init {
                            autoSubscribeAudio = true
                            autoSubscribeVideo = true
                            publishCustomVideoTrack = joinChannelConfig.enablePushExternalVideo
                            customVideoTrackId = mVideoTrackerId
                            clientRoleType = roleType
                        }
                    })
            } ?: MaaSConstants.ERROR_INVALID_PARAMS
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

    override fun leaveChannel(): Int {
        Log.d(MaaSConstants.TAG, "leaveChannel")
        if (mRtcEngine == null) {
            Log.e(MaaSConstants.TAG, "leaveChannel error: not initialized")
            return MaaSConstants.ERROR_NOT_INITIALIZED
        }
        PullAudioFrameManager.stop()
        try {
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

    override fun addVideoWatermark(watermarkUrl: String, watermarkOptions: WatermarkOptions): Int {
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

        val ret = mRtcEngine?.addVideoWatermark(
            data,
            width,
            height,
            format.value,
            Utils.getRtcWatermarkOptions(options)
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
        val ret = mRtcEngine?.sendAudioMetadata(metadata)
        return if (ret == 0) {
            MaaSConstants.OK
        } else {
            MaaSConstants.ERROR_GENERIC
        }
    }

    override fun sendRtmMessage(
        message: ByteArray,
        channelType: MaaSConstants.RtmChannelType
    ): Int {
        if (mMaaSEngineConfiguration?.enableRtm == true) {
            RtmManager.sendRtmMessage(message, channelType)
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
        var videoFrame: VideoFrame? = null
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
                videoFrame = VideoFrame(i420Buffer, 0, currentMonotonicTimeInMs * 1000000)

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
            Log.e(MaaSConstants.TAG, "pushExternalVideoFrameByIdInternal error: not initialized")
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

    override fun close() {
        scope.cancel()
        runBlocking(Dispatchers.Default) {
            executor.close()
        }
    }

    override fun doDestroy() {
        RtcEngine.destroy()
        if (mMaaSEngineConfiguration?.enableRtm == true) {
            RtmManager.rtmLogout()
        }
        mRtcEngine = null
        mMaaSEngineConfiguration = null
        mEventCallback = null
        mDataStreamId = -1
        mAudioFileName = ""
        Log.d(MaaSConstants.TAG, "rtc destroy")
    }


}