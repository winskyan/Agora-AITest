package io.agora.ai.test.maas.internal.rtm

import android.util.Log
import io.agora.ai.test.maas.MaaSConstants
import io.agora.ai.test.maas.MaaSEngineEventHandler
import io.agora.ai.test.maas.model.MaaSEngineConfiguration
import io.agora.rtm.ErrorInfo
import io.agora.rtm.JoinChannelOptions
import io.agora.rtm.JoinTopicOptions
import io.agora.rtm.MessageEvent
import io.agora.rtm.PresenceEvent
import io.agora.rtm.PublishOptions
import io.agora.rtm.ResultCallback
import io.agora.rtm.RtmClient
import io.agora.rtm.RtmConfig
import io.agora.rtm.RtmConstants
import io.agora.rtm.RtmConstants.RtmMessageQos
import io.agora.rtm.RtmEventListener
import io.agora.rtm.StreamChannel
import io.agora.rtm.SubscribeOptions
import io.agora.rtm.SubscribeTopicResult
import io.agora.rtm.TopicEvent
import io.agora.rtm.TopicMessageOptions
import io.agora.rtm.TopicOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


object RtmManager {
    private var mEventCallback: MaaSEngineEventHandler? = null
    private var mRtmClient: RtmClient? = null
    private var mStreamChannel: StreamChannel? = null
    private var mRoomName: String = ""
    private var mLoginSuccess = false
    private var mRtmToken = ""

    fun initialize(configuration: MaaSEngineConfiguration) {
        Log.d(MaaSConstants.TAG, "rtm initialize")
        mLoginSuccess = false
        mRtmToken = configuration.rtmToken
        mEventCallback = configuration.eventHandler
        val rtmConfig =
            RtmConfig.Builder(configuration.appId, configuration.userId.toString())
                .eventListener(object : RtmEventListener {
                    override fun onMessageEvent(event: MessageEvent?) {
                        super.onMessageEvent(event)
                        Log.d(MaaSConstants.TAG, "Rtm onMessageEvent: $event")
                        if (event?.channelType == RtmConstants.RtmChannelType.MESSAGE) {
                            var rtmMessage: String = ""
                            if (event.message.type == RtmConstants.RtmMessageType.BINARY) {
                                rtmMessage = String((event.message.data as ByteArray))
                            } else if (event.message.type == RtmConstants.RtmMessageType.STRING) {
                                rtmMessage = event.message.data as String
                            }
                            mEventCallback?.onRtmMessageReceived(
                                MaaSConstants.RtmChannelType.MESSAGE,
                                event.channelName,
                                event.topicName,
                                rtmMessage,
                                event.publisherId,
                                event.customType,
                                event.timestamp
                            )
                        } else if (event?.channelType == RtmConstants.RtmChannelType.STREAM) {
                            var rtmMessage: String = ""
                            if (event.message.type == RtmConstants.RtmMessageType.BINARY) {
                                rtmMessage = String((event.message.data as ByteArray))
                            } else if (event.message.type == RtmConstants.RtmMessageType.STRING) {
                                rtmMessage = event.message.data as String
                            }
                            mEventCallback?.onRtmMessageReceived(
                                MaaSConstants.RtmChannelType.STREAM,
                                event.channelName,
                                event.topicName,
                                rtmMessage,
                                event.publisherId,
                                event.customType,
                                event.timestamp
                            )
                        }
                    }

                    override fun onPresenceEvent(event: PresenceEvent?) {
                        super.onPresenceEvent(event)
                        Log.d(MaaSConstants.TAG, "Rtm onPresenceEvent: $event")
                    }

                    override fun onTopicEvent(event: TopicEvent?) {
                        super.onTopicEvent(event)
                        Log.d(MaaSConstants.TAG, "Rtm onTopicEvent: $event")
                        if (event?.type == RtmConstants.RtmTopicEventType.REMOTE_JOIN ||
                            event?.type == RtmConstants.RtmTopicEventType.SNAPSHOT
                        ) {
                            CoroutineScope(Dispatchers.Main).launch {
                                subscribeTopic(mRoomName)
                            }
                        }

                    }
                })
                .build()

        try {
            mRtmClient = RtmClient.create(rtmConfig)

            mRtmClient?.login(
                configuration.rtmToken,
                object : ResultCallback<Void> {
                    override fun onSuccess(p0: Void?) {
                        Log.d(MaaSConstants.TAG, "rtm login onSuccess")
                        mLoginSuccess = true
                        if (mRoomName.isNotEmpty()) {
                            joinChannel(mRoomName)
                        }
                    }

                    override fun onFailure(p0: ErrorInfo?) {
                        Log.d(MaaSConstants.TAG, "rtm login onFailure: $p0")
                    }
                })


        } catch (e: Exception) {
            e.printStackTrace()

        }
    }

