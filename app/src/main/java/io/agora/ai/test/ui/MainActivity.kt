package io.agora.ai.test.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
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
import io.agora.ai.test.maas.model.JoinChannelConfig
import io.agora.ai.test.maas.model.MaaSEngineConfiguration
import io.agora.ai.test.maas.model.MassEncryptionConfig
import io.agora.ai.test.maas.model.SceneMode
import io.agora.ai.test.maas.model.VadConfiguration
import io.agora.ai.test.maas.model.WatermarkOptions
import io.agora.ai.test.ui.dialog.SettingsDialog
import io.agora.ai.test.utils.KeyCenter
import io.agora.ai.test.utils.Utils
import io.agora.ai.test.utils.VideoFileReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    private var mSendRtcAudioMetadataTime = 0L
    private var mSendRtcAudioMetadata = ""
    private var mReceiveRtcAudioMetadataTotalTime = 0L
    private var mReceiveRtcAudioMetadataTotalCount = 0

    private var mSendRtcDataStreamMessageTime = 0L
    private var mSendRtcDataStreamMessage = ""
    private var mReceiveRtcDataStreamMessageTotalTime = 0L
    private var mReceiveRtcDataStreamMessageTotalCount = 0

    private var mSendRtmMessageTime = 0L
    private var mSendRtmMessage = ""
    private var mReceiveRtmMessageTotalTime = 0L
    private var mReceiveRtmMessageTotalCount = 0

    private var mSendRtmStreamMessageTime = 0L
    private var mSendRtmStreamMessage = ""
    private var mReceiveRtmStreamMessageTotalTime = 0L
    private var mReceiveRtmStreamMessageTotalCount = 0

    private var sendingJob: Job? = null

    private var mHistoryFileName = ""
    private val logExecutor = Executors.newSingleThreadExecutor()
    private lateinit var bufferedWriter: BufferedWriter

    private var mVideoFileReader: VideoFileReader? = null


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
        mSendRtcAudioMetadataTime = 0L
        mSendRtcAudioMetadata = ""
        mReceiveRtcAudioMetadataTotalTime = 0L
        mReceiveRtcAudioMetadataTotalCount = 0

        mSendRtcDataStreamMessageTime = 0L
        mSendRtcDataStreamMessage = ""
        mReceiveRtcDataStreamMessageTotalTime = 0L
        mReceiveRtcDataStreamMessageTotalCount = 0

        mSendRtmMessageTime = 0L
        mSendRtmMessage = ""
        mReceiveRtmMessageTotalTime = 0L
        mReceiveRtmMessageTotalCount = 0

        mSendRtmStreamMessageTime = 0L
        mSendRtmStreamMessage = ""
        mReceiveRtmStreamMessageTotalTime = 0L
        mReceiveRtmStreamMessageTotalCount = 0
    }

    private fun initEngine() {
        mMaaSEngine = MaaSEngine.create()
        val configuration = MaaSEngineConfiguration()
        configuration.context = this
        configuration.eventHandler = this
        configuration.enableConsoleLog = true
        configuration.enableSaveLogToFile = true
        configuration.appId =
            DemoContext.appId.ifEmpty { BuildConfig.APP_ID }
        configuration.userId = KeyCenter.getRtcUid()
        configuration.rtmUserId = KeyCenter.getRtmUid()
        configuration.rtcToken = KeyCenter.getRtcToken(mChannelName, KeyCenter.getRtcUid())
        configuration.rtmToken =
            if (DemoContext.enableRtm) KeyCenter.getRtmToken2(KeyCenter.getRtmUid()) else ""
        configuration.enableMultiTurnShortTermMemory = true
        configuration.userName = "test"
        configuration.agentVoiceName = "xiaoyan"
        configuration.input = SceneMode("zh-CN", 16000, 1, 16)
        configuration.output = SceneMode("zh-CN", 16000, 1, 16)
        configuration.vadConfiguration = VadConfiguration(500)
        configuration.noiseEnvironment = MaaSConstants.NoiseEnvironment.NOISE
        configuration.speechRecognitionCompletenessLevel =
            MaaSConstants.SpeechRecognitionCompletenessLevel.NORMAL
        configuration.params = DemoContext.params.toList()
        configuration.enableRtm = DemoContext.enableRtm

        if (DemoContext.audioProfile != -1) {
            configuration.audioProfile = DemoContext.audioProfile
        }
        if (DemoContext.audioScenario != -1) {
            configuration.audioScenario = DemoContext.audioScenario
        }

        val ret = mMaaSEngine?.initialize(configuration)
        if (ret != 0) {
            Log.d(TAG, "initialize failed")
        }

        if (DemoContext.enableAudio) {
            mMaaSEngine?.enableAudio()
        } else {
            mMaaSEngine?.disableAudio()
        }

        if (DemoContext.enableVideo) {
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
                stopSendYuv()
                stopSendingMessagesTest()
                if (DemoContext.enableAudio) {
                    mMaaSEngine?.disableAudio()
                    DemoContext.enableAudio = false
                }
                if (DemoContext.enableVideo) {
                    mMaaSEngine?.stopVideo()
                    DemoContext.enableVideo = false
                }

                mMaaSEngine?.leaveChannel()
                if (DemoContext.enableDestroyRtcWhenLeaveChannel) {
                    MaaSEngine.destroy()
                }
                closeWriter()
            } else {
                initData()
                var channelName = binding.etChannelName.text.toString()
                if (channelName.isEmpty()) {
                    channelName = mChannelName
                }
                mHistoryFileName =
                    "history-${channelName}-${KeyCenter.getRtcUid()}-${Utils.getCurrentDateStr("yyyyMMdd_HHmmss")}.txt"
                initWriter()
                initEngine()

                val config = MassEncryptionConfig()

                if (DemoContext.enableEncryption) {
                    // 将加密模式设置为 AES_128_GCM2
                    config.encryptionMode = MassEncryptionConfig.EncryptionMode.AES_128_GCM2
                    config.encryptionKey =
                        "oLB41X/IGpxgUMzsYpE+IOpNLOyIbpr8C7qe+mb7QRHkmrELtVsWw6Xr6rQ0XAK03fsBXJJVCkXeL2X7J492qXjR89Q="
                    val encryptionKdfSalt =
                        "3t6pvC+qHvVW300B3f+g5J49U3Y×QR40tWKEP/Zz+4=".toByteArray(Charsets.UTF_8)
                    Log.d(TAG, "encryptionKdfSalt: ${encryptionKdfSalt.size}")
                    System.arraycopy(
                        encryptionKdfSalt,
                        0,
                        config.encryptionKdfSalt,
                        0,
                        config.encryptionKdfSalt.size
                    )
                }
                if (DemoContext.enableVideo) {
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
                JoinChannelConfig().apply {
                    enableStereoTest = DemoContext.enableStereoTest
                    enableSaveAudio = DemoContext.enableSaveAudio
                    enablePushExternalVideo = DemoContext.enablePushExternalVideo
                    enableEncryption = DemoContext.enableEncryption
                    encryptionConfig = config
                    enablePullAudioFrame = DemoContext.enablePullAudioFrame
                    enableSendVideoMetadata = DemoContext.enableSendVideoMetadata
                }.let {
                    mMaaSEngine?.joinChannel(
                        channelName,
                        DemoContext.clientRoleType,
                        it
                    )

                }

            }
        }

        binding.btnEnableAudio.setOnClickListener {
            if (DemoContext.enableAudio) {
                mMaaSEngine?.disableAudio()
                DemoContext.enableAudio = false
            } else {
                mMaaSEngine?.enableAudio()
                DemoContext.enableAudio = true
            }

            binding.btnEnableAudio.text =
                if (DemoContext.enableAudio) getText(R.string.disable_audio) else getText(
                    R.string.enable_audio
                )
        }


        binding.btnEnableVideo.setOnClickListener {
            if (DemoContext.enableVideo) {
                mMaaSEngine?.stopVideo()
                DemoContext.enableVideo = false
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
                DemoContext.enableVideo = true
            }

            binding.btnEnableVideo.text =
                if (DemoContext.enableVideo) getText(R.string.disable_video) else getText(R.string.enable_video)
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
            if (DemoContext.enableTestRtcDataStreamMessage) {
                startSendingMessagesTest()
            } else {
                sendRtcDataStreamMessage()
            }
        }

        binding.btnSendAudioMetadata.setOnClickListener {
            if (DemoContext.enableTestRtcAudioMetadata) {
                startSendingMessagesTest()
            } else {
                sendRtcAudioMetadata()
            }
        }

        binding.btnSendRtmMessage.setOnClickListener {
            if (DemoContext.enableTestRtmMessage) {
                startSendingMessagesTest()
            } else {
                sendRtmMessages()
            }
        }

        binding.tvHistory.movementMethod = ScrollingMovementMethod.getInstance()

        binding.btnClear.setOnClickListener {
            binding.tvHistory.text = ""
        }

        binding.btnSendYuv.setOnClickListener {
            sendYuv()
        }
    }

    private fun sendYuv() {
        mVideoFileReader = VideoFileReader(
            applicationContext, "sample.yuv", 640, 360, DemoContext.fps,
            videoReadListener = object : VideoFileReader.OnVideoReadListener {
                override fun onVideoRead(
                    buffer: ByteArray?,
                    width: Int,
                    height: Int,
                    frameIndex: Int
                ) {
                    if (buffer != null) {
                        mMaaSEngine?.pushVideoFrame(
                            buffer,
                            width,
                            height,
                            MaaSConstants.ViewFrameType.I420
                        )
                        updateHistoryUI("SendYuvFrame:$frameIndex")
                    }
                }
            }
        )
        mVideoFileReader?.start()
    }

    private fun stopSendYuv() {
        if (null != mVideoFileReader) {
            mVideoFileReader?.stop()
            mVideoFileReader = null
        }
    }

    private fun startSendingMessagesTest() {
        // Cancel any existing coroutine job to avoid multiple instances running
        sendingJob?.cancel()

        // Launch a new coroutine to send messages every second
        sendingJob = CoroutineScope(Dispatchers.Main).launch {
            var count = 0
            while (count < RTM_TEST_MAX_COUNT && isActive) {
                if (DemoContext.enableTestRtcDataStreamMessage) {
                    binding.btnSendStreamMessage.isEnabled = false
                    sendRtcDataStreamMessage()
                }
                if (DemoContext.enableTestRtcAudioMetadata) {
                    binding.btnSendAudioMetadata.isEnabled = false
                    sendRtcAudioMetadata()
                }
                if (DemoContext.enableTestRtmMessage) {
                    binding.btnSendRtmMessage.isEnabled = false
                    sendRtmMessages()
                }
                count++
                delay(1 * 1000)
            }
        }

        // Enable the button after the coroutine job is finished
        sendingJob?.invokeOnCompletion {
            runOnUiThread {
                if (DemoContext.enableTestRtcDataStreamMessage) {
                    binding.btnSendStreamMessage.isEnabled = true
                }
                if (DemoContext.enableTestRtcAudioMetadata) {
                    binding.btnSendAudioMetadata.isEnabled = true
                }
                if (DemoContext.enableTestRtmMessage) {
                    binding.btnSendRtmMessage.isEnabled = true
                }
            }
        }
    }

    private fun stopSendingMessagesTest() {
        if (sendingJob?.isActive == true) {
            sendingJob?.cancel()
        }
    }

    private fun sendRtcDataStreamMessage() {
        val currentTime = System.currentTimeMillis()
        mSendRtcDataStreamMessage = "RtcDataStreamMessage:$currentTime"
        mMaaSEngine?.sendText(mSendRtcDataStreamMessage)
        mSendRtcDataStreamMessageTime = currentTime
        updateHistoryUI("SendRtcDataStreamMessage:$mSendRtcDataStreamMessage")
    }

    private fun sendRtcAudioMetadata() {
        val currentTime = System.currentTimeMillis()
        mSendRtcAudioMetadata = "RtcAudioMetadata:$currentTime"
        val ret = mMaaSEngine?.sendAudioMetadata(mSendRtcAudioMetadata.toByteArray(Charsets.UTF_8))
        if (ret != 0) {
            Log.e(TAG, "sendAudioMetadata ret:$ret")
        }
        mSendRtcAudioMetadataTime = currentTime
        updateHistoryUI("SendRtcAudioMetadata:$mSendRtcAudioMetadata")
    }

    private fun sendRtmMessages() {
        mSendRtmMessageTime = System.currentTimeMillis()
        mSendRtmMessage = "rtmMessage:$mSendRtmMessageTime"
        var ret = mMaaSEngine?.sendRtmMessage(
            mSendRtmMessage.toByteArray(Charsets.UTF_8),
            MaaSConstants.RtmChannelType.MESSAGE
        )
        if (ret != 0) {
            Log.e(TAG, "sendRtmMessage failed")
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
            Log.e(TAG, "sendRtmStreamMessage failed")
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
        binding.btnSendRtmMessage.isEnabled = mJoinSuccess
        binding.btnSendYuv.isEnabled = mJoinSuccess

        binding.btnJoin.text =
            if (mJoinSuccess) getText(R.string.leave) else getText(R.string.join)

        binding.btnEnableVideo.text =
            if (DemoContext.enableVideo) getText(R.string.disable_video) else getText(R.string.enable_video)

        binding.btnEnableAudio.text =
            if (DemoContext.enableAudio) getText(R.string.disable_audio) else getText(
                R.string.enable_audio
            )
    }

    private fun updateToolbarTitle(title: String) {
        binding.toolbarTitle.text = title
    }


    override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
        mJoinSuccess = true
        runOnUiThread {
            updateToolbarTitle("${getString(R.string.app_name)}($channel:$uid)")
            updateUI()
        }
    }

    override fun onLeaveChannelSuccess() {
        Log.d(TAG, "onLeaveChannelSuccess")
        mJoinSuccess = false
        runOnUiThread {
            updateToolbarTitle(getString(R.string.app_name))
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
    }

    override fun onAudioVolumeIndication(
        speakers: ArrayList<AudioVolumeInfo>,
        totalVolume: Int
    ) {
        //Log.d(TAG, "onAudioVolumeIndication speakers:$speakers totalVolume:$totalVolume")
    }

    override fun onStreamMessage(uid: Int, data: ByteArray?) {
        Log.d(TAG, "onStreamMessage uid:$uid data:${String(data!!, Charsets.UTF_8)}")
        if (0L != mSendRtcDataStreamMessageTime && data.contentEquals(
                mSendRtcDataStreamMessage.toByteArray(Charsets.UTF_8)
            )
        ) {
            val currentTime = System.currentTimeMillis()
            val diff = System.currentTimeMillis() - mSendRtcDataStreamMessageTime
            mReceiveRtcDataStreamMessageTotalCount++
            mReceiveRtcDataStreamMessageTotalTime += diff
            updateHistoryUI("${currentTime}:ReceiveRtcDataStreamMessage:${String(data)} diff:$diff total:$mReceiveRtcDataStreamMessageTotalCount avg:${mReceiveRtcDataStreamMessageTotalTime / mReceiveRtcDataStreamMessageTotalCount}")
        } else {
            updateHistoryUI("ReceiveRtcDataStreamMessage:${String(data)}")
        }
    }

    override fun onAudioMetadataReceived(uid: Int, metadata: ByteArray?) {
        Log.d(
            TAG,
            "onAudioMetadataReceived uid:$uid metadata:${String(metadata!!, Charsets.UTF_8)}"
        )
        if (0L != mSendRtcAudioMetadataTime && metadata.contentEquals(
                mSendRtcAudioMetadata.toByteArray(
                    Charsets.UTF_8
                )
            )
        ) {
            val currentTime = System.currentTimeMillis()
            val diff = System.currentTimeMillis() - currentTime
            mReceiveRtcAudioMetadataTotalCount++
            mReceiveRtcAudioMetadataTotalTime += diff
            updateHistoryUI("${currentTime}:ReceiveRtcAudioMetadata:${String(metadata)} diff:$diff total:$mReceiveRtcAudioMetadataTotalCount avg:${mReceiveRtcAudioMetadataTotalTime / mReceiveRtcAudioMetadataTotalCount}")
        } else {
            updateHistoryUI("ReceiveRtcAudioMetadata:${String(metadata)}")
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
        view.draw(Canvas(bitmap))

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
        Log.d(TAG, message)
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