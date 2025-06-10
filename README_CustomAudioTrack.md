# RTC Engine 自定义音频轨道使用指南

本文档介绍如何使用 Agora RTC SDK 的原生接口实现自定义音频轨道功能，通过 `createCustomAudioTrack` 创建 `AUDIO_TRACK_DIRECT` 类型的轨道，并使用 `pushExternalAudioFrame` 推送自采集音频数据到频道。

## 功能概述

自定义音频轨道允许开发者：

- 推送外部音频数据而非麦克风采集的音频
- 完全控制音频数据的来源和处理
- 实现音频文件播放、TTS音频等特殊场景

## 核心API使用步骤

### 1. 初始化RTC Engine后设置音频配置

创建RTC Engine后，设置音频Profile和Scenario：

```kotlin
// 设置音频Profile和Scenario
rtcEngine.setAudioProfile(Constants.AUDIO_PROFILE_DEFAULT)
rtcEngine.setAudioScenario(Constants.AUDIO_SCENARIO_AI_CLIENT)
```

### 2. 设置AI QoS优化参数

开启AI QoS相关的私有参数优化：

```kotlin
// 开启AI QoS优化参数
rtcEngine.setParameters("{\"che.audio.aec.split_srate_for_48k\":16000}")
rtcEngine.setParameters("{\"che.audio.sf.enabled\":true}")
rtcEngine.setParameters("{\"che.audio.sf.stftType\":6}")
rtcEngine.setParameters("{\"che.audio.sf.ainlpLowLatencyFlag\":1}")
rtcEngine.setParameters("{\"che.audio.sf.ainsLowLatencyFlag \":1}")
rtcEngine.setParameters("{\"che.audio.sf.procChainMode\":1}")
rtcEngine.setParameters("{\"che.audio.sf.nlpDynamicMode\":1}")
rtcEngine.setParameters("{\"che.audio.sf.nlpAlgRoute\":0}")
rtcEngine.setParameters("{\"che.audio.sf.ainlpModelPref\":10}")
rtcEngine.setParameters("{\"che.audio.sf.nsngAlgRoute\":12}")
rtcEngine.setParameters("{\"che.audio.sf.ainsModelPref\":10}")
rtcEngine.setParameters("{\"che.audio.sf.nsngPredefAgg\":11}")
rtcEngine.setParameters("{\"che.audio.agc.enable\":false}")
```

### 3. 创建自定义音频轨道

使用 RTC Engine 创建 DIRECT 类型的自定义音频轨道：

```kotlin
// 配置音频轨道参数
val audioTrackConfig = AudioTrackConfig()
audioTrackConfig.enableLocalPlayback = false      // 禁用本地播放
audioTrackConfig.enableAudioProcessing = false    // 禁用音频处理

// 创建自定义音频轨道
val customAudioTrackId = rtcEngine.createCustomAudioTrack(
    Constants.AudioTrackType.AUDIO_TRACK_DIRECT,   // 使用DIRECT类型
    audioTrackConfig
)
```

### 4. 配置频道媒体选项

加入频道时配置发布自定义音频轨道：

```kotlin
val channelMediaOptions = ChannelMediaOptions()
channelMediaOptions.publishCustomAudioTrack = true              // 发布自定义音频轨道
channelMediaOptions.publishCustomAudioTrackId = customAudioTrackId  // 指定轨道ID
channelMediaOptions.publishMicrophoneTrack = false              // 关闭麦克风轨道

// 加入频道
rtcEngine.joinChannel(token, channelName, uid, channelMediaOptions)
```

### 5. 推送外部音频数据

使用 `pushExternalAudioFrame` 推送音频数据：

```kotlin
fun pushAudioData(
    audioData: ByteArray,
    timestamp: Long,
    sampleRate: Int,
    channels: Int,
    customTrackId: Int
): Int {
    return rtcEngine.pushExternalAudioFrame(
        audioData,                                      // PCM音频数据
        timestamp,                                      // 时间戳(ms)
        sampleRate,                                     // 采样率
        channels,                                       // 声道数
        Constants.BytesPerSample.TWO_BYTES_PER_SAMPLE, // 固定使用2字节
        customTrackId                                   // 自定义轨道ID
    )
}

```

