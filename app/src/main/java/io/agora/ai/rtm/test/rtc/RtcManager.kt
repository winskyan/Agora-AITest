package io.agora.ai.rtm.test.rtc

import android.content.Context
import android.util.Log
import io.agora.ai.rtm.test.constants.Constants
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.IAudioFrameObserver
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.audio.AudioParams
import java.nio.ByteBuffer

object RtcManager {

    private val TAG = Constants.TAG + "-" + RtcManager::class.java.simpleName
    private var mRtcEngine: RtcEngine? = null
    private var mRtcMessageListener: RtcMessageListener? = null

    interface RtcMessageListener {
        fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int)
        fun onLeaveChannelSuccess()
        fun onUserJoined(uid: Int, elapsed: Int)
        fun onUserOffline(uid: Int, reason: Int)
        fun onAudioMetadataReceived(uid: Int, data: ByteArray?)
        fun onRecordAudioFrame(data: ByteArray?)
    }

    fun create(context: Context, appId: String, listener: RtcMessageListener) {
        Log.d(TAG, "rtc create appId: $appId")
        mRtcMessageListener = listener
        try {
            val rtcEngineConfig = RtcEngineConfig()
            rtcEngineConfig.mContext = context
            rtcEngineConfig.mAppId = appId
            rtcEngineConfig.mChannelProfile =
                io.agora.rtc2.Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            rtcEngineConfig.mEventHandler = object : IRtcEngineEventHandler() {
                override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
                    Log.d(
                        TAG,
                        "onJoinChannelSuccess channel:$channel uid:$uid elapsed:$elapsed"
                    )
                    mRtcMessageListener?.onJoinChannelSuccess(channel, uid, elapsed)
                }

                override fun onLeaveChannel(stats: RtcStats) {
                    Log.d(TAG, "onLeaveChannel")
                    mRtcEngine?.registerAudioFrameObserver(null)
                    mRtcMessageListener?.onLeaveChannelSuccess()
                }

                override fun onUserJoined(uid: Int, elapsed: Int) {
                    Log.d(TAG, "onUserJoined uid:$uid elapsed:$elapsed")
                    mRtcMessageListener?.onUserJoined(uid, elapsed)
                }

                override fun onUserOffline(uid: Int, reason: Int) {
                    Log.d(TAG, "onUserOffline uid:$uid reason:$reason")
                    mRtcMessageListener?.onUserOffline(uid, reason)
                }


                override fun onStreamMessage(uid: Int, streamId: Int, data: ByteArray?) {
                    super.onStreamMessage(uid, streamId, data)
                    Log.d(
                        TAG,
                        "onStreamMessage uid:$uid streamId:$streamId data:${data?.toString()}"
                    )
                }

                override fun onAudioMetadataReceived(uid: Int, data: ByteArray?) {
                    super.onAudioMetadataReceived(uid, data)
                    Log.d(
                        TAG,
                        "onAudioMetadataReceived uid:$uid data:${String(data!!)}"
                    )
                    mRtcMessageListener?.onAudioMetadataReceived(uid, data)
                }
            }

            mRtcEngine = RtcEngine.create(rtcEngineConfig)

            mRtcEngine?.setAudioProfile(io.agora.rtc2.Constants.AUDIO_PROFILE_DEFAULT)
            mRtcEngine?.setAudioScenario(io.agora.rtc2.Constants.AUDIO_SCENARIO_CHORUS)

            mRtcEngine?.setParameters("{\"rtc.enable_debug_log\":true}")

            mRtcEngine?.enableAudio()

            mRtcEngine?.setDefaultAudioRoutetoSpeakerphone(true)

            Log.d(
                TAG, "initRtcEngine success"
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(
                TAG, "initRtcEngine error:" + e.message
            )
        }
    }

    private fun registerAudioFrame(
    ) {
        if (mRtcEngine == null) {
            Log.e(TAG, "registerAudioFrame error: not initialized")
            return
        }

        mRtcEngine?.setRecordingAudioFrameParameters(
            16000,
            1,
            io.agora.rtc2.Constants.RAW_AUDIO_FRAME_OP_MODE_READ_ONLY,
            160
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
                if (buffer == null) {
                    return false
                }
                val byteArray = ByteArray(buffer.remaining())
                buffer[byteArray]
                buffer.rewind()
                mRtcMessageListener?.onRecordAudioFrame(byteArray)
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

    fun joinChannel(
        rtcToken: String,
        channelId: String,
        uid: Int,
        roleType: Int,
    ): Int {
        Log.d(
            TAG,
            "joinChannel channelId:$channelId roleType:$roleType "
        )
        if (mRtcEngine == null) {
            Log.e(TAG, "joinChannel error: not initialized")
            return io.agora.rtc2.Constants.ERR_NOT_INITIALIZED
        }
        try {
            registerAudioFrame()

            val ret = mRtcEngine?.joinChannel(
                rtcToken,
                channelId,
                uid,
                object : ChannelMediaOptions() {
                    init {
                        autoSubscribeAudio = true
                        publishMicrophoneTrack = true
                        clientRoleType = roleType
                    }
                })
            Log.d(
                TAG, "joinChannel ret:$ret"
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(
                TAG, "joinChannel error:" + e.message
            )
            return io.agora.rtc2.Constants.ERR_FAILED
        }
        return io.agora.rtc2.Constants.ERR_OK
    }

    fun leaveChannel(): Int {
        Log.d(TAG, "leaveChannel")
        if (mRtcEngine == null) {
            Log.e(TAG, "leaveChannel error: not initialized")
            return io.agora.rtc2.Constants.ERR_NOT_INITIALIZED
        }
        try {
            mRtcEngine?.leaveChannel()
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(
                TAG, "leaveChannel error:" + e.message
            )
            return io.agora.rtc2.Constants.ERR_FAILED
        }

        return io.agora.rtc2.Constants.ERR_OK
    }

    fun sendAudioMetadata(metadata: ByteArray): Int {
        Log.d(TAG, "sendAudioMetadata metadata:${String(metadata)} size:${metadata.size}")
        if (mRtcEngine == null) {
            Log.e(TAG, "leaveChannel error: not initialized")
            return io.agora.rtc2.Constants.ERR_NOT_INITIALIZED
        }
        val ret = mRtcEngine?.sendAudioMetadata(metadata)
        return if (ret == 0) {
            io.agora.rtc2.Constants.ERR_OK
        } else {
            Log.e(TAG, "sendAudioMetadata error:$ret")
            io.agora.rtc2.Constants.ERR_FAILED
        }
    }

    fun getRtcVersion(): String {
        return RtcEngine.getSdkVersion()
    }

    fun destroy() {
        RtcEngine.destroy()
        mRtcEngine = null
        Log.d(TAG, "rtc destroy")
    }
}