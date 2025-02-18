package io.agora.ai.test.ui

import android.Manifest
import android.graphics.Bitmap
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.lxj.xpopup.XPopup
import io.agora.ai.test.BuildConfig
import io.agora.ai.test.R
import io.agora.ai.test.constants.Constants
import io.agora.ai.test.context.DemoContext
import io.agora.ai.test.databinding.ActivityMainBinding
import io.agora.ai.test.maas.MaaSConstants
import io.agora.ai.test.maas.MaaSEngine
import io.agora.ai.test.maas.MaaSEngineEventHandler
import io.agora.ai.test.maas.model.AudioVolumeInfo
import io.agora.ai.test.maas.model.MaaSEngineConfiguration
import io.agora.ai.test.maas.model.SceneMode
import io.agora.ai.test.maas.model.VadConfiguration
import io.agora.ai.test.maas.model.WatermarkOptions
import io.agora.ai.test.ui.dialog.SettingsDialog
import io.agora.ai.test.utils.KeyCenter
import io.agora.ai.test.utils.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pub.devrel.easypermissions.AppSettingsDialog
import pub.devrel.easypermissions.EasyPermissions
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity(), MaaSEngineEventHandler {
    companion object {
        const val TAG: String = Constants.TAG + "-MainActivity"
        const val MY_PERMISSIONS_REQUEST_CODE = 123

        const val RTM_TEST_MAX_COUNT = 100
    }

    private lateinit var binding: ActivityMainBinding

    private var mMaaSEngine: MaaSEngine? = null

    private var mChannelName = "testAga"
    private var mJoinSuccess = false

    private var mSendAudioMetadataTime = 0L
    private var mSendStreamMessageTime = 0L
    private var mSendRtmMessageTime = 0L
    private var mSendRtmStreamMessageTime = 0L

    private var mSendRtmMessage = ""
    private var mSendRtmStreamMessage = ""
    private var mReceiveRtmMessageTotalTime = 0L
    private var mReceiveRtmMessageTotalCount = 0
    private var mReceiveRtmStreamMessageTotalTime = 0L
    private var mReceiveRtmStreamMessageTotalCount = 0

    private var sendingJob: Job? = null

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
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        if (EasyPermissions.hasPermissions(this, *permissions)) {
            // 已经获取到权限，执行相应的操作
            Log.d(TAG, "granted permission")
        } else {
            Log.i(TAG, "requestPermissions")
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

    }

    private fun initEngine() {
        mMaaSEngine = MaaSEngine.create()
        val configuration = MaaSEngineConfiguration()
        configuration.context = this
        configuration.eventHandler = this
        configuration.enableConsoleLog = true
        configuration.enableSaveLogToFile = true
        configuration.appId = BuildConfig.APP_ID
        configuration.userId = KeyCenter.getUid()
        configuration.rtcToken = KeyCenter.getRtcToken(mChannelName, KeyCenter.getUid())
        configuration.rtmToken =
            if (DemoContext.isEnableRtm()) KeyCenter.getRtmToken2(KeyCenter.getUid()) else ""
        configuration.enableMultiTurnShortTermMemory = true
        configuration.userName = "test"
        configuration.agentVoiceName = "xiaoyan"
        configuration.input = SceneMode("zh-CN", 16000, 1, 16)
        configuration.output = SceneMode("zh-CN", 16000, 1, 16)
        configuration.vadConfiguration = VadConfiguration(500)
        configuration.noiseEnvironment = MaaSConstants.NoiseEnvironment.NOISE
        configuration.speechRecognitionCompletenessLevel =
            MaaSConstants.SpeechRecognitionCompletenessLevel.NORMAL
        configuration.params = DemoContext.getParams().toList()
        configuration.enableRtm = DemoContext.isEnableRtm()

        if (DemoContext.getAudioProfile() != -1) {
            configuration.audioProfile = DemoContext.getAudioProfile()
        }
        if (DemoContext.getAudioScenario() != -1) {
            configuration.audioScenario = DemoContext.getAudioScenario()
        }

        val ret = mMaaSEngine?.initialize(configuration)
        if (ret != 0) {
            Log.d(TAG, "initialize failed")
        }

        if (DemoContext.isEnableAudio()) {
            mMaaSEngine?.enableAudio()
        }

        if (DemoContext.isEnableVideo()) {
            mMaaSEngine?.startVideo(
                binding.localView,
                MaaSConstants.RenderMode.HIDDEN
            )

            mMaaSEngine?.setVideoEncoderConfiguration(
                640,
                480,
                MaaSConstants.FrameRate.FRAME_RATE_FPS_15,
                MaaSConstants.OrientationMode.FIXED_LANDSCAPE,
                false
            )
        }
    }

    private fun initView() {
        handleOnBackPressed()
        updateUI()

        binding.toolbarSetting.setOnClickListener {
            SettingsDialog.showSettingsDialog(this)
        }

        binding.btnJoin.setOnClickListener {
            if (mJoinSuccess) {
                stopSendingRtmMessages()
                if (DemoContext.isEnableAudio()) {
                    mMaaSEngine?.disableAudio()
                    DemoContext.setEnableAudio(false)
                }
                if (DemoContext.isEnableVideo()) {
                    mMaaSEngine?.stopVideo()
                    DemoContext.setEnableVideo(false)
                }
                mMaaSEngine?.leaveChannel()
                MaaSEngine.destroy()
                closeWriter()
            } else {
                var channelName = binding.etChannelName.text.toString()
                if (channelName.isEmpty()) {
                    channelName = mChannelName
                }
                mHistoryFileName =
                    "history-${channelName}-${KeyCenter.getUid()}-${Utils.getCurrentDateStr("yyyyMMdd_HHmmss")}.txt"
                initWriter()
                initEngine()
                mMaaSEngine?.joinChannel(
                    channelName,
                    MaaSConstants.CLIENT_ROLE_BROADCASTER,
                    registerRecordingAudio = DemoContext.isEnableStereoTest(),
                    registerPlaybackAudio = DemoContext.isEnableSaveAudio()
                )
            }

        }

        binding.btnEnableAudio.setOnClickListener {
            if (DemoContext.isEnableAudio()) {
                mMaaSEngine?.disableAudio()
                DemoContext.setEnableAudio(false)
            } else {
                mMaaSEngine?.enableAudio()
                DemoContext.setEnableAudio(true)
            }

            binding.btnEnableAudio.text =
                if (DemoContext.isEnableAudio()) getText(R.string.disable_audio) else getText(
                    R.string.enable_audio
                )
        }


        binding.btnEnableVideo.setOnClickListener {
            if (DemoContext.isEnableVideo()) {
                mMaaSEngine?.stopVideo()
                DemoContext.setEnableVideo(false)
            } else {
                mMaaSEngine?.startVideo(
                    binding.localView,
                    MaaSConstants.RenderMode.HIDDEN
                )

                mMaaSEngine?.setVideoEncoderConfiguration(
                    640,
                    480,
                    MaaSConstants.FrameRate.FRAME_RATE_FPS_15,
                    MaaSConstants.OrientationMode.FIXED_LANDSCAPE,
                    false
                )
                DemoContext.setEnableVideo(true)
            }

            binding.btnEnableVideo.text =
                if (DemoContext.isEnableVideo()) getText(R.string.disable_video) else getText(R.string.enable_video)
        }


        binding.btnSwitchCamera.setOnClickListener {
            mMaaSEngine?.switchCamera()
        }

        binding.btnAddWatermark.setOnClickListener {
            val watermarkOptions = WatermarkOptions()
            val width = 200
            val height = 200
            watermarkOptions.positionInPortraitMode =
                WatermarkOptions.Rectangle(0, 0, width, height)
            watermarkOptions.positionInLandscapeMode =
                WatermarkOptions.Rectangle(0, 0, width, height)
            watermarkOptions.visibleInPreview = true

//            mMaaSEngine?.addVideoWatermark(
//                "/assets/agora-logo.png",
//                watermarkOptions
//            )

            val rootView = window.decorView.rootView
            val screenBuffer = captureScreenToByteBuffer(rootView)

            mMaaSEngine?.addVideoWatermark(
                screenBuffer,
                rootView.width,
                rootView.height,
                MaaSConstants.VideoFormat.VIDEO_PIXEL_RGBA,
                watermarkOptions
            )
        }

        binding.btnClearWatermark.setOnClickListener {
            mMaaSEngine?.clearVideoWatermarks()
        }



        binding.btnSendStreamMessage.setOnClickListener {
            val streamMessage = "streamMessage:" + System.currentTimeMillis()
            mMaaSEngine?.sendText(streamMessage)
            mSendStreamMessageTime = System.currentTimeMillis()
            updateHistoryUI("SendStreamMessage:$streamMessage")
        }

        binding.btnSendAudioMetadata.setOnClickListener {
            val metadata =
                "metadata:" + System.currentTimeMillis()
            mMaaSEngine?.sendAudioMetadata(metadata.toByteArray(Charsets.UTF_8))
            mSendAudioMetadataTime = System.currentTimeMillis()
            updateHistoryUI("SendAudioMetadata:$metadata")
        }

        binding.btnSendRtmMessage.setOnClickListener {
            binding.btnSendRtmMessage.isEnabled = false
            startSendingRtmMessages()
        }

        binding.tvHistory.movementMethod = ScrollingMovementMethod.getInstance()

        binding.btnClear.setOnClickListener {
            binding.tvHistory.text = ""
        }
    }

    private fun startSendingRtmMessages() {
        // Cancel any existing coroutine job to avoid multiple instances running
        sendingJob?.cancel()
        mSendRtmMessageTime = 0L
        mSendRtmStreamMessageTime = 0L
        mReceiveRtmMessageTotalTime = 0L
        mReceiveRtmMessageTotalCount = 0
        mReceiveRtmStreamMessageTotalTime = 0L
        mReceiveRtmStreamMessageTotalCount = 0


        // Launch a new coroutine to send messages every second
        sendingJob = CoroutineScope(Dispatchers.Main).launch {
            var count = 0
            while (count < RTM_TEST_MAX_COUNT && isActive) {
                sendRtmMessages()
                count++
                delay(1000)
            }
        }

        // Enable the button after the coroutine job is finished
        sendingJob?.invokeOnCompletion {
            runOnUiThread {
                binding.btnSendRtmMessage.isEnabled = true
            }
        }
    }

    private fun stopSendingRtmMessages() {
        if (sendingJob?.isActive == true) {
            sendingJob?.cancel()
        }
    }

    private suspend fun sendRtmMessages() {
        mSendRtmMessageTime = System.currentTimeMillis()
        mSendRtmMessage = "rtmMessage:$mSendRtmMessageTime"
        var ret = mMaaSEngine?.sendRtmMessage(
            mSendRtmMessage.toByteArray(Charsets.UTF_8),
            MaaSConstants.RtmChannelType.MESSAGE
        )
        if (ret != 0) {
            Log.d(TAG, "sendRtmMessage failed")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "sendRtmMessage failed", Toast.LENGTH_LONG).show()
            }
            return
        }
        updateHistoryUI("SendRtmMessage:$mSendRtmMessage")

        mSendRtmStreamMessageTime = System.currentTimeMillis()
        mSendRtmStreamMessage = "rtmStreamMessage:$mSendRtmStreamMessageTime"
        ret = mMaaSEngine?.sendRtmMessage(
            mSendRtmStreamMessage.toByteArray(Charsets.UTF_8),
            MaaSConstants.RtmChannelType.STREAM
        )
        if (ret != 0) {
            Log.d(TAG, "sendRtmStreamMessage failed")
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "sendRtmStreamMessage failed", Toast.LENGTH_LONG)
                    .show()
            }
            return
        }
        updateHistoryUI("SendRtmStreamMessage:$mSendRtmStreamMessage")
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
        MaaSEngine.destroy()
        mMaaSEngine = null
        finishAffinity()
        finish()
        exitProcess(0)
    }

    private fun updateUI() {
        binding.btnEnableAudio.isEnabled = mJoinSuccess
        binding.btnEnableVideo.isEnabled = mJoinSuccess
        binding.btnSwitchCamera.isEnabled = mJoinSuccess
        binding.btnAddWatermark.isEnabled = mJoinSuccess
        binding.btnClearWatermark.isEnabled = mJoinSuccess
        binding.btnSendStreamMessage.isEnabled = mJoinSuccess
        binding.btnSendAudioMetadata.isEnabled = mJoinSuccess
        binding.btnClear.isEnabled = mJoinSuccess
        binding.btnSendRtmMessage.isEnabled = mJoinSuccess

        binding.btnJoin.text =
            if (mJoinSuccess) getText(R.string.leave) else getText(R.string.join)

        binding.btnEnableVideo.text =
            if (DemoContext.isEnableVideo()) getText(R.string.disable_video) else getText(R.string.enable_video)

        binding.btnEnableAudio.text =
            if (DemoContext.isEnableAudio()) getText(R.string.disable_audio) else getText(
                R.string.enable_audio
            )
    }


    override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
        mJoinSuccess = true
        runOnUiThread { updateUI() }
    }

    override fun onLeaveChannelSuccess() {
        Log.d(TAG, "onLeaveChannelSuccess")
        mJoinSuccess = false
        runOnUiThread {
            updateUI()

        }
    }

    override fun onUserJoined(uid: Int, elapsed: Int) {
        runOnUiThread {
            mMaaSEngine?.setupRemoteVideo(
                binding.remoteView,
                MaaSConstants.RenderMode.FIT,
                uid
            )
        }
    }

    override fun onUserOffline(uid: Int, reason: Int) {
        Log.d(TAG, "onUserOffline uid:$uid reason:$reason")
    }

    override fun onAudioVolumeIndication(speakers: ArrayList<AudioVolumeInfo>, totalVolume: Int) {
        //Log.d(TAG, "onAudioVolumeIndication speakers:$speakers totalVolume:$totalVolume")
    }

    override fun onStreamMessage(uid: Int, data: ByteArray?) {
        Log.d(TAG, "onStreamMessage uid:$uid data:${String(data!!, Charsets.UTF_8)}")
        if (0L != mSendStreamMessageTime) {
            val diff = System.currentTimeMillis() - mSendStreamMessageTime
            updateHistoryUI("ReceiveStreamMessage:${String(data)} diff:$diff")
        } else {
            updateHistoryUI("ReceiveStreamMessage:${String(data)}")
        }
    }

    override fun onAudioMetadataReceived(uid: Int, metadata: ByteArray?) {
        Log.d(
            TAG,
            "onAudioMetadataReceived uid:$uid metadata:${String(metadata!!, Charsets.UTF_8)}"
        )
        if (0L != mSendAudioMetadataTime) {
            val diff = System.currentTimeMillis() - mSendAudioMetadataTime
            updateHistoryUI("ReceiveAudioMetadata:${String(metadata)} diff:$diff")
        } else {
            updateHistoryUI("ReceiveAudioMetadata:${String(metadata)}")
        }
    }

    override fun onRtmMessageReceived(
        channelType: MaaSConstants.RtmChannelType,
        channelName: String,
        topicName: String,
        message: String,
        publisherId: String,
        customType: String,
        timestamp: Long
    ) {
        Log.d(
            TAG,
            "onRtmMessageReceived channelName:$channelName topicName:$topicName message:$message publisherId:$publisherId customType:$customType timestamp:$timestamp"
        )
        if (channelType == MaaSConstants.RtmChannelType.MESSAGE) {
            if (0L != mSendRtmMessageTime && message == mSendRtmMessage) {
                val currentTime = System.currentTimeMillis()
                val diff = currentTime - mSendRtmMessageTime
                mReceiveRtmMessageTotalCount++
                mReceiveRtmMessageTotalTime += diff
                updateHistoryUI("$currentTime:ReceiveRtmMessage:$message diff:$diff total:$mReceiveRtmMessageTotalCount avg:${mReceiveRtmMessageTotalTime / mReceiveRtmMessageTotalCount}")
            } else {
                updateHistoryUI("ReceiveRtmMessage:$message")
            }
        } else if (channelType == MaaSConstants.RtmChannelType.STREAM) {
            if (0L != mSendRtmStreamMessageTime && message == mSendRtmStreamMessage) {
                val currentTime = System.currentTimeMillis()
                val diff = currentTime - mSendRtmStreamMessageTime
                mReceiveRtmStreamMessageTotalCount++
                mReceiveRtmStreamMessageTotalTime += diff
                updateHistoryUI("$currentTime:ReceiveRtmStreamMessage:$message diff:$diff total:$mReceiveRtmStreamMessageTotalCount avg:${mReceiveRtmStreamMessageTotalTime / mReceiveRtmStreamMessageTotalCount}")
            } else {
                updateHistoryUI("ReceiveRtmStreamMessage:$message")
            }
        }

    }

    private fun captureScreenToByteBuffer(view: View): ByteBuffer {
        // 创建一个与视图大小相同的 Bitmap
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)

        // 将视图绘制到 Bitmap
        view.draw(android.graphics.Canvas(bitmap))

        // 计算需要的字节数
        val bytes = bitmap.byteCount

        // 创建一个直接分配的 ByteBuffer
        val buffer = ByteBuffer.allocateDirect(bytes)

        // 将 Bitmap 像素复制到 ByteBuffer
        bitmap.copyPixelsToBuffer(buffer)

        // 重置 buffer 位置
        buffer.rewind()

        // 回收 Bitmap 以释放内存
        bitmap.recycle()

        return buffer
    }

    private fun updateHistoryUI(message: String) {
        runOnUiThread {
            binding.tvHistory.append("\r\n$message")
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
}