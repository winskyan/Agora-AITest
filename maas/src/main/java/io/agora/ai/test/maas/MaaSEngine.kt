package io.agora.ai.test.maas

import android.view.View
import androidx.annotation.Keep
import io.agora.ai.test.maas.internal.MaaSEngineInternal
import io.agora.ai.test.maas.model.JoinChannelConfig
import io.agora.ai.test.maas.model.MaaSEngineConfiguration
import io.agora.ai.test.maas.model.WatermarkOptions
import io.agora.rtc2.RtcEngine
import java.nio.ByteBuffer

abstract class MaaSEngine {
    abstract fun initialize(configuration: MaaSEngineConfiguration): Int

    abstract fun joinChannel(
        channelId: String,
        roleType: Int = MaaSConstants.CLIENT_ROLE_BROADCASTER,
        joinChannelConfig: JoinChannelConfig
    ): Int

    abstract fun leaveChannel(): Int
    abstract fun startVideo(
        view: View?,
        renderMode: MaaSConstants.RenderMode?,
        position: MaaSConstants.VideoModulePosition = MaaSConstants.VideoModulePosition.VIDEO_MODULE_POSITION_POST_CAPTURER
    ): Int

    abstract fun stopVideo(): Int
    abstract fun setVideoEncoderConfiguration(
        width: Int,
        height: Int,
        frameRate: MaaSConstants.FrameRate,
        orientationMode: MaaSConstants.OrientationMode,
        enableMirrorMode: Boolean
    ): Int

    abstract fun setupRemoteVideo(
        view: View?,
        renderMode: MaaSConstants.RenderMode?,
        remoteUid: Int
    ): Int

    abstract fun switchCamera(): Int

    abstract fun addVideoWatermark(watermarkUrl: String, watermarkOptions: WatermarkOptions): Int

    abstract fun addVideoWatermark(
        data: ByteBuffer,
        width: Int,
        height: Int,
        format: MaaSConstants.VideoFormat,
        options: WatermarkOptions
    ): Int

    abstract fun clearVideoWatermarks(): Int

    abstract fun enableAudio(): Int

    abstract fun disableAudio(): Int

    abstract fun adjustPlaybackSignalVolume(volume: Int): Int

    abstract fun adjustRecordingSignalVolume(volume: Int): Int

    abstract fun sendText(text: String): Int

    abstract fun sendAudioMetadata(metadata: ByteArray): Int

    abstract fun sendRtmMessage(message: ByteArray, channelType: MaaSConstants.RtmChannelType): Int
    abstract fun pushVideoFrame(
        data: ByteArray,
        width: Int,
        height: Int,
        type: MaaSConstants.ViewFrameType
    ): Int

    abstract fun pushExternalAudioFrame(
        data: ByteArray,
        timestamp: Long,
        sampleRate: Int,
        channels: Int,
        bytesPerSample: Int
    ): Int

    protected abstract fun doDestroy()

    companion object {
        @JvmStatic
        private var mInstance: MaaSEngine? = null

        @JvmStatic
        @Synchronized
        @Keep
        fun create(): MaaSEngine? {
            if (mInstance == null) {
                mInstance = MaaSEngineInternal()
            }
            return mInstance
        }

        @JvmStatic
        @Synchronized
        @Keep
        fun destroy() {
            mInstance?.doDestroy()
            mInstance = null
        }

        @JvmStatic
        @Synchronized
        @Keep
        fun getSdkVersion(): String {
            return RtcEngine.getSdkVersion()
        }
    }
}