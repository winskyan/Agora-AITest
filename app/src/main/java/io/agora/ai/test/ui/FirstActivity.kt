package io.agora.ai.test.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.lxj.xpopup.XPopup
import io.agora.ai.test.constants.Constants
import io.agora.ai.test.databinding.ActivityFirstBinding
import pub.devrel.easypermissions.AppSettingsDialog
import pub.devrel.easypermissions.EasyPermissions
import kotlin.system.exitProcess

class FirstActivity : AppCompatActivity() {
    companion object {
        const val TAG: String = Constants.TAG + "-FirstActivity"
        const val MY_PERMISSIONS_REQUEST_CODE = 123
    }

    private lateinit var binding: ActivityFirstBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "FirstActivity onCreate")
        binding = ActivityFirstBinding.inflate(layoutInflater)
        setContentView(binding.root)
        checkPermissions()
        initView()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "FirstActivity onResume")
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "FirstActivity onStart")
    }

    override fun onRestart() {
        super.onRestart()
        Log.d(TAG, "FirstActivity onRestart")
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "FirstActivity onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "FirstActivity onDestroy")
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

    private fun initView() {
        handleOnBackPressed()

        binding.btnJoin.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

    }

    private fun handleOnBackPressed() {
        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val xPopup = XPopup.Builder(this@FirstActivity)
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
}