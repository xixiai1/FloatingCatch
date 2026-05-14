package com.floatingcatch.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FloatingService : Service() {

    companion object {
        var isRunning = false
        var projectionResultCode: Int = Activity.RESULT_CANCELED
        var projectionData: Intent? = null
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var savePath: String
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var handler: Handler

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var videoFile: File? = null

    // 手势检测
    private var lastClickTime = 0L
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartTime = 0L
    private var isDragging = false
    private var longPressRunnable: Runnable? = null
    private val DOUBLE_CLICK_THRESHOLD = 300L
    private val LONG_PRESS_THRESHOLD = 600L
    private val DRAG_THRESHOLD = 10

    // 统计
    private var clipCount = 0
    private var shotCount = 0
    private var videoCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        savePath = intent?.getStringExtra("save_path")
            ?: "/storage/emulated/0/FloatingCatch/Inbox/"
        File(savePath).mkdirs()

        if (isRunning) return START_STICKY

        startForeground(1, createNotification())
        isRunning = true

        initMediaProjection()
        createFloatingBall()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopRecording()
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
        mediaProjection?.stop()
        virtualDisplay?.release()
    }

    // ========== 通知 ==========
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "floating_catch",
                "悬浮捕获服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "悬浮球正在运行"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "floating_catch")
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("悬浮捕获运行中")
            .setContentText("单击=剪贴板 长按=截图 双击=录屏")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    // ========== 媒体投影 ==========
    private fun initMediaProjection() {
        if (projectionData == null) return
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(projectionResultCode, projectionData!!)
    }

    // ========== 悬浮球 ==========
    private fun createFloatingBall() {
        // 主容器
        floatingView = FrameLayout(this).apply {
            val ball = ImageView(context).apply {
                val size = dp2px(48)
                layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#CC6C63FF"))
                    setStroke(dp2px(2), Color.parseColor("#88FFFFFF"))
                }
                setImageResource(android.R.drawable.ic_menu_edit)
                setColorFilter(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                padding = dp2px(10)
                id = android.R.id.icon
            }
            addView(ball)
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dp2px(200)
        }

        windowManager.addView(floatingView, params)
        setupTouchEvents()
        pulseAnimation()
    }

    private fun setupTouchEvents() {
        floatingView.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    isDragging = false

                    // 长按检测
                    longPressRunnable = Runnable {
                        if (!isDragging) {
                            takeScreenshot()
                            view.animate().scaleX(1.3f).scaleY(1.3f).setDuration(100)
                                .withEndAction { view.animate().scaleX(1f).scaleY(1f).setDuration(100) }
                        }
                    }
                    handler.postDelayed(longPressRunnable!!, LONG_PRESS_THRESHOLD)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
                        isDragging = true
                        longPressRunnable?.let { handler.removeCallbacks(it) }
                        params.x = (event.rawX - floatingView.width / 2).toInt()
                        params.y = (event.rawY - floatingView.height / 2).toInt()
                        windowManager.updateViewLayout(floatingView, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    if (!isDragging) {
                        val now = System.currentTimeMillis()
                        if (now - lastClickTime < DOUBLE_CLICK_THRESHOLD) {
                            // 双击：录屏
                            toggleRecording()
                        } else {
                            // 延迟执行单击，等双击判定
                            handler.postDelayed({
                                if (System.currentTimeMillis() - lastClickTime >= DOUBLE_CLICK_THRESHOLD - 50) {
                                    handleSingleClick()
                                }
                            }, DOUBLE_CLICK_THRESHOLD)
                        }
                        lastClickTime = now
                    } else {
                        // 拖动结束，吸附边缘
                        snapToEdge()
                    }
                }
            }
            true
        }
    }

    private fun handleSingleClick() {
        // 读取剪贴板并保存
        val clipData = clipboardManager.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val item = clipData.getItemAt(0)
            val text = item?.text?.toString() ?: ""
            if (text.isNotBlank()) {
                saveClipboard(text)
                showFeedback("已保存")
            } else {
                showFeedback("剪贴板为空")
            }
        } else {
            showFeedback("剪贴板为空")
        }
    }

    private fun snapToEdge() {
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val centerX = params.x + floatingView.width / 2
        val targetX = if (centerX < screenWidth / 2) 0 else screenWidth - floatingView.width
        val animator = ObjectAnimator.ofInt(params.x, targetX)
        animator.duration = 200
        animator.interpolator = AccelerateDecelerateInterpolator()
        animator.addUpdateListener {
            params.x = it.animatedValue as Int
            if (::floatingView.isInitialized) {
                windowManager.updateViewLayout(floatingView, params)
            }
        }
        animator.start()
    }

    private fun pulseAnimation() {
        val icon = floatingView.findViewById<ImageView>(android.R.id.icon)
        val animator = ObjectAnimator.ofFloat(icon, "alpha", 1f, 0.6f, 1f)
        animator.duration = 2000
        animator.repeatCount = ValueAnimator.INFINITE
        animator.start()
    }

    // ========== 保存剪贴板 ==========
    private fun saveClipboard(text: String) {
        val now = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(savePath, "clip_${now}.txt")

        // 提取链接
        val urlPattern = Regex("https?://[\\w\\./?=#&%\\-]+")
        val urls = urlPattern.findAll(text).map { it.value }.toList()
        val isUrl = text.startsWith("http") || urls.isNotEmpty()

        val content = buildString {
            appendLine("来源: 剪贴板捕获")
            appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine("类型: ${if (isUrl) "链接" else "文本"}")
            appendLine("---")
            if (urls.isNotEmpty()) {
                appendLine("链接: ${urls.joinToString(", ")}")
                if (text.length > urls.sumOf { it.length } + 10) {
                    val otherText = urls.fold(text) { acc, url -> acc.replace(url, "") }.trim()
                    if (otherText.isNotBlank()) appendLine("附带文本: $otherText")
                }
            } else {
                appendLine(text)
            }
        }

        file.writeText(content)
        clipCount++
    }

    // ========== 截图 ==========
    private fun takeScreenshot() {
        val projection = mediaProjection
        if (projection == null) {
            showFeedback("需要屏幕录制权限")
            return
        }

        showFeedback("截图中...")
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        // 使用 ImageReader 截图
        val handler2 = Handler(Looper.getMainLooper())
        Thread {
            try {
                val imageReader = android.media.ImageReader.newInstance(
                    width, height, PixelFormat.RGBA_8888, 2
                )
                val vd = projection.createVirtualDisplay(
                    "Screenshot",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.surface, null, handler2
                )

                // 等一帧
                Thread.sleep(150)
                val image = imageReader.acquireLatestImage()
                if (image != null) {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val bitmap = android.graphics.Bitmap.createBitmap(
                        width + rowPadding / pixelStride,
                        height,
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)

                    // 裁剪到实际宽度
                    val croppedBitmap = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    bitmap.recycle()

                    val now = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val file = File(savePath, "shot_${now}.png")
                    FileOutputStream(file).use { out ->
                        croppedBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    }
                    croppedBitmap.recycle()
                    image.close()
                    vd.release()
                    imageReader.close()

                    shotCount++
                    handler2.post { showFeedback("截图已保存") }
                } else {
                    vd.release()
                    imageReader.close()
                    handler2.post { showFeedback("截图失败") }
                }
            } catch (e: Exception) {
                handler2.post { showFeedback("截图错误: ${e.message}") }
            }
        }.start()
    }

    // ========== 录屏 ==========
    private fun toggleRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        val projection = mediaProjection
        if (projection == null) {
            showFeedback("需要屏幕录制权限")
            return
        }

        val metrics = resources.displayMetrics
        val width = (metrics.widthPixels * 0.7).toInt()
        val height = (metrics.heightPixels * 0.7).toInt()
        val density = metrics.densityDpi

        val now = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        videoFile = File(savePath, "video_${now}.mp4")

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(width, height)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(3_000_000)
            setOutputFile(videoFile!!.absolutePath)
            prepare()
        }

        virtualDisplay = projection.createVirtualDisplay(
            "FloatingCatch",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder!!.surface,
            null, handler
        )

        mediaRecorder!!.start()
        isRecording = true
        // 悬浮球变红表示正在录制
        val icon = floatingView.findViewById<ImageView>(android.R.id.icon)
        (floatingView as FrameLayout).background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#CCFF4444"))
            setStroke(dp2px(3), Color.parseColor("#FFFF0000"))
        }
        showFeedback("录屏开始")
        videoCount++
    }

    private fun stopRecording() {
        if (!isRecording) return
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null
        virtualDisplay?.release()
        virtualDisplay = null
        isRecording = false

        // 恢复紫色球
        val icon = floatingView.findViewById<ImageView>(android.R.id.icon)
        (floatingView as FrameLayout).background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#CC6C63FF"))
            setStroke(dp2px(2), Color.parseColor("#88FFFFFF"))
        }
        showFeedback("录屏已保存")
    }

    // ========== 工具 ==========
    private fun showFeedback(msg: String) {
        handler.post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp2px(dp: Int): Int {
        return (dp * resources.displayMetrics.density + 0.5f).toInt()
    }
}
