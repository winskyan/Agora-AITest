package io.agora.ai.test.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.lxj.xpopup.XPopup
import io.agora.ai.burst_test.R
import io.agora.ai.burst_test.databinding.ActivityMainBinding
import io.agora.ai.test.agora.IRtcEventCallback
import io.agora.ai.test.agora.RtcManager
import io.agora.ai.test.constants.ExamplesConstants
import io.agora.ai.test.utils.AudioFileReader
import io.agora.ai.test.utils.KeyCenter
import io.agora.ai.test.utils.LogUtils
import io.agora.ai.test.utils.StereoAudioFileReader
import io.agora.ai.test.utils.Utils
import io.agora.rtc2.Constants
import io.agora.rtc2.RtcEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import pub.devrel.easypermissions.AppSettingsDialog
import pub.devrel.easypermissions.EasyPermissions
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity(), IRtcEventCallback {
    companion object {
        const val TAG: String = ExamplesConstants.TAG + "-MainActivity"
        const val MY_PERMISSIONS_REQUEST_CODE = 123
    }

    private lateinit var binding: ActivityMainBinding

    private var mChannelName = "agaa"
    private var mJoinSuccess = false

    private var mAudioFileReader: AudioFileReader? = null
    private var mStereoAudioFileReader: StereoAudioFileReader? = null

    // 双声道音频保存相关
    private var mStereoAudioOutputStream: FileOutputStream? = null
    private var mStereoAudioFile: File? = null

    // 音频测试时间记录
    private var mAudioTestStartTime: Long = 0L
    private var mAudioTestCount: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        checkPermissions()
        initView()
    }

    private fun checkPermissions() {
        val permissions =
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        if (EasyPermissions.hasPermissions(this, *permissions)) {
            // 已经获取到权限，执行相应的操作
            LogUtils.d(TAG, "granted permission")
        } else {
            LogUtils.i(TAG, "requestPermissions")
            EasyPermissions.requestPermissions(
                this,
                "需要录音权限",
                MY_PERMISSIONS_REQUEST_CODE,
                *permissions
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }

    fun onPermissionsGranted(requestCode: Int, perms: MutableList<String>) {
        // 权限被授予，执行相应的操作
        LogUtils.d(TAG, "onPermissionsGranted requestCode:$requestCode perms:$perms")
    }

    fun onPermissionsDenied(requestCode: Int, perms: MutableList<String>) {
        LogUtils.d(TAG, "onPermissionsDenied requestCode:$requestCode perms:$perms")
        // 权限被拒绝，显示一个提示信息
        if (EasyPermissions.somePermissionPermanentlyDenied(this, perms)) {
            // 如果权限被永久拒绝，可以显示一个对话框引导用户去应用设置页面手动授权
            AppSettingsDialog.Builder(this).build().show()
        }
    }

    private fun initView() {
        handleOnBackPressed()
        updateUI()

        val versionStr = "Rtc SDK Version: ${RtcEngine.getSdkVersion()}"
        binding.tvSdkVersion.text = versionStr

        binding.btnJoin.setOnClickListener {
            if (mJoinSuccess) {
                leaveChannel()
            } else {
                joinChannel()
            }
        }

        binding.btnSendAudioMetadata.setOnClickListener {
            RtcManager.sendAudioMetadataEx("aaa".toByteArray())

        }

        binding.btnStopSendAudio.setOnClickListener {
            stopSendAudio()
        }

        binding.btnSendStreamMessage.setOnClickListener {
            RtcManager.sendStreamMessage("aaa".toByteArray())
        }
    }

    private fun joinChannel() {
        var channelName = binding.etChannelName.text.toString()
        if (channelName.isEmpty()) {
            channelName = mChannelName
        }

        // 如果启用了音频测试，记录测试开始时间
        if (ExamplesConstants.ENABLE_AUDIO_TEST) {
            if (mAudioTestStartTime == 0L) {
                mAudioTestStartTime = System.currentTimeMillis()
                LogUtils.i(TAG, "音频测试：开始测试，开始时间: $mAudioTestStartTime")
                // 更新 UI 显示测试已开始
                runOnUiThread {
                    val dateFormat =
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                    val startTimeText = dateFormat.format(Date(mAudioTestStartTime))
                    binding.tvAudioTestInfo.text = "测试已开始...\n测试开始时间: $startTimeText"
                }
            }
            mAudioTestCount++
            LogUtils.i(TAG, "音频测试：开始播放，当前测试次数: $mAudioTestCount")
        }

        RtcManager.initialize(applicationContext, KeyCenter.APP_ID, this)
        RtcManager.joinChannelEx(
            channelName,
            KeyCenter.getRtcUid(),
            KeyCenter.getRtcToken(channelName, KeyCenter.getRtcUid()),
            Constants.CLIENT_ROLE_BROADCASTER
        )
    }

    private fun leaveChannel() {
        stopSendAudio()
        RtcManager.leaveChannel()
    }

    /**
     * 更新测试时间到 UI（不重置开始时间）
     */
    private fun updateTestTimeUI() {
        if (mAudioTestStartTime > 0) {
            val totalTime = System.currentTimeMillis() - mAudioTestStartTime
            val totalSeconds = totalTime / 1000
            val totalMinutes = totalSeconds / 60
            val remainingSeconds = totalSeconds % 60

            // 格式化测试开始时间：年月日时分秒毫秒
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            val startTimeText = dateFormat.format(Date(mAudioTestStartTime))

            val timeText =
                "测试进行中...\n测试开始时间: $startTimeText\n当前测试次数：$mAudioTestCount\n总测试时间: $totalMinutes 分 $remainingSeconds 秒（${totalTime} 毫秒）"
            binding.tvAudioTestInfo.text = timeText
        }
    }

    private fun destroyEngine() {
        // 确保关闭音频文件
        if (ExamplesConstants.ENABLE_STEREO_AUDIO) {
            mStereoAudioFileReader?.stop()
            closeStereoAudioFile()
        } else {
            mAudioFileReader?.stop()
        }

        RtcManager.destroy()
    }

    private fun handleOnBackPressed() {
        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val xPopup = XPopup.Builder(this@MainActivity)
                    .asConfirm("退出", "确认退出程序", {
                        exit()
                    }, {})
                xPopup.show()
            }
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }

    private fun exit() {
        destroyEngine()
        finishAffinity()
        finish()
        exitProcess(0)
    }


    private fun updateUI() {
        binding.btnJoin.text =
            if (mJoinSuccess) getText(R.string.leave) else getText(R.string.join)

    }

    private fun updateToolbarTitle(title: String) {
        binding.toolbarTitle.text = title
    }


    override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
        mJoinSuccess = true
        runOnUiThread {
            updateToolbarTitle("${getString(R.string.app_name)}($channel:$uid)")
            updateUI()
            handleJoinChannelSuccess()
        }
    }

    override fun onLeaveChannelSuccess() {
        LogUtils.d(TAG, "onLeaveChannelSuccess")

        mJoinSuccess = false
        runOnUiThread {
            updateTestTimeUI()
            updateToolbarTitle(getString(R.string.app_name))
            updateUI()
        }
    }

    override fun onUserJoined(uid: Int, elapsed: Int) {
        runOnUiThread {
            //handleJoinChannelSuccess()
        }
    }

    override fun onUserOffline(uid: Int, reason: Int) {
    }

    override fun onPlaybackAudioFrameFinished() {
        LogUtils.i(TAG, "onPlaybackAudioFrameFinished")

        // 如果启用了音频测试，检查文件大小并执行循环测试逻辑
        if (ExamplesConstants.ENABLE_AUDIO_TEST) {
            checkAndExecuteAudioTestLoop()
        }
    }

    private fun stopSendAudio() {
        // 根据开关停止对应的音频读取器
        if (ExamplesConstants.ENABLE_STEREO_AUDIO) {
            mStereoAudioFileReader?.stop()
            // 关闭双声道音频保存文件
            closeStereoAudioFile()
        } else {
            mAudioFileReader?.stop()
        }
    }

    /**
     * 创建双声道音频保存文件
     */
    private fun createStereoAudioFile(): Boolean {
        try {
            // 创建 /cache/dump 目录
            val cacheDir = File(externalCacheDir, "dump")
            if (!cacheDir.exists()) {
                val created = cacheDir.mkdirs()
                LogUtils.d(TAG, "创建目录 ${cacheDir.absolutePath}: $created")
            }

            // 创建文件名（带时间戳）
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val timestamp = dateFormat.format(Date())
            val fileName = "stereo_audio_${timestamp}.pcm"

            mStereoAudioFile = File(cacheDir, fileName)
            mStereoAudioOutputStream = FileOutputStream(mStereoAudioFile)

            LogUtils.d(TAG, "创建双声道音频保存文件: ${mStereoAudioFile?.absolutePath}")
            return true
        } catch (e: IOException) {
            LogUtils.e(TAG, "创建双声道音频文件失败: ${e.message}")
            return false
        }
    }

    /**
     * 保存双声道音频数据
     */
    private fun saveStereoAudioData(buffer: ByteArray) {
        try {
            mStereoAudioOutputStream?.write(buffer)
            mStereoAudioOutputStream?.flush()
        } catch (e: IOException) {
            LogUtils.e(TAG, "保存双声道音频数据失败: ${e.message}")
        }
    }

    /**
     * 关闭双声道音频文件
     */
    private fun closeStereoAudioFile() {
        if (mStereoAudioOutputStream == null) {
            return
        }
        try {
            mStereoAudioOutputStream?.close()
            mStereoAudioOutputStream = null
            LogUtils.d(TAG, "双声道音频文件已保存: ${mStereoAudioFile?.absolutePath}")
            LogUtils.d(TAG, "文件大小: ${mStereoAudioFile?.length() ?: 0} bytes")
        } catch (e: IOException) {
            LogUtils.e(TAG, "关闭双声道音频文件失败: ${e.message}")
        }
    }

    /**
     * 检查文件大小并执行音频测试循环
     * 在收到 onPlaybackAudioFrameFinished 后调用
     * 使用异步操作，等待2秒确保文件写入完成后再检查
     */
    private fun checkAndExecuteAudioTestLoop() {
        // 使用协程异步执行，避免阻塞回调
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                LogUtils.d(
                    TAG,
                    "音频测试：收到 onPlaybackAudioFrameFinished，等待2秒确保文件写入完成"
                )

                // 等待2秒，确保文件写入完成
                delay(2000)

                val audioFileName = RtcManager.getAudioFileName()
                if (audioFileName.isEmpty()) {
                    LogUtils.e(TAG, "音频测试：音频文件路径为空，无法检查")
                    return@launch
                }

                val audioFile = File(audioFileName)
                if (!audioFile.exists()) {
                    LogUtils.e(TAG, "音频测试：音频文件不存在: $audioFileName")
                    return@launch
                }

                val fileSize = audioFile.length()
                LogUtils.d(
                    TAG,
                    "音频测试：等待2秒后检查文件大小 - 期望: ${ExamplesConstants.EXPECTED_PCM_FILE_SIZE_BYTES} bytes, 实际: $fileSize bytes"
                )

                if (fileSize >= ExamplesConstants.EXPECTED_PCM_FILE_SIZE_BYTES) {
                    LogUtils.i(
                        TAG,
                        "音频测试：文件大小匹配（期望: ${ExamplesConstants.EXPECTED_PCM_FILE_SIZE_BYTES} bytes，实际: $fileSize bytes），开始执行循环操作"
                    )
                    // 更新 UI 显示当前测试时间
                    runOnUiThread {
                        updateTestTimeUI()
                    }
                    executeAudioTestLoop()
                } else {
                    LogUtils.w(
                        TAG,
                        "音频测试：文件大小不匹配（期望: ${ExamplesConstants.EXPECTED_PCM_FILE_SIZE_BYTES} bytes，实际: $fileSize bytes），停止测试循环"
                    )
                    runOnUiThread {
                        showAudioTestError("文件大小不匹配：期望 ${ExamplesConstants.EXPECTED_PCM_FILE_SIZE_BYTES} bytes，实际 $fileSize bytes")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                LogUtils.w(TAG, "音频测试：检查文件大小操作被取消")
                throw e
            } catch (e: Exception) {
                LogUtils.e(TAG, "音频测试：检查文件大小异常: ${e.message}", e)
            }
        }
    }

    /**
     * 执行一轮音频测试循环操作
     */
    private fun executeAudioTestLoop() {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                LogUtils.i(TAG, "音频测试：开始执行循环操作")

                // 1. 退出频道
                LogUtils.i(TAG, "音频测试：退出频道")
                leaveChannel()

                // 等待退出完成
                ensureActive()
                delay(1000)
                LogUtils.d(TAG, "音频测试：退出频道等待完成")

                // 2. 执行 destroy
                LogUtils.i(TAG, "音频测试：执行 destroy")
                destroyEngine()

                // 3. 删除日志文件
                LogUtils.i(TAG, "音频测试：删除日志文件")
                Utils.deleteFiles(LogUtils.getLogDir().absolutePath)
                LogUtils.d(TAG, "音频测试：日志文件删除完成")

                // 4. 等待 10 秒
                val waitTimeMs = ExamplesConstants.AUTO_LOOP_WAIT_TIME_MS
                LogUtils.i(
                    TAG,
                    "音频测试：等待 $waitTimeMs 毫秒（${waitTimeMs / 1000} 秒）后重新加入频道"
                )
                val startTime = System.currentTimeMillis()
                delay(waitTimeMs)
                val elapsedTime = System.currentTimeMillis() - startTime
                LogUtils.i(TAG, "音频测试：等待完成，实际等待时间: $elapsedTime 毫秒")

                // 6. 重新初始化并加入频道
                LogUtils.i(TAG, "音频测试：重新初始化并加入频道")
                runOnUiThread {
                    joinChannel()
                    // 更新测试时间显示
                    updateTestTimeUI()
                    LogUtils.i(
                        TAG,
                        "音频测试：循环操作完成，已启动重新加入频道流程，等待加入成功并接收音频数据..."
                    )
                }


            } catch (e: kotlinx.coroutines.CancellationException) {
                LogUtils.w(TAG, "音频测试：协程被取消")
                throw e
            } catch (e: Exception) {
                LogUtils.e(TAG, "音频测试：循环操作异常: ${e.message}", e)
                runOnUiThread {
                    showAudioTestError("测试循环异常: ${e.message}")
                }
            }
        }
    }


    /**
     * 显示音频测试错误信息
     */
    private fun showAudioTestError(message: String) {
        LogUtils.e(TAG, "音频测试错误: $message")

        // 计算测试总时间
        var errorText = "错误: $message"
        if (mAudioTestStartTime > 0) {
            val totalTime = System.currentTimeMillis() - mAudioTestStartTime
            val totalSeconds = totalTime / 1000
            val totalMinutes = totalSeconds / 60
            val remainingSeconds = totalSeconds % 60

            // 格式化测试开始时间：年月日时分秒毫秒
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
            val startTimeText = dateFormat.format(Date(mAudioTestStartTime))

            val timeText =
                "\n测试开始时间: $startTimeText\n当前测试次数：$mAudioTestCount\n总测试时间: $totalMinutes 分 $remainingSeconds 秒（${totalTime} 毫秒）"
            errorText += timeText
            LogUtils.e(TAG, "音频测试错误: $message，$timeText")
            mAudioTestStartTime = 0L // 重置开始时间
        }

        // 更新 UI
        runOnUiThread {
            binding.tvAudioTestInfo.text = errorText
        }

        // 可以在这里添加 UI 提示，比如 Toast 或 Dialog
        // Toast.makeText(this, "音频测试错误: $message", Toast.LENGTH_LONG).show()
    }


    private fun handleJoinChannelSuccess() {
        CoroutineScope(Dispatchers.IO).launch {
            val interval = 50 // ms

            if (ExamplesConstants.ENABLE_STEREO_AUDIO) {
                // 双声道模式
                LogUtils.d(TAG, "启动双声道音频模式")
                val sampleRate = 16000 // 16kHz采样率

                // 创建保存文件
                if (!createStereoAudioFile()) {
                    LogUtils.e(TAG, "创建双声道音频保存文件失败")
                }

                mStereoAudioFileReader = StereoAudioFileReader(
                    applicationContext,
                    "left_audio_16k_1ch.pcm",  // 左声道文件
                    "right_audio_16k_1ch.pcm", // 右声道文件
                    sampleRate,
                    interval,
                    true, // 循环播放
                    object : StereoAudioFileReader.OnAudioReadListener {
                        override fun onAudioRead(
                            buffer: ByteArray?,
                            timestamp: Long,
                            isLastFrame: Boolean
                        ) {
                            if (buffer != null) {
                                // 保存混合后的双声道音频数据到文件
                                saveStereoAudioData(buffer)

                                mStereoAudioFileReader?.let {
                                    RtcManager.pushExternalAudioFrame(
                                        buffer,
                                        it.getSampleRate(),
                                        it.getNumOfChannels(), // 这里会返回2（双声道）
                                        isLastFrame
                                    )
                                }
                            }
                        }
                    })
                mStereoAudioFileReader?.start()
            } else {
                // 单声道模式（原有逻辑）
                LogUtils.d(TAG, "启动单声道音频模式")
                val sampleRate = 48000 // 48kHz采样率

                mAudioFileReader = AudioFileReader(
                    applicationContext,
                    "tts_out_48k_1ch.pcm",
                    sampleRate,
                    1, // 单声道
                    interval,
                    true, // 循环播放
                    object : AudioFileReader.OnAudioReadListener {
                        override fun onAudioRead(
                            buffer: ByteArray?,
                            timestamp: Long,
                            isLastFrame: Boolean
                        ) {
                            if (buffer != null) {
                                mAudioFileReader?.let {
                                    RtcManager.pushExternalAudioFrame(
                                        buffer,
                                        it.getSampleRate(),
                                        it.getNumOfChannels(), // 这里会返回1（单声道）
                                        isLastFrame
                                    )
                                }
                            }
                        }
                    })
                mAudioFileReader?.start()
            }
        }
    }
}