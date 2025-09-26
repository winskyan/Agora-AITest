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
    - `{"che.audio.neteq.max_wait_ms":150}`

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

### 新协议 PTS 生成与处理

#### 协议概述

新协议支持基于会话（Session）的音频流管理，包含数据包和命令包两种类型。协议头格式如下：

**基础协议头：**
```
[1位:固定0] | [1位:is_agora] | [4位:version] | [8位:session_id] | [1位:cmd_or_data_type] | [...] | [16位:base_pts]
```

**数据包 (cmd_or_data_type=0)：**
```
[1:0] | [1:is_agora] | [4:version] | [8:session_id] | [1:0] | [12:sentence_id] | [5:reserved] | [16:reserved] | [16:base_pts]
```

**命令包 (cmd_or_data_type=1)：**
```
[1:0] | [1:is_agora] | [4:version] | [8:session_id] | [1:1] | [5:cmd_type] | [12:session_dur_or_reserved] | [16:reserved] | [16:base_pts]
```

#### 命令类型说明

- **cmd_type = 1**：会话结束命令
  - `session_dur_or_reserved` 字段包含会话持续时长（以包为单位）
  - 发送时机：会话结束时发送 10 个包确保可靠性
  
- **cmd_type = 2**：会话中断命令
  - 用于打断当前会话（类似 mute 功能）
  - 发送时机：需要中断当前会话时发送 10 个包确保可靠性

#### API 使用

**1. 设置会话 ID：**
```kotlin
AudioFrameManager.setSessionId(sessionId)
```

**2. 生成数据包 PTS：**
```kotlin
val pts = AudioFrameManager.generatePtsNew(data, sampleRate, channels, 0, 0)
rtcEngine.pushExternalAudioFrame(
    data,
    pts,
    sampleRate,
    channels,
    Constants.BytesPerSample.TWO_BYTES_PER_SAMPLE,
    customAudioTrackId
)
```

**3. 发送会话结束命令：**
```kotlin
AudioFrameManager.sendSessionEndCommand(sessionDurationInPackets)
```

**4. 发送会话中断命令：**
```kotlin
AudioFrameManager.sendSessionInterruptCommand()
```

#### 协议特性

- **可靠性保证**：命令包发送 10 次，在 10% 丢包率下成功率达 99.99%
- **去重处理**：接收端只处理每个会话的第一个命令包，忽略重复包
- **超时机制**：500ms 无数据自动判定会话结束
- **会话管理**：自动检测会话切换并触发相应回调

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

`AudioFrameManager` 用于基于会话（Session）的音频流管理，支持会话开始、结束、中断的检测与回调。新协议直接从 `processAudioFrame` 的 `pts` 位域中解析会话信息。

### 术语说明

- **session**：一轮对话会话，由 8 位 session_id 标识（0-255）
- **sentence**：会话内的句子，由 12 位 sentence_id 标识（仅数据包使用）
- **cmd_type**：命令类型，1=会话结束，2=会话中断

### 协议解析

新协议位分布：
```
[1:固定0] | [1:is_agora] | [4:version] | [8:session_id] | [1:cmd_or_data_type] | [...] | [16:base_pts]
```

- **数据包解析**：提取 session_id 和 sentence_id，用于会话跟踪
- **命令包解析**：提取 session_id 和 cmd_type，触发相应回调

### 工作机制

- **会话开始检测**：当接收到新的 session_id 时自动触发 `onSessionStart`
- **会话结束检测**：
  - 接收到 cmd_type=1 命令包时立即触发 `onSessionEnd`
  - 500ms 超时无数据时自动触发 `onSessionEnd`
- **会话中断检测**：接收到 cmd_type=2 命令包时立即触发 `onSessionInterrupt`
- **去重处理**：同一 session_id 的重复命令包会被忽略，只处理第一个

### 常量配置

- `SESSION_TIMEOUT_MS = 500L`：会话超时时间（毫秒）

### API 一览

#### 初始化与回调

- `AudioFrameManager.init(callback: ICallback)`：初始化并注册回调
    - 参数：`callback` - 会话事件回调实例

- `ICallback` 接口包含三个回调方法：
    - `onSessionStart(sessionId: Int)`：新会话开始时触发
    - `onSessionEnd(sessionId: Int)`：会话结束时触发（命令包或超时）
    - `onSessionInterrupt(sessionId: Int)`：会话中断时触发

#### 核心处理方法

