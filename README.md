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

## 业务流程

- **启动与权限（`MainActivity`）**
  - 启动后调用 `checkPermissions()` 申请权限：`RECORD_AUDIO`、`CAMERA`、`WRITE_EXTERNAL_STORAGE`。
  - 初始化界面并展示 SDK 版本：`RtcEngine.getSdkVersion()`。

- **点击加入/离开频道（`MainActivity`）**
  - 未加入时点击“Join”：
    - 读取频道名输入，默认为 `testAga`。
    - 调用 `RtcManager.initialize(applicationContext, KeyCenter.APP_ID, this)` 初始化 SDK，并注册回调到 `MainActivity`（实现 `IRtcEventCallback`）。
    - 通过 `KeyCenter.getRtcUid()` 生成本地 UID，`KeyCenter.getRtcToken(channel, uid)` 生成或返回配置的 RTC Token。
    - 调用 `RtcManager.joinChannelEx(channel, uid, token, Constants.CLIENT_ROLE_BROADCASTER)` 入频道，角色为主播。
  - 已加入时点击“Leave”：
    - 停止音频读取线程 `mAudioFileReader?.stop()` 并调用 `RtcManager.leaveChannel()` 离开频道。

- **加入成功回调与后续处理（`MainActivity`）**
  - `onJoinChannelSuccess`：
    - 更新标题与按钮状态。
    - 启动音频推送逻辑 `handleJoinChannelSuccess()`：
      - 先从 assets 读取一次性 PCM 并推送：`tts_out_48k_1ch.pcm`（48k/单声道/16bit）。
      - 创建并启动 `AudioFileReader`，以固定间隔循环读取 `nearin_power_48k_1ch.pcm`，在 `onAudioRead` 回调中持续调用 `RtcManager.pushExternalAudioFrame(...)` 推送自定义音频帧。

- **RTC 初始化与参数设置（`RtcManager`）**
  - `initialize(...)`：
    - 创建 `RtcEngine`，设置频道场景为直播：`CHANNEL_PROFILE_LIVE_BROADCASTING`。
    - 设置音频 Profile 与场景：`AUDIO_PROFILE_DEFAULT`、`AUDIO_SCENARIO_AI_CLIENT`。
    - 常用内部参数：
      - `{"rtc.enable_debug_log":true}` 开启调试日志。
      - `{"che.audio.get_burst_mode":true}` 启用burst模式。
      - `{"che.audio.neteq.max_wait_first_decode_ms":0}`/`{"che.audio.neteq.max_wait_ms":0}` 。
    - 设为外放：`setDefaultAudioRoutetoSpeakerphone(true)`。
  - `joinChannelEx(...)`：
    - 注册音频帧观察者 `registerAudioFrame()`（拉流前混音回调）。
    - 创建自定义音轨 `createCustomAudioTrack(AUDIO_TRACK_DIRECT)` 并记录 `mCustomAudioTrackId`。
    - 配置 `ChannelMediaOptions`：
      - `autoSubscribeAudio = true`、`autoSubscribeVideo = false`。
      - `clientRoleType = roleType`（入参）。
      - `publishCustomAudioTrack = true`、`publishCustomAudioTrackId = mCustomAudioTrackId`。
      - `publishMicrophoneTrack = false`（不使用麦克风，改为自定义音轨）。
      - `enableAudioRecordingOrPlayout = false`（关闭本地播放/录制）。
    - 通过 `RtcEngineEx.joinChannelEx(...)` 入频道。

- **自定义音频推送（`RtcManager.pushExternalAudioFrame`）**
  - 前置条件：已 `initialize` 且已创建自定义音轨（`mCustomAudioTrackId != -1`）。
  - 入参需与源文件匹配：采样率（如 48000）、声道（如 1）、采样字节数（`Constants.BytesPerSample.TWO_BYTES_PER_SAMPLE`）。

- **远端音频帧监听与本地保存（`RtcManager`）**
  - 在 `registerAudioFrame()` 中设置 `onPlaybackAudioFrameBeforeMixing(...)` 回调，保存远端音频 PCM 到外部缓存目录文件（文件名包含 `channelId_uid_timestamp.pcm`）。

- **离开与销毁（`MainActivity` 与 `RtcManager`）**
  - `RtcManager.leaveChannel()` 退出频道。
  - `RtcManager.destroy()` 销毁引擎与自定义音轨、释放回调和线程调度器；`MainActivity` 退出时也会调用。

- **Token/UID 获取（`KeyCenter`）**
  - `APP_ID`、`APP_CERTIFICATE`、`RTC_TOKEN` 来自 `BuildConfig`（通过 `local.properties` 注入）。
  - `getRtcUid()` 随机生成并缓存本地 UID；`getRtcToken(channel, uid)` 若本地配置了 `RTC_TOKEN` 则直接返回，否则使用 `APP_CERTIFICATE` 本地生成临时 Token（仅用于测试）。

### 资源与权限

- **内置音频资源**：`assets/tts_out_48k_1ch.pcm`、`assets/nearin_power_48k_1ch.pcm`
- **必要权限**：`RECORD_AUDIO`、`CAMERA`、`WRITE_EXTERNAL_STORAGE`
