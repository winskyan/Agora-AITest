package io.agora.ai.test.maas.internal.utils

import io.agora.ai.test.maas.model.WatermarkOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.internal.EncryptionConfig
import io.agora.rtc2.video.VideoEncoderConfiguration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Utils {
    private val HEX_ARRAY: CharArray = "0123456789ABCDEF".toCharArray()

    fun getRtcFrameRate(frameRate: Int): VideoEncoderConfiguration.FRAME_RATE {
        return when (frameRate) {
            1 -> VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_1
            7 -> VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_7
            10 -> VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_10
            15 -> VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_15
            24 -> VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_24
            30 -> VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_30
            60 -> VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_60
            else -> VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_15
        }
    }

    fun getRtcOrientationMode(mode: Int): VideoEncoderConfiguration.ORIENTATION_MODE {
        return when (mode) {
            0 -> VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_ADAPTIVE
            1 -> VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_FIXED_LANDSCAPE
            2 -> VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_FIXED_PORTRAIT
            else -> VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_ADAPTIVE
        }
    }

    fun getRtcVideoModulePosition(position: Int): Constants.VideoModulePosition {
        return when (position) {
            1 -> Constants.VideoModulePosition.VIDEO_MODULE_POSITION_POST_CAPTURER
            2 -> Constants.VideoModulePosition.VIDEO_MODULE_POSITION_PRE_RENDERER
            4 -> Constants.VideoModulePosition.VIDEO_MODULE_POSITION_PRE_ENCODER
            8 -> Constants.VideoModulePosition.VIDEO_MODULE_POSITION_POST_CAPTURER_ORIGIN
            else -> Constants.VideoModulePosition.VIDEO_MODULE_POSITION_POST_CAPTURER
        }
    }

    fun getRtcWatermarkOptions(options: WatermarkOptions): io.agora.rtc2.video.WatermarkOptions {
        val rtcOptions = io.agora.rtc2.video.WatermarkOptions()
        rtcOptions.visibleInPreview = options.visibleInPreview
        rtcOptions.positionInLandscapeMode = io.agora.rtc2.video.WatermarkOptions.Rectangle(
            options.positionInLandscapeMode.x,
            options.positionInLandscapeMode.y,
            options.positionInLandscapeMode.width,
            options.positionInLandscapeMode.height
        )
        rtcOptions.positionInPortraitMode = io.agora.rtc2.video.WatermarkOptions.Rectangle(
            options.positionInPortraitMode.x,
            options.positionInPortraitMode.y,
            options.positionInPortraitMode.width,
            options.positionInPortraitMode.height
        )
        return rtcOptions
    }

    fun getEncryptionMode(mode: Int): EncryptionConfig.EncryptionMode {
        return when (mode) {
            1 -> EncryptionConfig.EncryptionMode.AES_128_XTS
            2 -> EncryptionConfig.EncryptionMode.AES_128_ECB
            3 -> EncryptionConfig.EncryptionMode.AES_256_XTS
            4 -> EncryptionConfig.EncryptionMode.SM4_128_ECB
            5 -> EncryptionConfig.EncryptionMode.AES_128_GCM
            6 -> EncryptionConfig.EncryptionMode.AES_256_GCM
            7 -> EncryptionConfig.EncryptionMode.AES_128_GCM2
            8 -> EncryptionConfig.EncryptionMode.AES_256_GCM2
            else -> EncryptionConfig.EncryptionMode.MODE_END
        }
    }

    fun bytesToHex(bytes: ByteArray?): String {
        if (bytes == null) {
            return "null"
        }
        val hexChars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = HEX_ARRAY[v ushr 4]
            hexChars[i * 2 + 1] = HEX_ARRAY[v and 0x0F]
        }
        return kotlin.text.String(hexChars)
    }

    fun getCurrentDateStr(pattern: String): String {
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
    }
}