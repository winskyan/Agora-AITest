package io.agora.ai.test.maas.internal.rtm

import android.util.Log
import io.agora.ai.test.maas.MaaSConstants
import io.agora.ai.test.maas.MaaSEngineEventHandler
import io.agora.ai.test.maas.model.MaaSEngineConfiguration
import io.agora.rtm.ErrorInfo
import io.agora.rtm.MessageEvent
import io.agora.rtm.PresenceEvent
import io.agora.rtm.PublishOptions
import io.agora.rtm.ResultCallback
import io.agora.rtm.RtmClient
import io.agora.rtm.RtmConfig
import io.agora.rtm.RtmConstants
import io.agora.rtm.RtmEventListener
import io.agora.rtm.SubscribeOptions

object RtmManager {
    private var mEventCallback: MaaSEngineEventHandler? = null
    private var mRtmClient: RtmClient? = null
    private var mRoomName: String = ""
    private var mLoginSuccess = false

    fun initialize(configuration: MaaSEngineConfiguration) {
        Log.d(MaaSConstants.TAG, "rtm initialize")
        mLoginSuccess = false
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
                            subscribeChannelMessage(mRoomName)
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

    fun subscribeChannelMessage(roomName: String) {
        this.mRoomName = roomName
        if (mLoginSuccess) {
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
    }

    fun unsubscribeChannelMessage() {
        if (mRoomName.isEmpty() || !mLoginSuccess) {
            return
        }
        mRtmClient?.unsubscribe(
            mRoomName,
            object : ResultCallback<Void> {
                override fun onSuccess(p0: Void?) {
                    Log.d(MaaSConstants.TAG, "rtm unsubscribe onSuccess")
                    rtmLogout()
                }

                override fun onFailure(p0: ErrorInfo?) {
                    Log.d(MaaSConstants.TAG, "unsubscribe onFailure: $p0")
                }
            });
    }

    private fun rtmLogout() {
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
        Log.d(MaaSConstants.TAG, "rtm release")
    }

    fun sendRtmMessage(message: ByteArray, channelType: MaaSConstants.RtmChannelType) {
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
                    MaaSConstants.TAG, "send rtm message onSuccess"
                )
            }

            override fun onFailure(p0: ErrorInfo?) {
                Log.d(
                    MaaSConstants.TAG, "send rtm message  onFailure $p0"
                )
            }
        })
    }
}