- `AudioFrameManager.processAudioFrame(data: ByteArray, pts: Long)`：处理远端音频帧
    - 参数：
        - `data`：当前帧的 PCM 字节数组
        - `pts`：64 位 PTS，包含会话信息和命令
    - 功能：解析协议头，检测会话变化，处理命令包

#### PTS 生成方法

- `AudioFrameManager.generatePtsNew(data: ByteArray, sampleRate: Int, channels: Int, cmdType: Int, sessionDurInPacks: Int = 0)`：生成新协议 PTS
    - 参数：
        - `data`：PCM 数据（命令包可为空）
        - `sampleRate`、`channels`：音频参数（命令包忽略）
        - `cmdType`：0=数据包，1=会话结束，2=会话中断
        - `sessionDurInPacks`：会话持续包数（仅 cmdType=1 使用）

- `AudioFrameManager.generatePts(data: ByteArray, sampleRate: Int, channels: Int, isSessionEnd: Boolean)`：兼容性方法，内部调用 `generatePtsNew`

#### 会话管理方法

- `AudioFrameManager.setSessionId(sessionId: Int)`：设置当前会话 ID
- `AudioFrameManager.sendSessionEndCommand(sessionDurInPacks: Int)`：发送会话结束命令（10个包）
- `AudioFrameManager.sendSessionInterruptCommand()`：发送会话中断命令（10个包）
- `AudioFrameManager.release()`：释放资源并清理状态

### 典型接入流程（Kotlin）

```kotlin
// 1) 注册回调
val audioCallback = object : AudioFrameManager.ICallback {
    override fun onSessionStart(sessionId: Int) {
        Log.d(TAG, "会话开始: $sessionId")
        // 处理会话开始逻辑
    }
    
    override fun onSessionEnd(sessionId: Int) {
        Log.d(TAG, "会话结束: $sessionId")
        // 处理会话结束逻辑
    }
    
    override fun onSessionInterrupt(sessionId: Int) {
        Log.d(TAG, "会话中断: $sessionId")
        // 处理会话中断逻辑
    }
}
AudioFrameManager.init(audioCallback)

// 2) 设置会话 ID 并开始推送数据
AudioFrameManager.setSessionId(123)

// 3) 在 onPlaybackAudioFrameBeforeMixing(...) 回调中处理远端音频
buffer?.rewind()
val bytes = ByteArray(buffer?.remaining() ?: 0)
buffer?.get(bytes)
if (bytes.isNotEmpty()) {
    AudioFrameManager.processAudioFrame(bytes, presentationMs)
}

// 4) 本地推送音频数据时生成 PTS
val pts = AudioFrameManager.generatePtsNew(frameBytes, sampleRate, channels, 0, 0)
rtcEngine.pushExternalAudioFrame(data, pts, sampleRate, channels, bytesPerSample, trackId)

// 5) 会话结束时发送结束命令
AudioFrameManager.sendSessionEndCommand(totalPackets)

// 6) 需要中断时发送中断命令
AudioFrameManager.sendSessionInterruptCommand()

// 7) 退出或销毁时
AudioFrameManager.release()
```

### 注意事项与调优建议

- **协议兼容性**：确保 PTS 按新协议正确编码，包含完整的会话信息
- **可靠性设计**：命令包自动发送 10 次，接收端自动去重，无需业务层处理
- **超时调整**：可根据业务需求调整 `SESSION_TIMEOUT_MS` 常量
- **会话管理**：合理设置 session_id，避免频繁切换导致的误判

### 协议应用场景

1. **判断会话开始**：解析协议，收到新的 session_id 即表示新会话开始
2. **判断会话结束**：
   - 主动结束：收到 cmd_type=1 命令包
   - 超时结束：500ms 无数据自动判定
3. **字幕对齐**：利用 sentence_id 进行句子级别的字幕同步
4. **自渲染打断**：在多模态场景下，使用 cmd_type=2 实现有序的当前会话→打断→新会话流程

### 与本项目的集成

- 已在 `RtcManager.initialize` 中调用 `AudioFrameManager.init(...)`
- 已在 `onPlaybackAudioFrameBeforeMixing(...)` 中调用 `processAudioFrame(data, pts)`
- 已在 `RtcManager.destroy` 中调用 `AudioFrameManager.release()`
- 支持通过 `RtcManager.pushExternalAudioFrame` 推送带新协议 PTS 的音频数据
