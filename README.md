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

`AudioFrameManager` 用于“句级”跟踪远端播放音频帧的结束时刻，典型用于 TTS/AI 对话：当某句播放完毕（静默超时或 PTS 跳变）时，回调上层标识该句已结束。

- **工作机制（简述）**：
  - 句元信息登记：业务侧在开始播放一条句子前，通过 `updateSentence(...)` 或 `updateSentenceWithJson(...)` 登记该句的 `sentenceId`、`basePts`、`sentenceDataLength`（可选长度指标）与是否为轮次结尾 `isRoundEnd`。
  - 分桶定位：内部按 `SENTENCE_PTS_THRESHOLD_MS` 对 PTS 进行分桶，`basePts` 与音频帧的上一帧 `pts` 经相同阈值取整后映射到同一“句桶”。
  - 结束判定：
    - 静默超时：每次收到帧都会重置一个定时器（默认 200ms）。超时触发时，若能解析到上一帧所属句子，则回调结束。
    - PTS 跳变：若两帧之间的 PTS 跳变超过 `PLAYBACK_PTS_CHANGE_THRESHOLD_MS`（默认 1000ms），判定上一句结束并回调。

- **常量（可按需调整源码内取值）**：
  - `PLAYBACK_AUDIO_FRAME_TIMEOUT_MS = 200`：静默超时阈值（毫秒）。
  - `PLAYBACK_PTS_CHANGE_THRESHOLD_MS = 1000`：帧间 PTS 跳变判定阈值（毫秒）。
  - `SENTENCE_PTS_THRESHOLD_MS = 50000`：PTS 分桶阈值（毫秒）。`basePts` 与上一帧 `pts` 会按该阈值向下取整对齐。

### API 一览

- `AudioFrameManager.init(callback: ICallback)`：初始化并注册回调。
  - `ICallback.onSentenceEnd(sentenceId: String, isRoundEnd: Boolean)`：句子结束时回调；`isRoundEnd` 表示该句是否标志一轮会话的结束。

- `AudioFrameManager.updateSentence(
    sentenceId: String,
    basePts: Long,
    sentenceDataLength: Int,
    isRoundEnd: Boolean
  )`
  - 在句子开始播放前登记元信息。
  - 约束：`sentenceId` 不可为空；若同一 `basePts` 已存在，将忽略本次登记。
  - 建议：确保 `basePts` 与后续 `processAudioFrame(..., pts)` 中的 `pts` 采用同一时间基，并满足分桶后一致。

- `AudioFrameManager.updateSentenceWithJson(sentencePayloadJson: String)`：以 JSON 一次性传入句元信息。
  - JSON 示例：`{"sentenceId":"123","basePts":50000,"sentenceDataLength":1000,"isRoundEnd":true}`

- `AudioFrameManager.processAudioFrame(data: ByteArray, pts: Long)`：输入远端 PCM 帧及其 PTS，用于进行结束判定。
  - 要求：`pts` 需单调递增；必须与 `basePts` 处于同一时间基（否则无法映射到正确句桶）。

- `AudioFrameManager.release()`：释放内部资源与线程。

### 典型接入流程（Kotlin）

```kotlin
// 1) 注册回调
val audioCallback = object : AudioFrameManager.ICallback {
    override fun onSentenceEnd(sentenceId: String, isRoundEnd: Boolean) {
        // 一句播放结束，可进行：切下一句、刷新 UI、触发业务回调等
    }
}
AudioFrameManager.init(audioCallback)

// 2) 新句开始播放前，登记句元信息（可选：使用 JSON 版本）
AudioFrameManager.updateSentence(
    sentenceId = "sent-001",
    basePts = 100_000L, // 与后续音频帧 pts 同一时间基，并经 50s 分桶可映射到同一桶
    sentenceDataLength = 48_000, // 可作为参考长度（字节数/样本数，视你的定义而定）
    isRoundEnd = false
)
// 或
AudioFrameManager.updateSentenceWithJson(
    "{" +
        "\"sentenceId\":\"sent-001\"," +
        "\"basePts\":100000," +
        "\"sentenceDataLength\":48000," +
        "\"isRoundEnd\":false" +
    "}"
)

// 3) 在 onPlaybackAudioFrameBeforeMixing(...) 回调中喂入 PCM 与 PTS
buffer?.rewind()
val bytes = ByteArray(buffer?.remaining() ?: 0)
buffer?.get(bytes)
if (bytes.isNotEmpty()) {
    // pts 使用引擎回调提供的时间戳；若无则需与 basePts 保持同一时间基
    AudioFrameManager.processAudioFrame(bytes, pts)
}

// 4) 退出或销毁时
AudioFrameManager.release()
```

### 注意事项与调优建议

- 确保 `pts` 单调递增；若出现乱序或回退，建议在业务侧丢弃异常帧或进行时间基校正。
- `basePts` 与 `pts` 必须来自同一时间基；否则分桶映射不到同一句子，导致无法回调。
- 如需更灵敏/更稳健的结束判定，可按业务场景调整：
  - 减小 `PLAYBACK_AUDIO_FRAME_TIMEOUT_MS` 以更快结束；或增大以避免断句过碎。
  - 调整 `PLAYBACK_PTS_CHANGE_THRESHOLD_MS`，匹配你的 PTS 抖动特性。
  - 视需要引入“数据量阈值”终止（当前实现默认依赖超时与 PTS 跳变，若要启用可在源码中补充长度判定）。

与本项目的关系：

- 已在 `RtcManager.initialize` 中调用 `AudioFrameManager.init(...)`
- 已在 `onPlaybackAudioFrameBeforeMixing(...)` 中调用 `processAudioFrame(data, pts)`
- 已在 `RtcManager.destroy` 中调用 `AudioFrameManager.release()`
