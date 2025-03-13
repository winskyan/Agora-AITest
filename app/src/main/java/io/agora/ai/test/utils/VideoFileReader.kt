package io.agora.ai.test.utils

import android.content.Context
import java.io.IOException
import java.io.InputStream
import kotlin.concurrent.Volatile
import kotlin.math.max

/**
 * The type Video file reader.
 */
class VideoFileReader(
    private var context: Context,
    private var rawVideoPath: String,
    private var videoWidth: Int,
    private var videoHeight: Int,
    private var fps: Float,
    private var videoReadListener: OnVideoReadListener
) {
    /**
     * The interface On video read listener.
     */
    interface OnVideoReadListener {
        /**
         * On video read.
         *
         * @param buffer the buffer
         * @param width  the width
         * @param height the height
         * @param frameIndex the frame index
         */
        fun onVideoRead(buffer: ByteArray?, width: Int, height: Int, frameIndex: Int)
    }

    private var rawVideoFrameSize = 0
    private var rawVideoFrameIntervalNs = 0L

    init {
        rawVideoFrameSize = videoWidth * videoHeight / 2 * 3
        rawVideoFrameIntervalNs = (1000 * 1000 * 1000 / fps).toLong()
    }


    @Volatile
    private var pushing = false
    private var inputStream: InputStream? = null
    private var thread: InnerThread? = null


    /**
     * Start.
     */
    fun start() {
        if (thread != null) {
            return
        }
        thread = InnerThread()
        thread?.start()
    }

    /**
     * Stop.
     */
    fun stop() {
        if (thread != null) {
            pushing = false
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
                inputStream = context.assets.open(rawVideoPath)
            } catch (e: IOException) {
                e.printStackTrace()
            }

            pushing = true
            val buffer = ByteArray(rawVideoFrameSize)
            var frameIndex = 0
            while (pushing) {
                val start = System.nanoTime()
                try {
                    var read = inputStream?.read(buffer) ?: -1
                    while (read < 0) {
                        inputStream?.reset()
                        frameIndex = 0
                        read = inputStream?.read(buffer) ?: -1
                    }

                } catch (e: IOException) {
                    e.printStackTrace()
                }
                videoReadListener.onVideoRead(buffer, videoWidth, videoHeight, frameIndex)
                frameIndex++
                val consume = System.nanoTime() - start

                try {
                    sleep(
                        max(
                            0.0,
                            ((rawVideoFrameIntervalNs - consume) / 1000 / 1000).toDouble()
                        ).toLong()
                    )
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                }
            }

            if (inputStream != null) {
                try {
                    inputStream?.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                } finally {
                    inputStream = null
                }
            }
        }
    }
}
