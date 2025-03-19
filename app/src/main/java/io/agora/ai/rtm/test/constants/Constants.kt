package io.agora.ai.rtm.test.constants

import java.text.SimpleDateFormat
import java.util.Locale

object Constants {
    const val TAG = "AgoraAITest"
    const val LOG_FILE_NAME = "agora.AITest"

    const val DEFAULT_TEST_COUNT = 100
    const val MAX_IN_CHANNEL_TEST_COUNT = 20
    const val INTERVAL_LOOP_WAIT = 10 * 1000L
    const val INTERVAL_RECEIVER_MESSAGE_TIMEOUT = 5 * 1000L
    
    // Date format for log timestamps - yyyy-MM-dd HH:mm:ss.SSS
    val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
}