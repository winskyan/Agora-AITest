package io.agora.ai.test.utils

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.View
import android.view.inputmethod.InputMethodManager
import io.agora.ai.test.constants.ExamplesConstants

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import java.util.UUID

object Utils {
    const val TAG: String = ExamplesConstants.TAG + "-Utils"
    fun getRandomString(length: Int): String {
        val str = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val random = Random()
        val sb = StringBuffer()
        for (i in 0 until length) {
            val number = random.nextInt(62)
            sb.append(str[number])
        }
        return sb.toString()
    }

    fun readAssetContent(context: Context, fileName: String): String {
        val content: String

        try {
            val assetManager = context.assets
            val inputStream: InputStream = assetManager.open(fileName)
            val inputStreamReader = InputStreamReader(inputStream)
            content = inputStreamReader.readText()
            inputStreamReader.close()
        } catch (e: IOException) {
            e.printStackTrace()
            return ""
        }

        return content
    }

    fun readAssetBytesContent(context: Context, fileName: String): ByteArray {
        return try {
            context.assets.open(fileName).use { inputStream ->
                inputStream.readBytes()
            }
        } catch (e: IOException) {
            e.printStackTrace()
            ByteArray(0)
        }
    }

    fun hideKeyboard(context: Context, view: View) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    fun toDp(value: Int): Int {
        val density = Resources.getSystem().displayMetrics.density
        return (value * density).toInt()
    }

    fun getUuidId(): String {
        return UUID.randomUUID().toString().replace("-", "")
    }

    fun addChineseQuotes(str: String): String {
        return "“$str”"
    }

    fun removeChineseQuotes(str: String): String {
        return if (str.isNotEmpty()) {
            if (str.startsWith("“") && str.endsWith("”")) {
                str.substring(1, str.length - 1)
            } else if (str.startsWith("“") && !str.endsWith("”")) {
                str.substring(1, str.length)
            } else if (!str.startsWith("“") && str.endsWith("”")) {
                str.substring(0, str.length - 1)
            } else if (str.startsWith("\"") && str.endsWith("\"")) {
                str.substring(1, str.length - 1)
            } else if (str.startsWith("\"") && !str.endsWith("\"")) {
                str.substring(1, str.length)
            } else if (!str.startsWith("\"") && str.endsWith("\"")) {
                str.substring(0, str.length - 1)
            } else {
                str
            }
        } else {
            str
        }
    }

    fun getCurrentDateStr(pattern: String): String {
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
    }

    fun isNetworkConnected(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val networkCapabilities =
            connectivityManager.getNetworkCapabilities(network) ?: return false

        return networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun getAllInterfaceMethodNames(interfaceType: Class<*>): List<String> {
        val methodNames = mutableListOf<String>()
        // 获取接口声明的所有方法，包括从父接口继承的方法
        val methods = interfaceType.declaredMethods

        // 遍历所有方法
        for (method in methods) {
            // 将方法名称添加到列表中
            methodNames.add(method.name)
        }

        return methodNames
    }

    fun assert(condition: Boolean, message: String) {
        if (!condition) {
            throw IllegalArgumentException(message)
        }
    }

    fun getContext(): Context {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentActivityThreadMethod =
            activityThreadClass.getDeclaredMethod("currentActivityThread")
        currentActivityThreadMethod.isAccessible = true
        val currentActivityThread = currentActivityThreadMethod.invoke(null)
        val getApplicationMethod = activityThreadClass.getDeclaredMethod("getApplication")
        getApplicationMethod.isAccessible = true
        val application = getApplicationMethod.invoke(currentActivityThread) as Application
        return application
    }

    fun getScreenHeight(context: Context): Int {
        val displayMetrics = context.resources.displayMetrics
        return displayMetrics.heightPixels
    }

    fun copyToClipboard(context: Context, text: String) {
        // 获取剪贴板管理器
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        // 创建一个包含字符串的 ClipData 对象
        val clip = ClipData.newPlainText("label", text)

        // 将 ClipData 设置到剪贴板
        clipboard.setPrimaryClip(clip)

    }

    /**
     * 删除日志目录下的所有文件和文件夹（包括子目录）
     */
    fun deleteFiles(filePath: String) {
        try {
            LogUtils.i(TAG, "开始删除日志文件 : $filePath")
            val logDir = File(filePath)
            if (logDir.exists() && logDir.isDirectory) {
                val result = deleteFilesAndDirsRecursively(logDir)
                LogUtils.d(
                    TAG,
                    "删除了 ${result.fileCount} 个文件和 ${result.dirCount} 个文件夹"
                )
            } else {
                LogUtils.w(TAG, "日志目录不存在: ${logDir.absolutePath}")
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "删除日志文件失败: ${e.message}", e)
        }
    }

    /**
     * 删除结果数据类
     */
    private data class DeleteResult(val fileCount: Int, val dirCount: Int)

    /**
     * 递归删除目录下的所有文件和文件夹（包括子目录）
     * @param directory 要删除内容的目录（不删除此目录本身）
     * @return 删除的文件和目录数量
     */
    private fun deleteFilesAndDirsRecursively(directory: File): DeleteResult {
        var fileCount = 0
        var dirCount = 0
        try {
            val files = directory.listFiles()
            if (files != null) {
                for (file in files) {
                    if (file.isDirectory) {
                        // 先递归删除子目录中的所有内容
                        val subResult = deleteFilesAndDirsRecursively(file)
                        fileCount += subResult.fileCount
                        dirCount += subResult.dirCount
                        LogUtils.d(TAG, "删除目录 : ${file.absolutePath}")
                        // 删除空目录
                        if (file.delete()) {
                            dirCount++
                        }
                    } else if (file.isFile) {
                        LogUtils.d(TAG, "删除文件 : ${file.absolutePath}")
                        // 删除文件
                        if (file.delete()) {
                            fileCount++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "删除文件/目录时出错: ${directory.absolutePath}, ${e.message}", e)
        }
        return DeleteResult(fileCount, dirCount)
    }

}