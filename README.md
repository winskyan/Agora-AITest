# Agora AITest

## 项目简介

这是一个用于测试声网RTM和WebSocket连接性能的Android应用程序。通过该应用，您可以测试RTM和WebSocket的连接速度、消息收发延迟等性能指标。

## 运行指南

### 配置密钥

在项目根目录下的 `local.properties` 文件中配置以下信息：

```properties
APP_ID=你的声网应用ID
APP_CERTIFICATE=你的声网证书密钥
```

### 运行应用

1. 在 Android Studio 中打开项目
2. 选择 `app` 模块
3. 点击运行按钮或按下 `Shift+F10` 运行应用

## 功能说明

### 测试次数设置 (Test Count)

- 功能：设置RTM或WebSocket连接测试的次数
- 默认值：100次
- 使用方法：在应用界面输入框中修改测试次数

### 停止测试 (Stop)

- 功能：立即停止当前正在进行的测试
- 使用方法：点击"Stop"按钮

### RTM测试 (Rtm Test)

- 功能：测试声网RTM服务的连接性能
- 默认频道: wei888
- 测试流程：
  1. 登录RTM (login)
  2. 连接成功回调 (onRtmConnected)
  3. 订阅频道 (subscribe)
  4. 订阅成功回调 (onRtmSubscribed)
  5. 发送RTM消息 (sendRtmMessage)
  6. 接收RTM消息 (receiveRtmMessage)
  7. 取消订阅 (unsubscribe)
  8. 登出 (logout)
- 每次测试：登录后在频道内发送和接收20次消息，然后登出
- 测试数据：
  - 从开始登录到连接成功的时间
  - 发送RTM消息到收到相同消息的平均时间
  - 从开始登录到收到第一条消息的时间

### WebSocket测试 (Ws Test)

- 功能：测试WebSocket连接性能
- 默认URL: wss://108.129.196.84:8765
- 测试流程：
  1. 连接WebSocket (connectWs)
  2. 连接成功回调 (onWSConnected)
  3. 发送WebSocket消息 (sendWsMessage)
  4. 接收WebSocket消息 (receiveWsMessage)
  5. 断开连接 (logoutWs)
- 每次测试：连接后在频道内发送和接收20次消息，然后断开连接
- 测试数据：
  - 从开始连接到连接成功的时间
  - 发送WebSocket消息到收到相同消息的平均时间
  - 从开始连接到收到第一条消息的时间

## 测试结果

测试结果会通过以下三种方式呈现：

1. **日志输出**：在Logcat中查看详细测试数据
2. **界面显示**：在应用UI中显示测试结果摘要
3. **文件保存**：测试数据会保存到设备存储中

### 结果文件位置

测试结果保存在设备的以下路径：

```
sdcard/Android/data/io.agora.ai.rtm.test/history*.txt
```

### 结果文件说明

#### RTM测试结果文件 (`history-rtm*.txt`)

RTM测试结果文件包含以下信息：

- 每次测试的开始标记和剩余测试次数
- 登录连接时间及平均值
- 每条消息的发送和接收平均时间
- 消息收发延迟及平均值
- 从登录到收到第一条消息的时间
- 测试统计摘要（平均连接时间、平均消息延迟、测试计数）

#### WebSocket测试结果文件 (`history-ws*.txt`)

WebSocket测试结果文件包含以下信息：

- 每次测试的开始标记和剩余测试次数
- WebSocket连接时间及平均值
- 每条消息的发送和接收平均时间
- 消息收发延迟及平均值
- 从连接到收到第一条消息的时间
- 测试统计摘要（平均连接时间、平均消息延迟、测试计数）

## 使用方法

1. 安装apk
2. 打开主页面，输入Test Count，默认是100次
3. 如果测试RTM，输入Channel Name，默认是wei888,然后点击Rtm Test按钮开始测试
4. 如果测试WebSocket，输入URL，默认是wss://108.129.196.84:8765,然后点击Ws Test按钮开始测试
5. 测试过程中可以点击Stop按钮停止测试
6. 测试完成后，会显示测试结果，包括连接时间，消息延迟，收到第一条消息的时间
7. 测试结果会保存在手机的sdcard/Android/data/io.agora.ai.rtm.test/history*.txt路径下
