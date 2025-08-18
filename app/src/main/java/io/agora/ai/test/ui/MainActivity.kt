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
import io.agora.ai.test.utils.Utils
import io.agora.rtc2.Constants
import io.agora.rtc2.RtcEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import pub.devrel.easypermissions.AppSettingsDialog
import pub.devrel.easypermissions.EasyPermissions
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity(), IRtcEventCallback {
    companion object {
        const val TAG: String = ExamplesConstants.TAG + "-MainActivity"
        const val MY_PERMISSIONS_REQUEST_CODE = 123
    }

    private lateinit var binding: ActivityMainBinding

    private var mChannelName = "wei1000"
    private var mJoinSuccess = false

    private var mAudioFileReader: AudioFileReader? = null

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
                mAudioFileReader?.stop()
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

        }
    }

    override fun onUserOffline(uid: Int, reason: Int) {
    }

    override fun onPlaybackAudioFrameFinished() {
        LogUtils.i(TAG, "onPlaybackAudioFrameFinished")
    }


    private fun handleJoinChannelSuccess() {
        CoroutineScope(Dispatchers.IO).launch {
            val fileBytes =
                Utils.readAssetBytesContent(applicationContext, "tts_out_48k_1ch.pcm")
            LogUtils.d(TAG, "readAssetBytesContent fileBytes:${fileBytes.size}")
            RtcManager.pushExternalAudioFrame(
                fileBytes,
                48000, 1, true
            )

            mAudioFileReader = AudioFileReader(
                applicationContext,
                "tts_out_48k_1ch.pcm", 48000, 1, 10,
                true,
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
                                    it.getNumOfChannels(),
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