## 使用示例

### 示例1：推送单个音频文件

```kotlin
读取音频文件数据：
val audioData = readAudioFile("sample.pcm")  // 48kHz, 单声道, 16bit PCM

推送音频数据：
val result = rtcEngine.pushExternalAudioFrame(
    audioData,                                      // 音频数据
    System.currentTimeMillis(),                     // 当前时间戳
    48000,                                          // 48kHz采样率
    1,                                              // 单声道
    Constants.BytesPerSample.TWO_BYTES_PER_SAMPLE, // 2字节每样本
    customAudioTrackId                              // 自定义轨道ID
)

检查结果：
if (result == 0) {
    // 推送成功
} else {
    // 推送失败，处理错误
}
```

### 示例2：连续推送音频流

```kotlin
设置音频参数：
val sampleRate = 48000
val channels = 1
val bufferSize = sampleRate *channels* 2 / 100  // 10ms音频数据

创建定时推送任务：
val audioHandler = Handler(Looper.getMainLooper())
val audioRunnable = object : Runnable {
    override fun run() {
        // 获取音频数据（这里需要根据实际情况实现）
        val audioBuffer = getNextAudioBuffer(bufferSize)

        if (audioBuffer != null) {
            rtcEngine.pushExternalAudioFrame(
                audioBuffer,
                System.currentTimeMillis(),
                sampleRate,
                channels,
                Constants.BytesPerSample.TWO_BYTES_PER_SAMPLE,
                customAudioTrackId
            )
        }
        
        // 每10ms推送一次
        audioHandler.postDelayed(this, 10)
    }
}

开始推送：
audioHandler.post(audioRunnable)

```

## 重要参数说明

### AudioTrackConfig 配置

- `enableLocalPlayback`: 是否启用本地播放
- `enableAudioProcessing`: 是否启用音频处理

### pushExternalAudioFrame 参数详解

```kotlin
rtcEngine.pushExternalAudioFrame(
    data,           // ByteArray: PCM格式的音频数据字节数组
    timestamp,      // Long: 音频帧时间戳(毫秒)，可使用 System.currentTimeMillis()
    sampleRate,     // Int: 采样率
    channels,       // Int: 声道数，1为单声道，2为立体声
    bytesPerSample, // BytesPerSample: 固定使用 TWO_BYTES_PER_SAMPLE
    trackId         // Int: 自定义音频轨道ID
)
```

## 资源管理

### 销毁自定义音频轨道

```kotlin
// 销毁自定义音频轨道
if (customAudioTrackId != 0) {
    rtcEngine.destroyCustomAudioTrack(customAudioTrackId)
}
```

### 完整的生命周期管理

```kotlin
class AudioTrackManager {
    private var rtcEngine: RtcEngine? = null
    private var customAudioTrackId: Int = 0
    
    fun initialize() {
        // 创建自定义音频轨道
        val config = AudioTrackConfig()
        config.enableLocalPlayback = false
        config.enableAudioProcessing = false
        customAudioTrackId = rtcEngine?.createCustomAudioTrack(
            Constants.AudioTrackType.AUDIO_TRACK_DIRECT,
            config
        ) ?: 0
    }
    
    fun destroy() {
        // 销毁自定义音频轨道
        if (customAudioTrackId != 0) {
            rtcEngine?.destroyCustomAudioTrack(customAudioTrackId)
            customAudioTrackId = 0
        }
    }
}
```

## 相关API参考

### 核心接口

- RtcEngine.createCustomAudioTrack(): 创建自定义音频轨道
- RtcEngine.destroyCustomAudioTrack(): 销毁自定义音频轨道  
- RtcEngine.pushExternalAudioFrame(): 推送外部音频帧

### 配置类

- AudioTrackConfig: 音频轨道配置
- ChannelMediaOptions: 频道媒体选项配置
- Constants.AudioTrackType: 音频轨道类型枚举
- Constants.BytesPerSample: 样本字节数枚举