    fun joinChannel(roomName: String) {
        this.mRoomName = roomName
        if (mLoginSuccess) {
            subscribeMessageChannel(roomName)

            joinStreamChannel(roomName)
        }
    }

    private fun subscribeMessageChannel(roomName: String) {
        mRtmClient?.subscribe(roomName,
            object : SubscribeOptions() {
                init {
                    withMessage = true
                    withPresence = true
                }
            },
            object : ResultCallback<Void> {
                override fun onSuccess(p0: Void?) {
                    Log.d(MaaSConstants.TAG, "rtm subscribe onSuccess")
                }

                override fun onFailure(p0: ErrorInfo?) {
                    Log.d(MaaSConstants.TAG, "subscribe onFailure: $p0")
                }
            })
    }

    private fun unsubscribeMessageChannel(roomName: String) {
        mRtmClient?.unsubscribe(roomName,
            object : ResultCallback<Void> {
                override fun onSuccess(p0: Void?) {
                    Log.d(MaaSConstants.TAG, "rtm unsubscribe message channel onSuccess")
                }

                override fun onFailure(p0: ErrorInfo?) {
                    Log.d(MaaSConstants.TAG, "rtm unsubscribe message channel onFailure: $p0")
                }
            })
    }

    private fun joinStreamChannel(roomName: String) {
        mStreamChannel = mRtmClient?.createStreamChannel(roomName)

        val options = JoinChannelOptions()
        options.token = mRtmToken
        options.withPresence = true
        options.withLock = true
        options.withMetadata = true

        mStreamChannel?.join(options, object : ResultCallback<Void?> {
            override fun onSuccess(responseInfo: Void?) {
                Log.d(MaaSConstants.TAG, "rtm join stream channel success")
                CoroutineScope(Dispatchers.Main).launch { joinTopic(roomName) }

            }

            override fun onFailure(errorInfo: ErrorInfo) {
                Log.d(MaaSConstants.TAG, "rtm join stream channel failure: $errorInfo")
            }
        })
    }

    private fun joinTopic(roomName: String) {
        val topicOptions = JoinTopicOptions()
        topicOptions.setMessageQos(RtmMessageQos.ORDERED)

        mStreamChannel?.joinTopic(roomName, topicOptions, object : ResultCallback<Void?> {
            override fun onSuccess(responseInfo: Void?) {
                Log.d(MaaSConstants.TAG, "rtm join topic success")
                CoroutineScope(Dispatchers.Main).launch {
                    subscribeTopic(roomName)
                }
            }

            override fun onFailure(errorInfo: ErrorInfo) {
                Log.d(MaaSConstants.TAG, "rtm join topic failure: $errorInfo")
            }
        })
    }

    private fun subscribeTopic(roomName: String) {
        val options = TopicOptions()

        mStreamChannel?.subscribeTopic(
            roomName,
            options,
            object : ResultCallback<SubscribeTopicResult?> {
                override fun onSuccess(responseInfo: SubscribeTopicResult?) {
                    Log.d(MaaSConstants.TAG, "rtm subscribe topic success")
                }

                override fun onFailure(errorInfo: ErrorInfo) {
                    Log.e(MaaSConstants.TAG, "rtm subscribe topic failure: $errorInfo")
                }
            })
    }

