package io.agora.ai.test.utils

import android.content.Context
import android.os.Process
import java.io.IOException
import java.io.InputStream

/**
 * 双声道音频文件读取器
 * 读取两个单声道PCM文件并混合成双声道输出
 */
class StereoAudioFileReader(
    private val context: Context,
    private val leftFileName: String,
    private val rightFileName: String,
    private val sampleRate: Int,
    private val interval: Int,
    private val loopPlayback: Boolean,
    private val audioReadListener: OnAudioReadListener?
) {

    companion object {
        private const val BITS_PER_SAMPLE: Int = 16
        private const val CHANNELS = 2 // 固定双声道
    }

    @kotlin.jvm.Volatile
    private var pushing = false
    private var thread: InnerThread? = null
    private var leftInputStream: InputStream? = null
    private var rightInputStream: InputStream? = null

    private fun getBufferSize(): Int {
        // 双声道缓冲区大小
        return (((sampleRate / 1000).toLong() * interval) * (BITS_PER_SAMPLE / 8 * CHANNELS)).toInt()
    }

    private fun getMonoBufferSize(): Int {
        // 单声道缓冲区大小
        return (((sampleRate / 1000).toLong() * interval) * (BITS_PER_SAMPLE / 8)).toInt()
    }

    private fun getOneMsBufferSize(): Int {
        val bytesPerSample = BITS_PER_SAMPLE / 8
        val samplesPerMs = sampleRate / 1000
        return samplesPerMs * bytesPerSample * CHANNELS
    }

    fun getSampleRate(): Int = sampleRate
    fun getNumOfChannels(): Int = CHANNELS
    fun getBytePerSample(): Int = BITS_PER_SAMPLE / 8

    /**
     * 将两个单声道PCM数据混合成双声道
     */
    private fun mixToStereo(leftBuffer: ByteArray, rightBuffer: ByteArray): ByteArray {
        val monoSize = minOf(leftBuffer.size, rightBuffer.size)
        val stereoBuffer = ByteArray(monoSize * 2)
        
        // 16位PCM，每个样本2字节
        for (i in 0 until monoSize step 2) {
            // 左声道样本
            stereoBuffer[i * 2] = leftBuffer[i]
            stereoBuffer[i * 2 + 1] = leftBuffer[i + 1]
            
            // 右声道样本
            stereoBuffer[i * 2 + 2] = rightBuffer[i]
            stereoBuffer[i * 2 + 3] = rightBuffer[i + 1]
        }
        
        return stereoBuffer
    }

    /**
     * The interface On audio read listener.
     */
    interface OnAudioReadListener {
        fun onAudioRead(buffer: ByteArray?, timestamp: Long, isLastFrame: Boolean)
    }

    fun start() {
        if (thread == null) {
            thread = InnerThread()
            thread?.start()
        }
    }

    fun stop() {
        pushing = false
        if (thread != null) {
            try {
                thread?.join()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            } finally {
                thread = null
            }
        }
    }

    private inner class InnerThread : Thread() {
        override fun run() {
            super.run()
            try {
                leftInputStream = context.assets.open(leftFileName)
                rightInputStream = context.assets.open(rightFileName)
            } catch (e: IOException) {
                e.printStackTrace()
                return
            }
            
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            pushing = true

            val startTime = System.currentTimeMillis()
            var sentAudioFrames = 0
            
            while (pushing) {
                val frame = readNextFrame() ?: break
                audioReadListener?.onAudioRead(
                    frame.first,
                    System.currentTimeMillis(),
                    frame.second
                )
                ++sentAudioFrames
                val nextFrameStartTime = sentAudioFrames * interval + startTime
                val now = System.currentTimeMillis()

                if (nextFrameStartTime > now) {
                    val sleepDuration = nextFrameStartTime - now
                    try {
                        sleep(sleepDuration)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                }
            }

            // 关闭输入流
            try {
                leftInputStream?.close()
                rightInputStream?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            } finally {
                leftInputStream = null
                rightInputStream = null
            }
        }

        fun readNextFrame(): Pair<ByteArray, Boolean>? {
            var attemptReopen = false
            do {
                attemptReopen = false
                val monoFrameSize = getMonoBufferSize()
                val stereoFrameSize = getBufferSize()

                if (!loopPlayback) {
                    val leftRemaining = try {
                        leftInputStream?.available() ?: 0
                    } catch (_: Throwable) {
                        0
                    }
                    val rightRemaining = try {
                        rightInputStream?.available() ?: 0
                    } catch (_: Throwable) {
                        0
                    }
                    
                    if (leftRemaining <= 0 || rightRemaining <= 0) {
                        return null
                    }

                    val leftBuffer = ByteArray(monoFrameSize)
                    val rightBuffer = ByteArray(monoFrameSize)
                    
                    var leftTotalRead = 0
                    var rightTotalRead = 0
                    
                    try {
                        // 读取左声道数据
                        while (leftTotalRead < monoFrameSize && leftTotalRead < leftRemaining) {
                            val read = leftInputStream?.read(leftBuffer, leftTotalRead, monoFrameSize - leftTotalRead) ?: -1
                            if (read <= 0) break
                            leftTotalRead += read
                        }
                        
                        // 读取右声道数据
                        while (rightTotalRead < monoFrameSize && rightTotalRead < rightRemaining) {
                            val read = rightInputStream?.read(rightBuffer, rightTotalRead, monoFrameSize - rightTotalRead) ?: -1
                            if (read <= 0) break
                            rightTotalRead += read
                        }
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                    
                    if (leftTotalRead <= 0 || rightTotalRead <= 0) {
                        return null
                    }
                    
                    // 混合成双声道
                    val stereoBuffer = mixToStereo(leftBuffer, rightBuffer)
                    val isLastFrame = leftRemaining <= monoFrameSize || rightRemaining <= monoFrameSize
                    
                    return Pair(stereoBuffer, isLastFrame)
                    
                } else {
                    // 循环播放逻辑
                    val leftRemaining = try {
                        leftInputStream?.available() ?: 0
                    } catch (_: Throwable) {
                        0
                    }
                    val rightRemaining = try {
                        rightInputStream?.available() ?: 0
                    } catch (_: Throwable) {
                        0
                    }
                    
                    if (leftRemaining <= 0 || rightRemaining <= 0) {
                        // 重新打开文件
                        try {
                            leftInputStream?.close()
                            rightInputStream?.close()
                            leftInputStream = context.assets.open(leftFileName)
                            rightInputStream = context.assets.open(rightFileName)
                            attemptReopen = true
                            continue
                        } catch (e: IOException) {
                            e.printStackTrace()
                            return null
                        }
                    }

                    val leftBuffer = ByteArray(monoFrameSize)
                    val rightBuffer = ByteArray(monoFrameSize)
                    
                    var leftTotalRead = 0
                    var rightTotalRead = 0
                    
                    try {
                        // 读取左声道数据
                        while (leftTotalRead < monoFrameSize) {
                            val read = leftInputStream?.read(leftBuffer, leftTotalRead, monoFrameSize - leftTotalRead) ?: -1
                            if (read <= 0) break
                            leftTotalRead += read
                        }
                        
                        // 读取右声道数据
                        while (rightTotalRead < monoFrameSize) {
                            val read = rightInputStream?.read(rightBuffer, rightTotalRead, monoFrameSize - rightTotalRead) ?: -1
                            if (read <= 0) break
                            rightTotalRead += read
                        }
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                    
                    if (leftTotalRead <= 0 || rightTotalRead <= 0) {
                        continue
                    }
                    
                    // 混合成双声道
                    val stereoBuffer = mixToStereo(leftBuffer, rightBuffer)
                    val isLastFrame = leftRemaining <= monoFrameSize || rightRemaining <= monoFrameSize
                    
                    return Pair(stereoBuffer, isLastFrame)
                }
            } while (attemptReopen)
            
            return null
        }
    }
}
