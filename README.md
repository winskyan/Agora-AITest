# MaaSEngine 使用指南

`MaaSEngine` 是一个抽象类，提供了一系列用于视频和音频处理的接口。本文档将介绍如何使用该类及其方法。

## 目录

- [初始化 MaaSEngine](#初始化-maasengine)
- [加入和离开频道](#加入和离开频道)
- [视频操作](#视频操作)
    - [开始和停止视频](#开始和停止视频)
    - [设置视频编码配置](#设置视频编码配置)
    - [设置远程视频](#设置远程视频)
    - [切换摄像头](#切换摄像头)
    - [添加和清除视频水印](#添加和清除视频水印)
- [音频操作](#音频操作)
    - [启用和禁用音频](#启用和禁用音频)
    - [调整音量](#调整音量)
- [消息操作](#消息操作)
    - [发送文本消息](#发送文本消息)
    - [发送 RTM 消息](#发送-rtm-消息)
    - [发送音频元数据](#发送音频元数据)
- [获取 SDK 版本](#获取-sdk-版本)
- [销毁 MaaSEngine 实例](#销毁-maasengine-实例)

## 初始化 MaaSEngine

在使用 `MaaSEngine` 之前，需要先创建并初始化一个 `MaaSEngine` 实例。

使用前需在 `local.properties` 中配置 KEY 参数：

```properties
APP_CERTIFICATE=你的证书密钥
APP_ID=你的应用ID
```

```kotlin
val maasEngine = MaaSEngine.create()
val configuration = MaaSEngineConfiguration(/* 配置参数 */)
val result = maasEngine?.initialize(configuration)
if (result == 0) {
    // 初始化成功
} else {
    // 初始化失败
}
```

## 加入和离开频道

```kotlin
val channelId = "your_channel_id"
val joinResult = maasEngine?.joinChannel(channelId)
if (joinResult == 0) {
    // 加入频道成功
} else {
    // 加入频道失败
}

val leaveResult = maasEngine?.leaveChannel()
if (leaveResult == 0) {
    // 离开频道成功
} else {
    // 离开频道失败
}
```

## 视频操作

### 开始和停止视频

```kotlin
val view: View? = /* 视频显示的视图 */
val renderMode: MaaSConstants.RenderMode? = /* 渲染模式 */
val position = MaaSConstants.VideoModulePosition.VIDEO_MODULE_POSITION_POST_CAPTURER

val startVideoResult = maasEngine?.startVideo(view, renderMode, position)
if (startVideoResult == 0) {
    // 开始视频成功
} else {
    // 开始视频失败
}

val stopVideoResult = maasEngine?.stopVideo()
if (stopVideoResult == 0) {
    // 停止视频成功
} else {
    // 停止视频失败
}
```

### 设置视频编码配置

```kotlin
val width = 1280
val height = 720
val frameRate = MaaSConstants.FrameRate.FPS_30
val orientationMode = MaaSConstants.OrientationMode.ORIENTATION_MODE_FIXED_LANDSCAPE
val enableMirrorMode = true

val setVideoEncoderConfigResult = maasEngine?.setVideoEncoderConfiguration(
    width, height, frameRate, orientationMode, enableMirrorMode
)
if (setVideoEncoderConfigResult == 0) {
    // 设置视频编码配置成功
} else {
    // 设置视频编码配置失败
}
```

### 设置远程视频

```kotlin
val remoteView: View? = /* 远程视频显示的视图 */
val remoteRenderMode: MaaSConstants.RenderMode? = /* 远程渲染模式 */
val remoteUid = 12345

val setupRemoteVideoResult = maasEngine?.setupRemoteVideo(remoteView, remoteRenderMode, remoteUid)
if (setupRemoteVideoResult == 0) {
    // 设置远程视频成功
} else {
    // 设置远程视频失败
}
```

### 切换摄像头

```kotlin
val switchCameraResult = maasEngine?.switchCamera()
if (switchCameraResult == 0) {
    // 切换摄像头成功
} else {
    // 切换摄像头失败
}
```

### 添加和清除视频水印

```kotlin
val watermarkOptions = WatermarkOptions()
val width = 200
val height = 200
watermarkOptions.positionInPortraitMode =
    WatermarkOptions.Rectangle(0, 0, width, height)
watermarkOptions.positionInLandscapeMode =
    WatermarkOptions.Rectangle(0, 0, width, height)
watermarkOptions.visibleInPreview = true

val rootView = window.decorView.rootView
val screenBuffer = captureScreenToByteBuffer(rootView)

val addWatermarkResult = maasEngine?.addVideoWatermark(
    screenBuffer,
    rootView.width,
    rootView.height,
    MaaSConstants.VideoFormat.VIDEO_PIXEL_RGBA,
    watermarkOptions
)
if (addWatermarkResult == 0) {
    // 添加水印成功
} else {
    // 添加水印失败
}

val clearWatermarksResult = maasEngine?.clearVideoWatermarks()
if (clearWatermarksResult == 0) {
    // 清除水印成功
} else {
    // 清除水印失败
}
```

## 音频操作

### 启用和禁用音频

```kotlin
val enableAudioResult = maasEngine?.enableAudio()
if (enableAudioResult == 0) {
    // 启用音频成功
} else {
    // 启用音频失败
}

val disableAudioResult = maasEngine?.disableAudio()
if (disableAudioResult == 0) {
    // 禁用音频成功
} else {
    // 禁用音频失败
}
```

### 调整音量

```kotlin
val playbackVolume = 50
val adjustPlaybackVolumeResult = maasEngine?.adjustPlaybackSignalVolume(playbackVolume)
if (adjustPlaybackVolumeResult == 0) {
    // 调整播放音量成功
} else {
    // 调整播放音量失败
}

val recordingVolume = 50
val adjustRecordingVolumeResult = maasEngine?.adjustRecordingSignalVolume(recordingVolume)
if (adjustRecordingVolumeResult == 0) {
    // 调整录音音量成功
} else {
    // 调整录音音量失败
}
```

## 消息操作

### 发送文本消息

```kotlin
val textMessage = "Hello, World!"
val sendTextResult = maasEngine?.sendText(textMessage)
if (sendTextResult == 0) {
    // 发送文本消息成功
} else {
    // 发送文本消息失败
}
```

### 发送 RTM 消息

```kotlin
val rtmMessage = "RTM Message"
val channelType = MaaSConstants.RtmChannelType.MESSAGE
val sendRtmMessageResult = maasEngine?.sendRtmMessage(rtmMessage.toByteArray(), channelType)
if (sendRtmMessageResult == 0) {
    // 发送 RTM 消息成功
} else {
    // 发送 RTM 消息失败
}
```

### 发送音频元数据

```kotlin
val audioMetadata = byteArrayOf(1, 2, 3, 4, 5)
val sendAudioMetadataResult = maasEngine?.sendAudioMetadata(audioMetadata)
if (sendAudioMetadataResult == 0) {
    // 发送音频元数据成功
} else {
    // 发送音频元数据失败
}
```

## 获取 SDK 版本

```kotlin
val sdkVersion = MaaSEngine.getSdkVersion()
println("SDK Version: $sdkVersion")
```

## 销毁 MaaSEngine 实例

```kotlin
MaaSEngine.destroy()
```

## 完整代码示例

```kotlin
import android.view.View
import androidx.annotation.Keep
import io.agora.ai.test.maas.internal.MaaSEngineInternal
import io.agora.ai.test.maas.model.MaaSEngineConfiguration
import io.agora.ai.test.maas.model.WatermarkOptions
import java.nio.ByteBuffer

abstract class MaaSEngine {
    abstract fun initialize(configuration: MaaSEngineConfiguration): Int

    abstract fun joinChannel(
        channelId: String,
        roleType: Int = MaaSConstants.CLIENT_ROLE_BROADCASTER,
        registerRecordingAudio: Boolean = false,
        registerPlaybackAudio: Boolean = false
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
            return BuildConfig.VERSION_NAME
        }
    }
}
```