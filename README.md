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

### 生成本地推送 PTS

当通过自定义音轨推送本地 PCM 时，使用
`AudioFrameManager.generatePts(data, sampleRate, channels, isSessionEnd)` 生成
PTS。

- 参数说明：
  - `data`：当前帧的 PCM 字节数组（16-bit PCM）
  - `sampleRate`：采样率（Hz，如 48000）
  - `channels`：通道数（如 1 表示单声道）
  - `isSessionEnd`：是否为当前会话的最后一帧

- 位分布（MSB → LSB）：
  - 3 位 `version`：固定为 1
  - 18 位 `sessionId`：内部计数，每次调用自增，超过 `0x3FFFF` 回到 1
  - 10 位 `last_chunk_duration_ms`：仅当 `isSessionEnd=true` 时写入 `durationMs & 0x3FF`，其余为 0
  - 1 位 `isSessionEnd`
  - 32 位 `basePts`：32 位滚动累计计数，每次累加 `durationMs` 后按 `2^32` 回绕；会话结束后重置为 0

- 推送示例：

```kotlin
val pts = AudioFrameManager.generatePts(data, sampleRate, channels, isLastFrame)
rtcEngine.pushExternalAudioFrame(
    data,
    pts,
    sampleRate,
    channels,
    Constants.BytesPerSample.TWO_BYTES_PER_SAMPLE,
    customAudioTrackId
)
```

提示：PTS 为 64 位整型，最高位用于 version，十进制可能显示为负数。建议用十六进制查看：
`String.format("0x%016X", pts)` 或用无符号：`pts.toULong().toString()`。

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
对话等场景）。新版实现不再需要业务侧进行句元登记，而是直接按照 `generatePts` 的位域协议在
`processAudioFrame` 中解析（当 `version=1` 时）。

术语说明：

- session：一轮对话，从开始到结束的完整交互过程
- sentence：一个句子，属于某个 session 内的单句 TTS 音频
- chunk：句子中的一段音频分片（子块），属于某个 sentence
- isSessionEnd：是否为该 session 的结束标记

当 `version=1` 时，`pts` 位分布如下（与 `generatePts` 一致）：
3 位 `version` | 18 位 `sessionId` | 10 位 `last_chunk_duration_ms` | 1 位 `isSessionEnd` | 32 位 `basePts`。

- **工作机制（简述）**：
  - 解析 `pts` 位域获取 `sessionId`、`last_chunk_duration_ms` 与 `isSessionEnd`。
  - 若 `sessionId` 发生变化，立即回调上一轮会话结束（`isSessionEnd=true`）。
  - 若 `isSessionEnd=true` 且 `last_chunk_duration_ms>0`，累计已接收 PCM 时长，达到该时长后立即回调会话结束。
  - 静默超时：若 `isSessionEnd=true` 使用 200ms 超时；否则使用 500ms 超时。超时后回调会话结束。
  - 当前实现中 `sentenceId` 与 `chunkId` 固定为 1，仅作为回调占位。

- **常量（可按需调整源码内取值）**：
  - `PLAYBACK_AUDIO_FRAME_MAX_TIMEOUT_MS = 500`：普通帧静默超时上限（毫秒）。
  - `PLAYBACK_AUDIO_FRAME_MIN_TIMEOUT_MS = 200`：标记为会话最后一帧（`isSessionEnd=true`）时使用的更短静默超时（毫秒）。

### API 一览

- `AudioFrameManager.init(callback: ICallback)`：初始化并注册回调
  - 参数：
    - `callback`：收到“句末/轮末”事件时的回调实例

- `ICallback.onSentenceEnd(sessionId: Int, sentenceId: Int, chunkId: Int, isSessionEnd: Boolean)`
  ：结束回调；
  - `isSessionEnd=false`：一句话结束（同一 `sessionId` 内 `sentenceId` 变化时立即回调）。
  - `isSessionEnd=true`：一轮会话结束（`sessionId` 变化时立即回调，或静默超时后回调；静默阈值：最后一帧
      `isEnd=1` 时 200ms，否则 500ms）。

- `AudioFrameManager.processAudioFrame(data: ByteArray, sampleRate: Int, channels: Int, pts: Long)`：
  输入远端 PCM 帧及其 PTS，用于进行结束判定。
  - 参数：
    - `data`：当前帧的 PCM 字节数组
    - `sampleRate`：采样率（Hz）
    - `channels`：通道数
    - `pts`：当前帧携带的 64 位 PTS；当 `version=1` 时按位域解析（与 `generatePts` 一致）
  - 当 `version=1`：3 位 `version` | 18 位 `sessionId` | 10 位 `last_chunk_duration_ms` | 1 位 `isSessionEnd` | 32 位 `basePts`。

-

`AudioFrameManager.generatePts(data: ByteArray, sampleRate: Int, channels: Int, isSessionEnd: Boolean)`
：生成 PTS（遵循 v1 位分布），
`last_chunk_duration_ms` 仅在最后一帧写入；`basePts` 为 32 位滚动累计计数。

- 参数：
- `data`：当前帧 PCM 字节数组（16-bit PCM）
- `sampleRate`：采样率（Hz）
- `channels`：通道数
- `isSessionEnd`：是否为当前会话的最后一帧
- 内部行为：
- `durationMs = data.size / ((sampleRate * channels * 2) / 1000)`
- `basePts = (basePts + durationMs) & 0xFFFF_FFFF`；若为最后一帧，还会写入
`last_chunk_duration_ms = durationMs & 0x3FF`

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
    AudioFrameManager.processAudioFrame(bytes, samplesPerSec, channels, pts)
}

// 本地推送自定义音频时生成 PTS
val pts = AudioFrameManager.generatePts(frameBytes, sampleRate, channels, isLastFrame)

// 3) 退出或销毁时
AudioFrameManager.release()
```

### 注意事项与调优建议

- 确保 `pts` 正确按位域编码；乱序或回退的帧建议丢弃。
- 如需更灵敏/更稳健的结束判定，可按业务场景调整：
  - 合理调整 `PLAYBACK_AUDIO_FRAME_MAX_TIMEOUT_MS` 与 `PLAYBACK_AUDIO_FRAME_MIN_TIMEOUT_MS`
      以平衡响应速度与稳健性。

### 额外行为说明

- 会话切换（`sessionId` 变化）：立即回调上一个会话的结束（`isSessionEnd=true`），随后开始追踪新会话。
- 句子切换（同一 `sessionId` 下 `sentenceId` 变化）：立即回调上一句结束（`isSessionEnd=false`）。当前实现中 `sentenceId` 固定为 1，此分支不会触发。

与本项目的关系：

- 已在 `RtcManager.initialize` 中调用 `AudioFrameManager.init(...)`
- 已在 `onPlaybackAudioFrameBeforeMixing(...)` 中调用 `processAudioFrame(data, samplesPerSec, channels, pts)`
- 已在 `RtcManager.destroy` 中调用 `AudioFrameManager.release()`
