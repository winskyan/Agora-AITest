package io.agora.ai.test.utils

import android.content.Context
import android.util.Log
import io.agora.ai.test.constants.ExamplesConstants
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object LogUtils {
    private const val DEFAULT_TAG = ExamplesConstants.TAG + "-LogUtils"

    @Volatile
    private var initialized: Boolean = false

    private lateinit var appContext: Context
    private val logExecutor = Executors.newSingleThreadExecutor()

    private val dateFormatter by lazy {
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    }
    private val timeFormatter by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    }

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        // 确保日志目录存在
        getLogDir().mkdirs()
        initialized = true
        d(DEFAULT_TAG, "LogUtils initialized. Log dir: ${getLogDir().absolutePath}")
    }

    fun getLogDir(): File {
        // 应用专属外部存储，不需要存储权限
        val base = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        return File(base, "logs")
    }

    private fun currentLogFile(): File {
        val name = "app-${dateFormatter.format(Date())}.log"
        return File(getLogDir(), name)
    }

    private fun writeToFile(level: String, tag: String, message: String, tr: Throwable? = null) {
        if (!initialized) return
        val logLine = buildString {
            append(timeFormatter.format(Date()))
            append(" ")
            append(level)
            append("/")
            append(tag)
            append(" [")
            append(Thread.currentThread().name)
            append("] ")
            append(message)
        }

        logExecutor.execute {
            try {
                val file = currentLogFile()
                BufferedWriter(FileWriter(file, true)).use { writer ->
                    writer.append(logLine)
                    writer.append('\n')
                    if (tr != null) {
                        val sw = StringWriter()
                        tr.printStackTrace(PrintWriter(sw))
                        writer.append(sw.toString())
                        writer.append('\n')
                    }
                }
            } catch (_: Throwable) {
                // 忽略文件写入异常，避免影响主逻辑
            }
        }
    }

    fun d(tag: String = DEFAULT_TAG, msg: String) {
        Log.d(tag, msg)
        writeToFile("D", tag, msg)
    }

    fun i(tag: String = DEFAULT_TAG, msg: String) {
        Log.i(tag, msg)
        writeToFile("I", tag, msg)
    }

    fun w(tag: String = DEFAULT_TAG, msg: String, tr: Throwable? = null) {
        Log.w(tag, msg, tr)
        writeToFile("W", tag, msg, tr)
    }

    fun e(tag: String = DEFAULT_TAG, msg: String, tr: Throwable? = null) {
        Log.e(tag, msg, tr)
        writeToFile("E", tag, msg, tr)
    }

    fun shutdown() {
        try {
            logExecutor.shutdown()
            logExecutor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: Throwable) {
        }
    }


}


