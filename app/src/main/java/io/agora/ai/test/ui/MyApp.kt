package io.agora.ai.test.ui

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import io.agora.ai.test.utils.LogUtils
import kotlin.system.exitProcess

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LogUtils.init(this)
        setupExceptionHandler()
        val pid = android.os.Process.myPid()
        LogUtils.d("AgoraAITest", "Application onCreate, 当前进程ID: $pid")
        val processName = getProcessNameCompat()
        val mainProcessName = packageName
        if (processName == mainProcessName) {
            LogUtils.d("AgoraAITest", "当前进程是主进程")
        } else {
            LogUtils.d("AgoraAITest", "当前进程不是主进程，进程名: $processName")
        }
    }

    private fun setupExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            LogUtils.e("AgoraAITest", "Uncaught exception in thread: ${thread.name}", throwable)
            // 在这里可以添加更多的错误处理逻辑，例如上报错误等

            // 结束当前进程
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(10) // 10 是一个非零的退出码，表示异常退出
        }
    }

    @Suppress("DEPRECATION")
    private fun getProcessNameCompat(): String? {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val method = Application::class.java.getDeclaredMethod("getProcessName")
                return method.invoke(null) as? String
            } catch (_: Throwable) {
            }
        }
        try {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
            manager?.runningAppProcesses?.forEach { processInfo ->
                if (processInfo.pid == android.os.Process.myPid()) {
                    return processInfo.processName
                }
            }
        } catch (e: Exception) {
            LogUtils.e("AgoraAITest", "Failed to get process name", e)
        }
        return null
    }
}