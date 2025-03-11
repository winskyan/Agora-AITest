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

### 使用方法

#### Test Count(100)

该功能指测试RTM或者ws链接的次数。默认是100次。

#### Stop

该功能指停止测试。

#### Rtm Test

该功能指测试RTM链接,测试流程为login->onRtmConnected->subscribe->onRtmSubscribed->sendRtmMessage->receiveRtmMessage->unsubscribe->logout。点击一次执行指定测试次数，测试数据包括login开始到connected时间，发送rtm消息收到返回相同消息的时间，收到消息与开始login时间。

#### Ws Test

该功能指测试WS链接,测试流程为connectWs->onWSConnected->sendWsMessage->receiveWsMessage->logoutWs。点击一次执行指定测试次数，测试数据包括login开始到connected时间，发送ws消息收到返回相同消息的时间，收到消息与开始login时间。

#### 测试结果

测试结果会显示在logcat和UI中，并且会保存到`sdcard/Android/data/io.agora.ai.rtm.test/history*.txt`文件中,统计包含单次测试的时间和平均时间。

##### `history-rtm*.txt`为RTM测试结果,示例如下

```
Test Start remainingTests:3
loginConnectedDiff:255ms average:255ms
SendRtmMessage:rtmMessage1741674599416
ReceiveRtmMessage:rtmMessage1741674599416 diff:79ms average:79ms
ReceiveRtmMessage:rtmMessage1741674599416 from login diff:476ms average:476ms
Test Start remainingTests:2
loginConnectedDiff:163ms average:209ms
SendRtmMessage:rtmMessage1741674600888
ReceiveRtmMessage:rtmMessage1741674600888 diff:1889ms average:984ms
ReceiveRtmMessage:rtmMessage1741674600888 from login diff:2160ms average:1318ms
Test Start remainingTests:1
loginConnectedDiff:195ms average:204ms
SendRtmMessage:rtmMessage1741674604233
ReceiveRtmMessage:rtmMessage1741674604233 diff:1869ms average:1279ms
ReceiveRtmMessage:rtmMessage1741674604233 from login diff:2189ms average:1608ms
Test Start remainingTests:0
Test End
```

##### `history-ws*.txt`为WS测试结果,示例如下

```
Test Start remainingTests:3
ws loginConnectedDiff:1494ms average:1494ms
SendWsMessage:wsMessage1741674638789
ReceiverWsMessage:wsMessage1741674638789 diff:476ms average:476ms
ReceiverWsMessage:wsMessage1741674638789 from login diff:1980ms average:1980ms
Test Start remainingTests:2
ws loginConnectedDiff:1667ms average:1580ms
SendWsMessage:wsMessage1741674642463
ReceiverWsMessage:wsMessage1741674642463 diff:608ms average:542ms
ReceiverWsMessage:wsMessage1741674642463 from login diff:2285ms average:2132ms
Test Start remainingTests:1
ws loginConnectedDiff:1761ms average:1640ms
SendWsMessage:wsMessage1741674646458
ReceiverWsMessage:wsMessage1741674646458 diff:536ms average:540ms
ReceiverWsMessage:wsMessage1741674646458 from login diff:2307ms average:2190ms
Test Start remainingTests:0
Test End
```
