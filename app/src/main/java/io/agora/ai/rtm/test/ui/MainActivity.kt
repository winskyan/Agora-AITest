package io.agora.ai.rtm.test.ui

import android.Manifest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.lxj.xpopup.XPopup
import io.agora.ai.rtm.test.constants.Constants
import io.agora.ai.rtm.test.databinding.ActivityMainBinding
import io.agora.ai.rtm.test.rtm.RtmManager
import io.agora.ai.rtm.test.utils.KeyCenter
import io.agora.ai.rtm.test.ws.WSManager
import pub.devrel.easypermissions.AppSettingsDialog
import pub.devrel.easypermissions.EasyPermissions
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity(), RtmManager.RtmMessageListener,
    WSManager.WSMessageListener {
    companion object {
        const val TAG: String = Constants.TAG + "-MainActivity"
        const val MY_PERMISSIONS_REQUEST_CODE = 123
        const val DEFAULT_TEST_COUNT = 100
        const val MAX_IN_CHANNEL_TEST_COUNT = 20
        const val INTERVAL_LOOP_WAIT = 10 * 1000L
    }

    private lateinit var binding: ActivityMainBinding

    private var mChannelName = "wei888"

    //private var mWsUrl = "wss://echo.websocket.org"
    private var mWsUrl = "wss://108.129.196.84:8765"

    private var mLoginTime = 0L
    private var mSendMessageTime = 0L
    private var mSendMessage = ""

    private var loginConnectedDiffSum = 0L
    private var receiverMessageDiffSum = 0L
    private var receiverMessageFromLoginDiffSum = 0L
    private var testCount = 0

    private var receiverMessageInChannelDiffSum = 0L

    private var remainingTests = 0
    private var remainInChannelTestCount = 0

    private var mHistoryFileName = ""
    private val logExecutor = Executors.newSingleThreadExecutor()
    private lateinit var bufferedWriter: BufferedWriter


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        checkPermissions()
        initView()
        RtmManager.create(KeyCenter.APP_ID, KeyCenter.getRtmUid().toString(), this)
        WSManager.create(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        RtmManager.release()
        closeWriter()
    }

    private fun initWriter() {
        //closeWriter()

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

    private fun resetData() {
        mSendMessageTime = 0L
        mSendMessage = ""
    }

    private fun resetTestStats() {
        loginConnectedDiffSum = 0L
        receiverMessageDiffSum = 0L
        receiverMessageFromLoginDiffSum = 0L
        testCount = 0
    }

    private fun initView() {
        handleOnBackPressed()

        binding.btnStop.setOnClickListener {
            remainingTests = 0
        }

        binding.btnRtmTest.setOnClickListener {
            val channelName = binding.etChannelName.text.toString()
            if (channelName.isNotEmpty()) {
                mChannelName = channelName
            }
            mHistoryFileName =
                "history-rtm-${mChannelName}-${KeyCenter.getRtmUid()}-${System.currentTimeMillis()}.txt"

            remainingTests = run {
                val customCount = binding.etCustomCount.text.toString()
                if (customCount.isEmpty()) DEFAULT_TEST_COUNT else customCount.toInt()
            }
            Log.d(TAG, "remainingTests:$remainingTests")

            initWriter()
            resetTestStats()
            startRtmTestCycle(true)
        }

        binding.btnWsTest.setOnClickListener {
            val wsUrl = binding.etWsUrl.text.toString()
            if (wsUrl.isNotEmpty()) {
                mWsUrl = wsUrl
            }
            mHistoryFileName =
                "history-ws-${System.currentTimeMillis()}.txt"

            remainingTests = run {
                val customCount = binding.etCustomCount.text.toString()
                if (customCount.isEmpty()) DEFAULT_TEST_COUNT else customCount.toInt()
            }
            Log.d(TAG, "remainingTests:$remainingTests")

            initWriter()
            resetTestStats()
            startWsTestCycle(true)
        }

        binding.tvHistory.movementMethod = ScrollingMovementMethod.getInstance()
    }

    private fun startRtmTestCycle(firstTest: Boolean = false) {
        if (remainingTests > 0) {
            runOnUiThread { binding.btnRtmTest.isEnabled = false }
            var delayTime = INTERVAL_LOOP_WAIT
            if (firstTest) {
                delayTime = 0
            }
            remainingTests--
            Handler(Looper.getMainLooper()).postDelayed({
                updateHistoryUI("Rtm Test Start remainingTests:$remainingTests")
                loginRtm()
            }, delayTime)
        } else {
            runOnUiThread {
                binding.btnRtmTest.isEnabled = true
                updateHistoryUI("Rtm Test End")
            }

        }
    }

    private fun loginRtm() {
        updateLoginTime()
        RtmManager.login(KeyCenter.getRtmToken2(KeyCenter.getRtmUid()))
    }

    private fun logoutRtm() {
        RtmManager.unsubscribeMessageChannel(mChannelName)
        RtmManager.rtmLogout()
    }


    private fun sendRtmMessages() {
        remainInChannelTestCount--
        mSendMessageTime = System.currentTimeMillis()
        mSendMessage = "rtmMessage$mSendMessageTime"

        val ret =
            RtmManager.sendRtmMessage(mSendMessage.toByteArray(Charsets.UTF_8), mChannelName)
        if (ret != 0) {
            Log.e(TAG, "sendRtmMessage failed")
            return
        }
        updateHistoryUI("SendRtmMessage:$mSendMessage")
    }

    private fun updateTestAverage() {
        val averageDiff =
            "login Connected diff average:${loginConnectedDiffSum / testCount}ms,echo message average:${receiverMessageDiffSum / testCount}ms,receiver first echo message from login average:${receiverMessageFromLoginDiffSum / testCount}ms,test count:$testCount"
        updateHistoryUI(averageDiff)
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

    private fun updateToolbarTitle(title: String) {
        binding.toolbarTitle.text = title
    }

    private fun updateHistoryUI(message: String) {
        Log.d(TAG, message)
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

    private fun updateLoginTime() {
        resetData()

        mLoginTime = System.currentTimeMillis()
        remainInChannelTestCount = MAX_IN_CHANNEL_TEST_COUNT
        receiverMessageInChannelDiffSum = 0L
    }

    override fun onRtmMessageReceived(message: String) {
        Log.d(TAG, "onRtmMessageReceived  message:$message ")
        if (0L != mSendMessageTime && message == mSendMessage) {
            val currentTime = System.currentTimeMillis()
            val sendRtmMessageDiff = currentTime - mSendMessageTime;
            receiverMessageInChannelDiffSum += sendRtmMessageDiff
            val receiverMessageInChannelAverageDiff =
                receiverMessageInChannelDiffSum / (MAX_IN_CHANNEL_TEST_COUNT - remainInChannelTestCount)
            val sendRtmMessageDiffStr =
                "ReceiveRtmMessage:$mSendMessage diff:${sendRtmMessageDiff}ms average:${receiverMessageInChannelAverageDiff}ms"
            updateHistoryUI(sendRtmMessageDiffStr)

            if (remainInChannelTestCount == 0) {
                receiverMessageDiffSum += receiverMessageInChannelAverageDiff
            }

            if (remainInChannelTestCount == MAX_IN_CHANNEL_TEST_COUNT - 1) {
                val receiverMessageFromLoginDiff = currentTime - mLoginTime
                receiverMessageFromLoginDiffSum += receiverMessageFromLoginDiff
                val receiverMessageFromLoginDiffStr =
                    "Receive First Rtm Message from login diff:${receiverMessageFromLoginDiff}ms average:${receiverMessageFromLoginDiffSum / testCount}ms"
                updateHistoryUI(receiverMessageFromLoginDiffStr)
            }

            if (remainInChannelTestCount > 0) {
                sendRtmMessages()
            } else {
                logoutRtm()
            }
        } else {
            updateHistoryUI("ReceiveRtmMessage:$message")
        }
    }

    override fun onRtmConnected() {
        Log.d(TAG, "onRtmConnected")
        runOnUiThread {
            val loginConnectedTime = System.currentTimeMillis() - mLoginTime
            loginConnectedDiffSum += loginConnectedTime
            testCount++

            val loginConnectedDiff =
                "loginConnectedDiff:${loginConnectedTime}ms average:${loginConnectedDiffSum / testCount}ms"
            updateHistoryUI(loginConnectedDiff)
            RtmManager.subscribeMessageChannel(mChannelName)
        }
    }

    override fun onRtmDisconnected() {
        runOnUiThread {
            updateTestAverage()
        }
        startRtmTestCycle()
    }

    override fun onRtmSubscribed() {
        sendRtmMessages()
    }

    private fun connectWs(url: String) {
        Log.d(TAG, "connectWs url:$url")
        updateLoginTime()
        WSManager.connect(url)
    }

    private fun sendWsMessage() {
        remainInChannelTestCount--
        mSendMessageTime = System.currentTimeMillis()
        mSendMessage = "wsMessage$mSendMessageTime"

        //WSManager.sendMessage(mSendMessage.toByteArray(Charsets.UTF_8))
        WSManager.sendMessage(mSendMessage)
        updateHistoryUI("SendWsMessage:$mSendMessage")
    }

    private fun logoutWs() {
        WSManager.release()
    }

    private fun startWsTestCycle(firstTest: Boolean = false) {
        if (remainingTests > 0) {
            runOnUiThread { binding.btnWsTest.isEnabled = false }
            var delayTime = INTERVAL_LOOP_WAIT
            if (firstTest) {
                delayTime = 0
            }
            remainingTests--
            Handler(Looper.getMainLooper()).postDelayed({
                updateHistoryUI("Ws Test Start remainingTests:$remainingTests")
                connectWs(mWsUrl)
            }, delayTime)
        } else {
            runOnUiThread {
                binding.btnWsTest.isEnabled = true
                updateHistoryUI("Ws Test End")
            }
        }
    }

    override fun onWSConnected() {
        Log.d(TAG, "onWSConnected")
        runOnUiThread {
            val loginConnectedTime = System.currentTimeMillis() - mLoginTime
            loginConnectedDiffSum += loginConnectedTime
            testCount++

            val loginConnectedDiff =
                "ws loginConnectedDiff:${loginConnectedTime}ms average:${loginConnectedDiffSum / testCount}ms"
            updateHistoryUI(loginConnectedDiff)
            sendWsMessage()
        }
    }

    override fun onWSDisconnected() {
        runOnUiThread {
            updateTestAverage()
        }
        startWsTestCycle()
    }

    override fun onWSMessageReceived(message: String) {
        Log.d(TAG, "onWSMessageReceived  message:${message}")
        if (0L != mSendMessageTime && message == mSendMessage) {
            val currentTime = System.currentTimeMillis()
            val sendMessageDiff = currentTime - mSendMessageTime;

            receiverMessageInChannelDiffSum += sendMessageDiff
            val receiverMessageInChannelAverageDiff =
                receiverMessageInChannelDiffSum / (MAX_IN_CHANNEL_TEST_COUNT - remainInChannelTestCount)
            val sendRtmMessageDiffStr =
                "ReceiverWsMessage:$mSendMessage diff:${sendMessageDiff}ms average:${receiverMessageInChannelAverageDiff}ms"
            updateHistoryUI(sendRtmMessageDiffStr)

            if (remainInChannelTestCount == 0) {
                receiverMessageDiffSum += receiverMessageInChannelAverageDiff
            }

            if (remainInChannelTestCount == MAX_IN_CHANNEL_TEST_COUNT - 1) {
                val receiverMessageFromLoginDiff = currentTime - mLoginTime
                receiverMessageFromLoginDiffSum += receiverMessageFromLoginDiff
                val receiverMessageFromLoginDiffStr =
                    "ReceiverWsMessage:$mSendMessage from login diff:${receiverMessageFromLoginDiff}ms average:${receiverMessageFromLoginDiffSum / testCount}ms"
                updateHistoryUI(receiverMessageFromLoginDiffStr)
            }

            if (remainInChannelTestCount > 0) {
                sendWsMessage()
            } else {
                logoutWs()
            }
        } else {
            updateHistoryUI("ReceiveRtmMessage:$message")
        }
    }

    override fun onWSMessageReceived(message: ByteArray) {
        Log.d(TAG, "onWSMessageReceived  message:${String(message)}")
        onWSMessageReceived(String(message))
    }

    override fun onWSError(errorMessage: String) {

    }


}