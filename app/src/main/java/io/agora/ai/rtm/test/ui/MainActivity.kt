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
import io.agora.ai.rtm.test.rtm.RtmManager
import io.agora.ai.rtm.test.rtm.RtmTestManager
import io.agora.ai.rtm.test.ws.WsTestManager
import pub.devrel.easypermissions.AppSettingsDialog
import pub.devrel.easypermissions.EasyPermissions
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity(),
    RtmTestManager.TestStatusCallback, WsTestManager.TestStatusCallback,
    DnsTestManager.TestStatusCallback {
    companion object {
        const val TAG: String = Constants.TAG + "-MainActivity"
        const val MY_PERMISSIONS_REQUEST_CODE = 123
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var rtmTestManager: RtmTestManager
    private lateinit var wsTestManager: WsTestManager
    private lateinit var dnsTestManager: DnsTestManager

    private var mChannelName = "wei888"
    private var mWsUrl = "wss://108.129.196.84:8765"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        checkPermissions()
        initView()

        // Initialize RTM Test Manager
        rtmTestManager = RtmTestManager(applicationContext)
        rtmTestManager.initialize(this) // Initialize RTM with this as the callback

        // Initialize WebSocket Test Manager
        wsTestManager = WsTestManager(applicationContext)
        wsTestManager.initialize(this) // Initialize WebSocket with this as the callback

        // Initialize DNS Test Manager
        dnsTestManager = DnsTestManager(applicationContext)
        dnsTestManager.initialize(this) // Initialize DNS Test Manager with this as the callback
    }

    override fun onDestroy() {
        super.onDestroy()
        rtmTestManager.release() // This will also release RtmManager
        wsTestManager.release() // This will also release WSManager
        dnsTestManager.release() // This will also close the DNS log file
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

    private fun initView() {
        handleOnBackPressed()

        val version = "Version: ${BuildConfig.VERSION_NAME} \r\n RTM: ${RtmManager.getRtmVersion()}"

        binding.tvVersion.text = version

        binding.btnStop.setOnClickListener {
            rtmTestManager.stopTest()
            wsTestManager.stopTest()
        }

        binding.btnRtmTest.setOnClickListener {
            val channelName = binding.etChannelName.text.toString()
            if (channelName.isNotEmpty()) {
                mChannelName = channelName
                rtmTestManager.setChannelName(channelName)
            }

            val customCount = binding.etCustomCount.text.toString()
            val testCount =
                if (customCount.isEmpty()) Constants.DEFAULT_TEST_COUNT else customCount.toInt()

            // Start RTM test
            runOnUiThread { binding.btnRtmTest.isEnabled = false }

            // Perform DNS resolution
            dnsTestManager.startTest()

            // Start RTM test
            rtmTestManager.startTest(testCount)
        }

        binding.btnWsTest.setOnClickListener {
            val wsUrl = binding.etWsUrl.text.toString()
            if (wsUrl.isNotEmpty()) {
                mWsUrl = wsUrl
                wsTestManager.setWsUrl(wsUrl)
            }

            val customCount = binding.etCustomCount.text.toString()
            val testCount =
                if (customCount.isEmpty()) Constants.DEFAULT_TEST_COUNT else customCount.toInt()

            // Start WebSocket test
            runOnUiThread { binding.btnWsTest.isEnabled = false }

            // Start WebSocket test
            wsTestManager.startTest(testCount)
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
}