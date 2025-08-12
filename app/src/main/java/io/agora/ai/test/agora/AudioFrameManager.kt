package io.agora.ai.test.agora

import io.agora.ai.test.constants.ExamplesConstants
import io.agora.ai.test.utils.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

object AudioFrameManager {
    private const val TAG = "${ExamplesConstants.TAG}-AudioFrameManager"
    private const val PLAYBACK_AUDIO_FRAME_TIMEOUT = 200 // ms

    private var mAudioFrameFinishJob: Job? = null
    private val mExecutor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val mSingleThreadScope = CoroutineScope(mExecutor)
    private var mCallback: ICallback? = null

    private var mSessionId: String = ""

    interface ICallback {
        fun onLineEnd(sessionId: String, index: Int) {

        }

        fun onSessionEnd(sessionId: String) {

        }
    }

    fun init(callback: ICallback) {
        LogUtils.d(TAG, "AudioFrameManager init")
        mCallback = callback
    }

    fun updateSession(sessionId: String, index: Int, text: String) {
        this.mSessionId = sessionId
    }

    fun processAudioFrame(data: ByteArray) {
        mAudioFrameFinishJob?.cancel()
        mAudioFrameFinishJob = mSingleThreadScope.launch {
            delay(PLAYBACK_AUDIO_FRAME_TIMEOUT.toLong())
            LogUtils.d(
                TAG,
                "onPlaybackAudioFrame finished due to timeout ${PLAYBACK_AUDIO_FRAME_TIMEOUT}ms"
            )
            mCallback?.onSessionEnd(mSessionId)
        }
    }

    fun release() {
        LogUtils.d(TAG, "AudioFrameManager release")
        mAudioFrameFinishJob?.cancel()
        mCallback = null
    }
}