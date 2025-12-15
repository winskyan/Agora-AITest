package io.agora.ai.test.constants

object ExamplesConstants {
    const val TAG = "AITest"
    const val LOG_FILE_NAME = "agora.AITest"

    // 音频配置
    const val ENABLE_STEREO_AUDIO = false // 是否启用双声道音频，true=双声道，false=单声道

    const val ENABLE_AUDIO_TEST = false // 是否启用音频测试循环模式
    const val EXPECTED_PCM_FILE_SIZE_BYTES: Long =
        480000L // 期望的 PCM 文件大小（字节），用于测试循环判断。16kHz 单通道 15秒 = 16000 × 1 × 2 × 15 = 480000 字节
    const val AUTO_LOOP_WAIT_TIME_MS: Long = 10 * 1000L
}