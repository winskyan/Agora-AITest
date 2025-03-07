package io.agora.ai.rtm.test.rtm

import android.util.Log
import io.agora.ai.rtm.test.constants.Constants
import io.agora.rtm.ErrorInfo
import io.agora.rtm.LinkStateEvent
import io.agora.rtm.MessageEvent
import io.agora.rtm.PublishOptions
import io.agora.rtm.ResultCallback
import io.agora.rtm.RtmClient
import io.agora.rtm.RtmConfig
import io.agora.rtm.RtmConstants
import io.agora.rtm.RtmEventListener
import io.agora.rtm.SubscribeOptions


object RtmManager {
    private val TAG = Constants.TAG + "-" + RtmManager::class.java.simpleName
    private var mRtmClient: RtmClient? = null
    private var mRtmMessageListener: RtmMessageListener? = null

    interface RtmMessageListener {
        fun onRtmMessageReceived(message: String)
        fun onRtmConnected()
        fun onRtmDisconnected()
        fun onRtmSubscribed()
    }

    fun create(appId: String, rtmUserId: String, listener: RtmMessageListener) {
        Log.d(TAG, "rtm create appId: $appId, rtmUserId: $rtmUserId")
        mRtmMessageListener = listener
        val rtmConfig =
            RtmConfig.Builder(appId, rtmUserId)
                .eventListener(object : RtmEventListener {

                    override fun onLinkStateEvent(event: LinkStateEvent?) {
                        super.onLinkStateEvent(event)
                        Log.d(TAG, "Rtm onLinkStateEvent: $event")
                        if (event?.currentState == RtmConstants.RtmLinkState.CONNECTED) {
                            mRtmMessageListener?.onRtmConnected()
                        } else if (event?.currentState == RtmConstants.RtmLinkState.IDLE) {
                            mRtmMessageListener?.onRtmDisconnected()
                        }
                    }

                    override fun onMessageEvent(event: MessageEvent?) {
                        super.onMessageEvent(event)
                        Log.d(TAG, "Rtm onMessageEvent: $event")
                        if (event?.channelType == RtmConstants.RtmChannelType.MESSAGE) {
                            var rtmMessage: String = ""
                            if (event.message.type == RtmConstants.RtmMessageType.BINARY) {
                                rtmMessage = String((event.message.data as ByteArray))
                            } else if (event.message.type == RtmConstants.RtmMessageType.STRING) {
                                rtmMessage = event.message.data as String
                            }
                            mRtmMessageListener?.onRtmMessageReceived(rtmMessage)
                            //sendRtmMessage(event.message.data as ByteArray, event.channelName)
                        }
                    }
                })
                .build()

        mRtmClient = RtmClient.create(rtmConfig)
    }

    fun login(rtmToken: String) {
        Log.d(TAG, "rtm login")

        try {
            mRtmClient?.login(
                rtmToken,
                object : ResultCallback<Void> {
                    override fun onSuccess(p0: Void?) {
                        Log.d(TAG, "rtm login onSuccess")

                    }

                    override fun onFailure(p0: ErrorInfo?) {
                        Log.d(TAG, "rtm login onFailure: $p0")
                    }
                })


        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun subscribeMessageChannel(roomName: String) {
        Log.d(TAG, "rtm subscribe message channelName:$roomName")
        mRtmClient?.subscribe(roomName,
            object : SubscribeOptions() {
                init {
                    withMessage = true
                    withPresence = true
                }
            },
            object : ResultCallback<Void> {
                override fun onSuccess(p0: Void?) {
                    Log.d(TAG, "rtm subscribe onSuccess")
                    mRtmMessageListener?.onRtmSubscribed()
                }

                override fun onFailure(p0: ErrorInfo?) {
                    Log.d(TAG, "subscribe onFailure: $p0")
                }
            })
    }

    fun unsubscribeMessageChannel(roomName: String) {
        Log.d(TAG, "rtm unsubscribe message channelName:$roomName")
        mRtmClient?.unsubscribe(roomName,
            object : ResultCallback<Void> {
                override fun onSuccess(p0: Void?) {
                    Log.d(TAG, "rtm unsubscribe message channel onSuccess")
                }

                override fun onFailure(p0: ErrorInfo?) {
                    Log.d(TAG, "rtm unsubscribe message channel onFailure: $p0")
                }
            })
    }


    fun rtmLogout() {
        Log.d(TAG, "rtm logout")
        mRtmClient?.logout(object : ResultCallback<Void> {
            override fun onSuccess(p0: Void?) {
                Log.d(TAG, "rtm logout onSuccess")
            }

            override fun onFailure(p0: ErrorInfo?) {
                Log.d(TAG, "rtm logout onFailure: $p0")
            }
        })
    }

    fun release() {
//      RtmClient.release()
        mRtmClient = null
        Log.d(TAG, "rtm release")
    }

    fun sendRtmMessage(message: ByteArray, roomName: String): Int {
        Log.d(TAG, "send rtm message channelName:$roomName, message: ${String(message)}")
        mRtmClient?.publish(roomName, message, object : PublishOptions() {
            init {
                setChannelType(RtmConstants.RtmChannelType.MESSAGE)
            }
        }, object : ResultCallback<Void> {
            override fun onSuccess(p0: Void?) {
                Log.d(TAG, "send rtm message onSuccess for channelType: MESSAGE")
            }

            override fun onFailure(p0: ErrorInfo?) {
                Log.d(
                    TAG,
                    "send rtm message onFailure for channelType: MESSAGE: $p0"
                )
            }
        })
        return 0
    }
}