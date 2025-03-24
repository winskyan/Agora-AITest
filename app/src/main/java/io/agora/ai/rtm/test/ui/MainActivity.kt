package io.agora.ai.rtm.test.ui

import android.Manifest
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.lxj.xpopup.XPopup
import io.agora.ai.rtm.test.BuildConfig
import io.agora.ai.rtm.test.constants.Constants
import io.agora.ai.rtm.test.databinding.ActivityMainBinding
import io.agora.ai.rtm.test.dns.DnsResolver
import io.agora.ai.rtm.test.dns.DnsTestManager
import io.agora.ai.rtm.test.rtc.RtcManager
import io.agora.ai.rtm.test.rtc.RtcTestManager
import io.agora.ai.rtm.test.rtm.RtmManager
import io.agora.ai.rtm.test.rtm.RtmTestManager
import io.agora.ai.rtm.test.ws.WsAudioTestManager
import io.agora.ai.rtm.test.ws.WsTestManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import pub.devrel.easypermissions.AppSettingsDialog
import pub.devrel.easypermissions.EasyPermissions
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity(),
    RtmTestManager.TestStatusCallback, WsTestManager.TestStatusCallback,
    DnsTestManager.TestStatusCallback, RtcTestManager.TestStatusCallback,
    WsAudioTestManager.TestStatusCallback {
    companion object {
        const val TAG: String = Constants.TAG + "-MainActivity"
        const val MY_PERMISSIONS_REQUEST_CODE = 123
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var rtmTestManager: RtmTestManager
    private lateinit var wsTestManager: WsTestManager
    private lateinit var dnsTestManager: DnsTestManager
    private lateinit var rtcTestManager: RtcTestManager
    private lateinit var wsAudioTestManager: WsAudioTestManager

    private var mRtmChannelName = "wei888"
    private var mRtcChannelName = "wei999"
    private var mWsUrl = "wss://108.129.196.84:8765"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        checkPermissions()
        initView()

        // Initialize RTM Test Manager
        rtmTestManager = RtmTestManager(applicationContext)
        rtmTestManager.initialize(
            this,
            applicationContext
        ) // Initialize RTM with this as the callback

        // Initialize WebSocket Test Manager
        wsTestManager = WsTestManager(applicationContext)
        wsTestManager.initialize(this) // Initialize WebSocket with this as the callback

        // Initialize DNS Test Manager
        dnsTestManager = DnsTestManager(applicationContext)
        dnsTestManager.initialize(this) // Initialize DNS Test Manager with this as the callback

        rtcTestManager = RtcTestManager(applicationContext)
        rtcTestManager.initialize(
            this
        ) // Initialize RTC with this as the callback

        wsAudioTestManager = WsAudioTestManager(applicationContext)
        wsAudioTestManager.initialize(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        rtmTestManager.release() // This will also release RtmManager
        wsTestManager.release() // This will also release WSManager
        dnsTestManager.release() // This will also close the DNS log file
        rtcTestManager.release() // This will also release RtcManager
        wsAudioTestManager.release()
    }

    private fun checkPermissions() {
        val permissions =
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
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

    private fun initView() {
        handleOnBackPressed()

        val version =
            "Version: ${BuildConfig.VERSION_NAME} \r\n RTM: ${RtmManager.getRtmVersion()} \r\n RTC: ${RtcManager.getRtcVersion()}"

        binding.tvVersion.text = version

        binding.btnStop.setOnClickListener {
            rtmTestManager.stopTest()
            wsTestManager.stopTest()
            rtcTestManager.stopTest()
            wsAudioTestManager.stopTest()
        }

        binding.btnRtmTest.setOnClickListener {
            val rtmChannelName = binding.etRtmChannelName.text.toString()
            if (rtmChannelName.isNotEmpty()) {
                mRtmChannelName = rtmChannelName
            }

            val rtcChannelName = binding.etRtcChannelName.text.toString()
            if (rtcChannelName.isNotEmpty()) {
                mRtcChannelName = rtcChannelName
            }

            val wsUrl = binding.etWsUrl.text.toString()
            if (wsUrl.isNotEmpty()) {
                mWsUrl = wsUrl
            }

            val customCount = binding.etCustomCount.text.toString()
            val testCount =
                if (customCount.isEmpty()) Constants.DEFAULT_TEST_COUNT else customCount.toInt()

            val sleepTime = binding.etCustomSleepTime.text.toString()
            val loopSleepTime =
                if (sleepTime.isEmpty()) Constants.INTERVAL_LOOP_WAIT else sleepTime.toInt() * 1000L

            // Start RTM test
            runOnUiThread { binding.btnRtmTest.isEnabled = false }

            // Perform DNS resolution and RTC test in separate thread
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    // Perform DNS resolution
                    dnsTestManager.startTest()

                } catch (e: Exception) {
                    Log.e(TAG, "Error in DNS test thread", e)
                    updateHistoryUI("Error in DNS test: ${e.message}")
                }
            }

            CoroutineScope(Dispatchers.Default).launch {
                try {
                    // Perform RTC test
                    rtcTestManager.startTest(mRtcChannelName)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in RTC test thread", e)
                    updateHistoryUI("Error in RTC test: ${e.message}")
                }
            }

            CoroutineScope(Dispatchers.Default).launch {
                try {
                    // Perform RTC test
                    wsAudioTestManager.startTest(mWsUrl)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in ws audio test thread", e)
                    updateHistoryUI("Error in ws audio test: ${e.message}")
                }
            }

            // Start RTM test immediately
            rtmTestManager.startTest(mRtmChannelName, testCount, loopSleepTime)
        }

        binding.btnWsTest.setOnClickListener {
            val wsUrl = binding.etWsUrl.text.toString()
            if (wsUrl.isNotEmpty()) {
                mWsUrl = wsUrl
            }

            val customCount = binding.etCustomCount.text.toString()
            val testCount =
                if (customCount.isEmpty()) Constants.DEFAULT_TEST_COUNT else customCount.toInt()

            val sleepTime = binding.etCustomSleepTime.text.toString()
            val loopSleepTime =
                if (sleepTime.isEmpty()) Constants.INTERVAL_LOOP_WAIT else sleepTime.toInt() * 1000L

            // Start WebSocket test
            runOnUiThread { binding.btnWsTest.isEnabled = false }

            // Start WebSocket test
            wsTestManager.startTest(mWsUrl, testCount, loopSleepTime)
        }

        binding.tvHistory.movementMethod = ScrollingMovementMethod.getInstance()
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

    // RtmTestManager.TestStatusCallback implementation
    override fun onRtmTestStarted() {
        Log.d(TAG, "RTM test started")
    }

    override fun onRtmTestProgress(message: String) {
        updateHistoryUI(message)
    }

    override fun onRtmTestCompleted() {
        runOnUiThread {
            binding.btnRtmTest.isEnabled = true
        }
        CoroutineScope(Dispatchers.Default).launch {
            rtcTestManager.stopTest()
        }
        CoroutineScope(Dispatchers.Default).launch {
            wsAudioTestManager.stopTest()
        }
    }

    override fun onRtmTestCycleCompleted() {
        Log.d(TAG, "RTM test cycle completed")
    }

    // WsTestManager.TestStatusCallback implementation
    override fun onWsTestStarted() {
        Log.d(TAG, "WebSocket test started")
    }

    override fun onWsTestProgress(message: String) {
        updateHistoryUI(message)
    }

    override fun onWsTestCompleted() {
        runOnUiThread {
            binding.btnWsTest.isEnabled = true
        }
    }

    override fun onWsTestCycleCompleted() {
        Log.d(TAG, "WebSocket test cycle completed")
    }

    // DnsTestManager.TestStatusCallback implementation
    override fun onDnsTestStarted() {
        Log.d(TAG, "DNS test started")
    }

    override fun onDnsTestProgress(message: String) {
        updateHistoryUI(message)
    }

    override fun onDnsTestCompleted() {
        Log.d(TAG, "DNS test completed")
    }

    override fun onDnsResultReceived(results: List<DnsResolver.DnsResult>) {
        // Optional: Handle DNS results if needed
    }

    override fun onRtcTestStarted() {
        Log.d(TAG, "RTC test started")
        updateHistoryUI("RTC test started")
    }

    override fun onRtcTestProgress(message: String) {
        updateHistoryUI(message)
    }

    override fun onRtcTestCompleted() {
        Log.d(TAG, "RTC test completed")
        updateHistoryUI("RTC test completed")
    }

    override fun onRecordAudioFrame(data: ByteArray?) {
        CoroutineScope(Dispatchers.Default).launch {
            if (data != null) {
                wsAudioTestManager.sendAudioFrame(data)
            }
        }
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
        }
    }

    override fun onWsAudioTestStarted() {
        Log.d(TAG, "WebSocket audio test started")
    }

    override fun onWssAudioTestProgress(message: String) {
        updateHistoryUI(message)
    }

    override fun onWssAudioTestCompleted() {
        Log.d(TAG, "WebSocket audio test completed")
    }


}