    private fun leaveStreamChannel() {
        if (mRoomName.isEmpty() || !mLoginSuccess) {
            return
        }

        val topicName = mRoomName
        val options = TopicOptions()

        mStreamChannel!!.unsubscribeTopic(
            topicName.toString(),
            options,
            object : ResultCallback<Void?> {
                override fun onSuccess(responseInfo: Void?) {
                    Log.d(MaaSConstants.TAG, "unsubscribe topic success")
                }

                override fun onFailure(errorInfo: ErrorInfo) {
                    Log.e(MaaSConstants.TAG, "unsubscribe topic failure: $errorInfo")
                }
            })

        mStreamChannel?.leaveTopic(mRoomName, object : ResultCallback<Void?> {
            override fun onSuccess(responseInfo: Void?) {
                Log.d(MaaSConstants.TAG, "rtm leave topic success")
            }

            override fun onFailure(errorInfo: ErrorInfo) {
                Log.e(MaaSConstants.TAG, "rtm leave topic failure: $errorInfo")
            }
        })

        mStreamChannel?.leave(object : ResultCallback<Void?> {
            override fun onSuccess(responseInfo: Void?) {
                Log.d(MaaSConstants.TAG, "rtm leave stream channel success")
            }

            override fun onFailure(errorInfo: ErrorInfo) {
                Log.d(MaaSConstants.TAG, "rtm leave stream channel failure: $errorInfo")
            }
        })
    }

    fun leaveChannel() {
        if (mRoomName.isEmpty() || !mLoginSuccess) {
            return
        }
        unsubscribeMessageChannel(mRoomName)
        leaveStreamChannel()
    }

    fun rtmLogout() {
        mStreamChannel?.release()

        mRtmClient?.logout(object : ResultCallback<Void> {
            override fun onSuccess(p0: Void?) {
                Log.d(MaaSConstants.TAG, "rtm logout onSuccess")
                mLoginSuccess = false
                release()
            }

            override fun onFailure(p0: ErrorInfo?) {
                Log.d(MaaSConstants.TAG, "rtm logout onFailure: $p0")
            }
        })
    }

    private fun release() {
//      RtmClient.release()
        mRtmClient = null
        mStreamChannel = null
        mRtmToken = ""
        mRoomName = ""
        Log.d(MaaSConstants.TAG, "rtm release")
    }

    fun sendRtmMessage(message: ByteArray, channelType: MaaSConstants.RtmChannelType) {
        if (channelType == MaaSConstants.RtmChannelType.STREAM) {
            sendStreamMessage(message)
            return
        }

        Log.d(
            MaaSConstants.TAG,
            "send rtm message channelName:$mRoomName, message: ${String(message)}, channelType: $channelType"
        )

        mRtmClient?.publish(mRoomName, message, object : PublishOptions() {
            init {
                setChannelType(RtmConstants.RtmChannelType.getEnum(channelType.value))
            }
        }, object : ResultCallback<Void> {
            override fun onSuccess(p0: Void?) {
                Log.d(
                    MaaSConstants.TAG, "send rtm message onSuccess for channelType: $channelType"
                )
            }

            override fun onFailure(p0: ErrorInfo?) {
                Log.d(
                    MaaSConstants.TAG,
                    "send rtm message onFailure for channelType: $channelType $p0"
                )
            }
        })
    }

    private fun sendStreamMessage(message: ByteArray) {
        Log.d(
            MaaSConstants.TAG,
            "send rtm stream message: ${String(message)} , channelType: STREAM"
        )

        val topicName = mRoomName
        val options = TopicMessageOptions()
        options.customType = "ByteArray"
        mStreamChannel?.publishTopicMessage(
            topicName,
            message,
            options,
            object : ResultCallback<Void?> {
                override fun onSuccess(responseInfo: Void?) {
                    Log.d(
                        MaaSConstants.TAG,
                        "send rtm stream message success for channelType: STREAM"
                    )
                }

                override fun onFailure(errorInfo: ErrorInfo) {
                    Log.e(MaaSConstants.TAG, "send rtm stream message failure: $errorInfo")
                }
            })
    }
}