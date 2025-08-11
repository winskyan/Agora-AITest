package io.agora.ai.test.ui

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import kotlin.system.exitProcess

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        setupExceptionHandler()
        val pid = android.os.Process.myPid()
        Log.d("AgoraAITest", "Application onCreate, 当前进程ID: $pid")
        val processName = getProcessNameCompat()
        val mainProcessName = packageName
        if (processName == mainProcessName) {
            Log.d("AgoraAITest", "当前进程是主进程")
        } else {
            Log.d("AgoraAITest", "当前进程不是主进程，进程名: $processName")
        }
    }

    private fun setupExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("AgoraAITest", "Uncaught exception in thread: ${thread.name}", throwable)
            // 在这里可以添加更多的错误处理逻辑，例如上报错误等

            // 结束当前进程
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(10) // 10 是一个非零的退出码，表示异常退出
        }
    }

    @Suppress("DEPRECATION")
    private fun getProcessNameCompat(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }
        try {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
            manager?.runningAppProcesses?.forEach { processInfo ->
                if (processInfo.pid == android.os.Process.myPid()) {
                    return processInfo.processName
                }
            }
        } catch (e: Exception) {
            Log.e("AgoraAITest", "Failed to get process name", e)
        }
        return null
    }
}