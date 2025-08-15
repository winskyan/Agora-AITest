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

以下步骤与项目中 `RtcManager`/`MainActivity` 的实现一一对应，展示如何启用并使用 Burst
模式进行自定义音频推流与远端音频帧监听。

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
- 使用 `RtcEngineEx.joinChannelEx(token, RtcConnection(channelId, uid), options, rtcEventHandler)`
  入频道

5) 准备音频源并推送帧 `pushExternalAudioFrame`

- 保证与源数据一致的参数：采样率（如 48000）、通道数（如 1）、采样字节数（2 字节/16bit）
- 支持一次性推送整块 PCM，或使用 `AudioFileReader` 按固定帧间隔（如 10ms）循环读取并在回调中推送

6) 监听远端音频帧并保存（可选）

- 在 `onPlaybackAudioFrameBeforeMixing(...)` 回调中获取远端 PCM，按需保存为
  `channel_uid_timestamp.pcm`

7) 离开频道 `leaveChannel`

- 调用离开逻辑，停止本地音频读取（若在运行）

8) 销毁资源 `destroy`

- 销毁自定义音轨 `destroyCustomAudioTrack(mCustomAudioTrackId)`
- `RtcEngine.destroy()` 并清理回调、连接与线程调度器

说明：上述步骤在本项目中分别由 `RtcManager.initialize`、`RtcManager.registerAudioFrame`（内部调用）、
`RtcManager.createCustomAudioTrack`（内部方法 `initCustomAudioTracker`）、`RtcManager.joinChannelEx`、
`RtcManager.pushExternalAudioFrame`、`RtcManager.leaveChannel`、`RtcManager.destroy` 具体实现；触发流程由
`MainActivity` 的按钮点击与回调驱动。

## AudioFrameManager 使用说明

`AudioFrameManager` 用于“句级/轮次级”跟踪远端播放音频帧的结束时刻（TTS/AI
对话等场景）。新版实现不再需要业务侧进行句元登记，而是直接从 `processAudioFrame` 的 `pts` 位域中解析（当
`version=2` 时）。

术语说明：

- session：一轮对话，从开始到结束的完整交互过程
- sentence：一个句子，属于某个 session 内的单句 TTS 音频
- chunk：句子中的一段音频分片（子块），属于某个 sentence
- isSessionEnd：是否为该 session 的结束标记

当 `version=2` 时，`pts` 位分布如下：
高 4 位 `version`（不超过 0x7）| 16 位 `sessionId` | 16 位 `sentenceId` | 10 位 `chunkId` | 2 位
`isEnd` | 低 16 位 `basePts`。

- **工作机制（简述）**：
    - 直接解析 `pts` 位域获取 `sessionId`、`sentenceId`、`isEnd`；每条句子的 `isEnd` 在该句内是固定值，
      `isEnd=1` 表示该句是本轮最后一句。
    - 结束判定（基于静默超时）：
        - 若 `isEnd=0`，超过 200ms 未收到下一帧，则认为该 `sentenceId` 对应的一句话结束（
          `isSessionEnd=false`）。
        - 若 `isEnd=1`，超过 200ms 未收到下一帧，则认为该 `sessionId` 对应的一轮对话结束（
          `isSessionEnd=true`）。

- **常量（可按需调整源码内取值）**：
    - `PLAYBACK_AUDIO_FRAME_TIMEOUT_MS = 200`：静默超时阈值（毫秒）。

### API 一览

- `AudioFrameManager.init(callback: ICallback)`：初始化并注册回调。
  -
  `ICallback.onSentenceEnd(sessionId: Int, sentenceId: Int, chunkId: Int, isSessionEnd: Boolean)`
  ：结束回调；
  - `isSessionEnd=false`：一句话结束（同一 session 内 `sentenceId` 变化或 200ms 超时且`isEnd=0`）
  - `isSessionEnd=true`：一轮会话结束（`sessionId` 变化或 200ms 超时且 `isEnd=1`）

- `AudioFrameManager.processAudioFrame(data: ByteArray, pts: Long)`：输入远端 PCM 帧及其
  PTS，用于进行结束判定。
    - 当 `version=2`：高 4 位 `version` | 16 位 `sessionId` | 16 位 `sentenceId` | 10 位 `chunkId` |
      2 位 `isEnd` | 低 16 位 `basePts`。
    - 其他 `version` 将被忽略（当前实现仅处理 `version=2`）。

- `AudioFrameManager.release()`：释放内部资源与线程。

### 典型接入流程（Kotlin）

```kotlin
// 1) 注册回调
val audioCallback = object : AudioFrameManager.ICallback {
    override fun onSentenceEnd(
        sessionId: Int,
        sentenceId: Int,
        chunkId: Int,
        isSessionEnd: Boolean
    ) {
        // 一句或一轮播放结束：据 isSessionEnd 区分句末 or 轮末；chunkId 可用于定位最后一个分片
    }
}
AudioFrameManager.init(audioCallback)

// 2) 在 onPlaybackAudioFrameBeforeMixing(...) 回调中喂入 PCM 与 PTS
buffer?.rewind()
val bytes = ByteArray(buffer?.remaining() ?: 0)
buffer?.get(bytes)
if (bytes.isNotEmpty()) {
    // pts 使用引擎回调提供的时间戳，并按位域编码 sessionId/sentenceId/isEnd/basePts
    AudioFrameManager.processAudioFrame(bytes, pts)
}

// 3) 退出或销毁时
AudioFrameManager.release()
```

### 注意事项与调优建议

- 确保 `pts` 正确按位域编码；乱序或回退的帧建议丢弃。
- 如需更灵敏/更稳健的结束判定，可按业务场景调整：
    - 调小/调大 `PLAYBACK_AUDIO_FRAME_TIMEOUT_MS` 以平衡响应速度与稳健性。

### 额外行为说明

- 会话切换（`sessionId` 变化）：立即回调上一个会话的结束（`isSessionEnd=true`），随后开始追踪新会话。
- 句子切换（同一 `sessionId` 下 `sentenceId` 变化）：立即回调上一句结束（`isSessionEnd=false`），随后开始追踪新句。

与本项目的关系：

- 已在 `RtcManager.initialize` 中调用 `AudioFrameManager.init(...)`
- 已在 `onPlaybackAudioFrameBeforeMixing(...)` 中调用 `processAudioFrame(data, pts)`
- 已在 `RtcManager.destroy` 中调用 `AudioFrameManager.release()`
