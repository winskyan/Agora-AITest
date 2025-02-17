# Agora AITest

## 运行 Example

### 配置 Key

在 `local.properties` 文件中配置以下信息：

```properties
APP_ID=你的应用ID
APP_CERTIFICATE=你的证书密钥
```

### 运行

在 Android Studio 中打开项目，选择 `app` 模块并运行。

## MaaSEngine 使用指南

`MaaSEngine` 是一个抽象类，提供了视频、音频和消息处理的接口。本文将介绍如何使用该类及其方法。

### 初始化 MaaSEngine

在使用 `MaaSEngine` 之前，需要先创建并初始化一个 `MaaSEngine` 实例：

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

### 加入和离开频道

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

### 视频操作

#### 开始和停止视频

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

#### 设置视频编码配置

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

#### 设置远程视频

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

#### 切换摄像头

```kotlin
val switchCameraResult = maasEngine?.switchCamera()
if (switchCameraResult == 0) {
    // 切换摄像头成功
} else {
    // 切换摄像头失败
}
```

#### 添加和清除视频水印

```kotlin
val watermarkOptions = WatermarkOptions().apply {
    positionInPortraitMode = WatermarkOptions.Rectangle(0, 0, 200, 200)
    positionInLandscapeMode = WatermarkOptions.Rectangle(0, 0, 200, 200)
    visibleInPreview = true
}

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

### 音频操作

#### 启用和禁用音频

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

#### 调整音量

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

### 消息操作

#### 发送文本消息

```kotlin
val textMessage = "Hello, World!"
val sendTextResult = maasEngine?.sendText(textMessage)
if (sendTextResult == 0) {
    // 发送文本消息成功
} else {
    // 发送文本消息失败
}
```

#### 发送 RTM 消息

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

#### 发送音频元数据

```kotlin
val audioMetadata = byteArrayOf(1, 2, 3, 4, 5)
val sendAudioMetadataResult = maasEngine?.sendAudioMetadata(audioMetadata)
if (sendAudioMetadataResult == 0) {
    // 发送音频元数据成功
} else {
    // 发送音频元数据失败
}
```

### 获取 SDK 版本

```kotlin
val sdkVersion = MaaSEngine.getSdkVersion()
println("SDK Version: $sdkVersion")
```

### 销毁 MaaSEngine 实例

```kotlin
MaaSEngine.destroy()
```

通过以上步骤，你可以轻松地使用 `MaaSEngine` 进行视频、音频和消息的处理。