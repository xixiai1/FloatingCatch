package com.floatingcatch.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: Button
    private lateinit var etSavePath: EditText
    private lateinit var tvPermissionStatus: TextView
    private lateinit var tvClipCount: TextView
    private lateinit var tvShotCount: TextView
    private lateinit var tvVideoCount: TextView

    private val prefs by lazy { getSharedPreferences("floating_catch", Context.MODE_PRIVATE) }

    // 悬浮窗权限
    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionStatus()
        if (Settings.canDrawOverlays(this)) {
            requestMediaProjection()
        }
    }

    // 媒体投影权限
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            FloatingService.projectionResultCode = result.resultCode
            FloatingService.projectionData = result.data
            startFloatingService()
        } else {
            Toast.makeText(this, "需要屏幕录制权限才能截图/录屏", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggle = findViewById(R.id.btnToggle)
        etSavePath = findViewById(R.id.etSavePath)
        tvPermissionStatus = findViewById(R.id.tvPermissionStatus)
        tvClipCount = findViewById(R.id.tvClipCount)
        tvShotCount = findViewById(R.id.tvShotCount)
        tvVideoCount = findViewById(R.id.tvVideoCount)

        // 恢复保存路径
        etSavePath.setText(prefs.getString("save_path", getDefaultSavePath()))

        btnToggle.setOnClickListener {
            val path = etSavePath.text.toString().trim()
            if (path.isNotEmpty()) {
                prefs.edit().putString("save_path", path).apply()
            }
            if (FloatingService.isRunning) {
                stopFloatingService()
            } else {
                checkPermissionsAndStart()
            }
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        updateStats()
        updateUI()
    }

    private fun getDefaultSavePath(): String {
        // 坚果云常见同步目录
        val candidates = listOf(
            "/storage/emulated/0/坚果云/Inbox/",
            "/storage/emulated/0/Nutstore/Inbox/",
            "/storage/emulated/0/Documents/Inbox/"
        )
        for (path in candidates) {
            val dir = java.io.File(path)
            if (dir.parentFile?.exists() == true) return path
        }
        return "/storage/emulated/0/FloatingCatch/Inbox/"
    }

    private fun updatePermissionStatus() {
        val msgs = mutableListOf<String>()
        if (!Settings.canDrawOverlays(this)) msgs.add("缺少悬浮窗权限")
        if (FloatingService.projectionData == null) msgs.add("缺少屏幕录制权限")
        tvPermissionStatus.text = if (msgs.isEmpty()) "所有权限已就绪" else msgs.joinToString(" · ")
        tvPermissionStatus.setTextColor(
            if (msgs.isEmpty()) 0xFF4CAF50.toInt() else 0xFFFF6B8A.toInt()
        )
    }

    private fun updateStats() {
        val path = prefs.getString("save_path", getDefaultSavePath()) ?: getDefaultSavePath()
        val base = java.io.File(path)
        var clips = 0; var shots = 0; var vids = 0
        if (base.exists()) {
            base.listFiles()?.forEach { f ->
                val name = f.name.lowercase()
                when {
                    name.contains("clip") || name.endsWith(".txt") -> clips++
                    name.contains("shot") || name.endsWith(".png") -> shots++
                    name.contains("video") || name.endsWith(".mp4") -> vids++
                }
            }
        }
        tvClipCount.text = clips.toString()
        tvShotCount.text = shots.toString()
        tvVideoCount.text = vids.toString()
    }

    private fun updateUI() {
        btnToggle.text = if (FloatingService.isRunning) "停止悬浮球" else "启动悬浮球"
        btnToggle.backgroundTintList =
            if (FloatingService.isRunning) android.content.res.ColorStateList.valueOf(0xFFFF6B8A.toInt())
            else android.content.res.ColorStateList.valueOf(0xFF6C63FF.toInt())
    }

    private fun checkPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayLauncher.launch(intent)
            return
        }
        requestMediaProjection()
    }

    private fun requestMediaProjection() {
        if (FloatingService.projectionData != null) {
            startFloatingService()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startFloatingService() {
        val path = etSavePath.text.toString().trim()
        val intent = Intent(this, FloatingService::class.java).apply {
            putExtra("save_path", path)
        }
        ContextCompat.startForegroundService(this, intent)
        updateUI()
        Toast.makeText(this, "悬浮球已启动", Toast.LENGTH_SHORT).show()
    }

    private fun stopFloatingService() {
        stopService(Intent(this, FloatingService::class.java))
        updateUI()
        Toast.makeText(this, "悬浮球已停止", Toast.LENGTH_SHORT).show()
    }
}
