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

## 实现 Burst 模式

以下步骤与项目中 `RtcManager`/`MainActivity` 的实现一一对应，展示如何启用并使用 Burst 模式进行自定义音频推流与远端音频帧监听。

1) 初始化引擎 `initialize`

- 创建 `RtcEngine` 并设置基础属性：
  - 频道场景：`CHANNEL_PROFILE_LIVE_BROADCASTING`
  - 音频 Profile/场景：`AUDIO_PROFILE_DEFAULT`、`AUDIO_SCENARIO_AI_CLIENT`
- 关键参数（启用 Burst 及优化时延）：
  - `{"rtc.enable_debug_log":true}` 打开调试日志
  - `{"che.audio.get_burst_mode":true}` 启用 burst 模式
  - `{"che.audio.neteq.max_wait_first_decode_ms":0}`、`{"che.audio.neteq.max_wait_ms":0}`

2) 注册音频帧观察者 `registerAudioFrame`

- 设置回调 `onPlaybackAudioFrameBeforeMixing(...)` 用于在混音前拿到远端用户的 PCM 帧
- 可结合内部保存逻辑将帧写入文件，便于分析

3) 创建直通自定义音轨 `createCustomAudioTrack(AUDIO_TRACK_DIRECT)`

- 通过 `AudioTrackConfig` 关闭本地回放与内置处理：
  - `enableLocalPlayback = false`
  - `enableAudioProcessing = false`
- 记录返回的 `mCustomAudioTrackId`

4) 配置并加入频道 `joinChannelEx`

- 构造 `ChannelMediaOptions`：
  - `autoSubscribeAudio = true`、`autoSubscribeVideo = false`
  - `publishCustomAudioTrack = true`、`publishCustomAudioTrackId = mCustomAudioTrackId`
  - `publishMicrophoneTrack = false`（禁用麦克风，改用自定义音轨）
  - `enableAudioRecordingOrPlayout = false`
- 使用 `RtcEngineEx.joinChannelEx(token, RtcConnection(channelId, uid), options, rtcEventHandler)` 入频道

5) 准备音频源并推送帧 `pushExternalAudioFrame`

- 保证与源数据一致的参数：采样率（如 48000）、通道数（如 1）、采样字节数（2 字节/16bit）
- 支持一次性推送整块 PCM，或使用 `AudioFileReader` 按固定帧间隔（如 10ms）循环读取并在回调中推送

6) 监听远端音频帧并保存（可选）

- 在 `onPlaybackAudioFrameBeforeMixing(...)` 回调中获取远端 PCM，按需保存为 `channel_uid_timestamp.pcm`

7) 离开频道 `leaveChannel`

- 调用离开逻辑，停止本地音频读取（若在运行）

8) 销毁资源 `destroy`

- 销毁自定义音轨 `destroyCustomAudioTrack(mCustomAudioTrackId)`
- `RtcEngine.destroy()` 并清理回调、连接与线程调度器

说明：上述步骤在本项目中分别由 `RtcManager.initialize`、`RtcManager.registerAudioFrame`（内部调用）、`RtcManager.createCustomAudioTrack`（内部方法 `initCustomAudioTracker`）、`RtcManager.joinChannelEx`、`RtcManager.pushExternalAudioFrame`、`RtcManager.leaveChannel`、`RtcManager.destroy` 具体实现；触发流程由 `MainActivity` 的按钮点击与回调驱动。

## AudioFrameManager 使用说明

`AudioFrameManager` 用于管理远端播放音频帧事件：通过对收到的远端 PCM 帧进行“静默超时”判定（默认 200ms），在一段音频播放结束时回调上层，适合 TTS/AI 对话等需按“句”感知播放结束的场景。

- **核心能力**：
  - `init(callback)`：注册回调，开始管理会话与帧事件
  - `updateSession(sessionId, index, text)`：更新当前会话标识（例如一段 TTS 的 id），用于回调携带上下文
  - `processAudioFrame(data)`：喂入远端 PCM 帧（在音频回调中调用）；若超时未再收到帧则触发 `onSessionEnd`
  - `release()`：释放内部资源

- **最小接入示例（Kotlin）**：

```kotlin
// 1) 初始化（建议在 Rtc 初始化完成后）
val audioCallback = object : AudioFrameManager.ICallback {
    override fun onLineEnd(sessionId: String, index: Int) {
        // 可选：一行结束（若有行级别划分）
    }
    override fun onSessionEnd(sessionId: String) {
        // 一段音频播放结束（200ms 未再收到帧），可进行业务处理：切下一句/更新 UI 等
    }
}
AudioFrameManager.init(audioCallback)

// 2) 新的一段播放开始时（如一条 TTS 结果）更新会话信息
AudioFrameManager.updateSession(sessionId = "session-123", index = 0, text = "你好")

// 3) 在 onPlaybackAudioFrameBeforeMixing(...) 中喂入 PCM
buffer?.rewind()
val bytes = ByteArray(buffer?.remaining() ?: 0)
buffer?.get(bytes)
if (bytes.isNotEmpty()) {
    AudioFrameManager.processAudioFrame(bytes)
}

// 4) 退出或销毁时
AudioFrameManager.release()
```

- **与本项目的关系**：
  - 已在 `RtcManager.initialize` 中调用 `AudioFrameManager.init(...)`
  - 已在 `onPlaybackAudioFrameBeforeMixing(...)` 中调用 `processAudioFrame(...)`
  - 已在 `RtcManager.destroy` 中调用 `AudioFrameManager.release()`
  - 如需按业务维度感知“句/段”结束，仅需在开始播放新段时调用 `updateSession(...)`，其余逻辑已对接好
