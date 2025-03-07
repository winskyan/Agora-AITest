package io.agora.ai.rtm.test.ui

import android.Manifest
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.lxj.xpopup.XPopup
import io.agora.ai.rtm.test.R
import io.agora.ai.rtm.test.constants.Constants
import io.agora.ai.rtm.test.databinding.ActivityMainBinding
import io.agora.ai.rtm.test.rtm.RtmManager
import io.agora.ai.rtm.test.utils.KeyCenter
import pub.devrel.easypermissions.AppSettingsDialog
import pub.devrel.easypermissions.EasyPermissions
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity(), RtmManager.RtmMessageListener {
    companion object {
        const val TAG: String = Constants.TAG + "-MainActivity"
        const val MY_PERMISSIONS_REQUEST_CODE = 123

        const val RTM_TEST_MAX_COUNT = 100
    }

    private lateinit var binding: ActivityMainBinding

    private var mChannelName = "testAga"
    private var mJoinSuccess = false

    private var mLoginTime = 0L
    private var mSendRtmMessageTime = 0L
    private var mSendRtmMessage = ""


    private var mHistoryFileName = ""
    private val logExecutor = Executors.newSingleThreadExecutor()
    private lateinit var bufferedWriter: BufferedWriter


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        checkPermissions()
        initData()
        initView()
        RtmManager.create(KeyCenter.APP_ID, KeyCenter.getRtmUid().toString(), this)
    }

    override fun onDestroy() {
        super.onDestroy()
        RtmManager.release()
    }

    private fun initWriter() {
        val cacheDir = externalCacheDir
        val logFile = File(cacheDir, mHistoryFileName)
        try {
            bufferedWriter = BufferedWriter(FileWriter(logFile, true))
        } catch (e: IOException) {
            Log.e(TAG, "Error creating BufferedWriter", e)
        }
    }

    private fun closeWriter() {
        try {
            bufferedWriter.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing BufferedWriter", e)
        }
    }


    private fun checkPermissions() {
        val permissions =
            arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        if (EasyPermissions.hasPermissions(this, *permissions)) {
            // 已经获取到权限，执行相应的操作
            Log.d(TAG, "granted permission")
        } else {
            Log.i(TAG, "requestPermissions")
            EasyPermissions.requestPermissions(
                this,
                "需要写文件权限",
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
        Log.d(TAG, "onPermissionsGranted requestCode:$requestCode perms:$perms")
    }

    fun onPermissionsDenied(requestCode: Int, perms: MutableList<String>) {
        Log.d(TAG, "onPermissionsDenied requestCode:$requestCode perms:$perms")
        // 权限被拒绝，显示一个提示信息
        if (EasyPermissions.somePermissionPermanentlyDenied(this, perms)) {
            // 如果权限被永久拒绝，可以显示一个对话框引导用户去应用设置页面手动授权
            AppSettingsDialog.Builder(this).build().show()
        }
    }

    private fun initData() {
        mSendRtmMessageTime = 0L
        mSendRtmMessage = ""
    }


    private fun initView() {
        handleOnBackPressed()
        updateUI()

        binding.btnJoin.setOnClickListener {
            if (mJoinSuccess) {
                RtmManager.unsubscribeMessageChannel(mChannelName)
                RtmManager.rtmLogout()
                closeWriter()
            } else {
                initData()
                val channelName = binding.etChannelName.text.toString()
                if (channelName.isNotEmpty()) {
                    mChannelName = channelName
                }
                mHistoryFileName =
                    "history-${mChannelName}-${KeyCenter.getRtcUid()}-${System.currentTimeMillis()}.txt"
                initWriter()
                mLoginTime = System.currentTimeMillis()
                RtmManager.login(KeyCenter.getRtmToken2(KeyCenter.getRtmUid()))
            }

        }

        binding.btnSendRtmMessage.setOnClickListener {
            sendRtmMessages()
        }

        binding.tvHistory.movementMethod = ScrollingMovementMethod.getInstance()

        binding.btnClear.setOnClickListener {
            binding.tvHistory.text = ""
        }
    }


    private fun sendRtmMessages() {
        mSendRtmMessageTime = System.currentTimeMillis()
        mSendRtmMessage = "rtmMessage:$mSendRtmMessageTime"

        val ret =
            RtmManager.sendRtmMessage(mSendRtmMessage.toByteArray(Charsets.UTF_8), mChannelName)
        if (ret != 0) {
            Log.e(TAG, "sendRtmMessage failed")
            return
        }
        updateHistoryUI("SendRtmMessage:$mSendRtmMessage")
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
        finishAffinity()
        finish()
        exitProcess(0)
    }

    private fun updateUI() {
        binding.btnSendRtmMessage.isEnabled = mJoinSuccess

        binding.btnJoin.text =
            if (mJoinSuccess) getText(R.string.leave) else getText(R.string.join)
    }

    private fun updateToolbarTitle(title: String) {
        binding.toolbarTitle.text = title
    }

    private fun updateHistoryUI(message: String) {
        runOnUiThread {
            binding.tvHistory.append("\r\n\r\n$message")
            val scrollAmount =
                binding.tvHistory.layout.getLineTop(binding.tvHistory.lineCount) - binding.tvHistory.height
            if (scrollAmount > 0) {
                binding.tvHistory.scrollTo(0, scrollAmount)
            } else {
                binding.tvHistory.scrollTo(0, 0)
            }
            writeMessageToFile(message)
        }
    }

    private fun writeMessageToFile(message: String) {
        logExecutor.execute {
            try {
                bufferedWriter.append(message).append("\n")
                bufferedWriter.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Error writing message to file", e)
            }
        }
    }

    override fun onRtmMessageReceived(message: String) {
        Log.d(TAG, "onRtmMessageReceived  message:$message ")
        if (0L != mSendRtmMessageTime && message == mSendRtmMessage) {
            val currentTime = System.currentTimeMillis()
            val receiverMessageDiff =
                "$currentTime:ReceiveRtmMessage:$message diff:${currentTime - mSendRtmMessageTime} ms"
            val receiverMessageFromLoginDiff =
                "$currentTime:ReceiveRtmMessage $message  from login diff:${currentTime - mLoginTime} ms"
            Log.d(TAG, receiverMessageDiff)
            updateHistoryUI(receiverMessageDiff)
            Log.d(TAG, receiverMessageFromLoginDiff)
            updateHistoryUI(receiverMessageFromLoginDiff)
        } else {
            updateHistoryUI("ReceiveRtmMessage:$message")
        }
    }

    override fun onRtmConnected() {
        Log.d(TAG, "onRtmConnected")
        mJoinSuccess = true
        runOnUiThread {
            updateUI()
            val loginConnectedDiff =
                "loginConnectedDiff:${System.currentTimeMillis() - mLoginTime} ms"
            Log.d(TAG, loginConnectedDiff)
            updateHistoryUI(loginConnectedDiff)
            RtmManager.subscribeMessageChannel(mChannelName)
        }
    }

    override fun onRtmDisconnected() {
        mJoinSuccess = false
        runOnUiThread {
            updateUI()
        }
    }

    override fun onRtmSubscribed() {
        sendRtmMessages()
    }
}