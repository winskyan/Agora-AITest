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
import io.agora.rtc2.Constants
import io.agora.rtc2.RtcEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
                // 根据开关停止对应的音频读取器
                if (ExamplesConstants.ENABLE_STEREO_AUDIO) {
                    mStereoAudioFileReader?.stop()
                    // 关闭双声道音频保存文件
                    closeStereoAudioFile()
                } else {
                    mAudioFileReader?.stop()
                }
                RtcManager.leaveChannel()
            } else {
                var channelName = binding.etChannelName.text.toString()
                if (channelName.isEmpty()) {
                    channelName = mChannelName
                }

                RtcManager.initialize(applicationContext, KeyCenter.APP_ID, this)
                RtcManager.joinChannelEx(
                    channelName,
                    KeyCenter.getRtcUid(),
                    KeyCenter.getRtcToken(channelName, KeyCenter.getRtcUid()),
                    Constants.CLIENT_ROLE_BROADCASTER
                )
            }
        }

        binding.btnSendAudioMetadata.setOnClickListener {
            RtcManager.sendAudioMetadataEx("aaa".toByteArray())

        }
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
        // 确保关闭音频文件
        if (ExamplesConstants.ENABLE_STEREO_AUDIO) {
            mStereoAudioFileReader?.stop()
            closeStereoAudioFile()
        } else {
            mAudioFileReader?.stop()
        }

        RtcManager.destroy()
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
        try {
            mStereoAudioOutputStream?.close()
            mStereoAudioOutputStream = null
            LogUtils.d(TAG, "双声道音频文件已保存: ${mStereoAudioFile?.absolutePath}")
            LogUtils.d(TAG, "文件大小: ${mStereoAudioFile?.length() ?: 0} bytes")
        } catch (e: IOException) {
            LogUtils.e(TAG, "关闭双声道音频文件失败: ${e.message}")
        }
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