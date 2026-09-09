package com.mblivestudio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.pedro.library.view.OpenGlView

class MainActivity : Activity(), EngineCallback {

    private lateinit var streamEngine: StreamEngine
    private lateinit var openGlView: OpenGlView
    private lateinit var overlayContainer: RelativeLayout
    
    private lateinit var btnGoLive: Button
    private lateinit var btnSwitchCamera: ImageButton
    private lateinit var btnBluetoothMic: ImageButton

    private var isAudioMuted = false
    private val overlayHandler = Handler(Looper.getMainLooper())
    private var pendingRefresh = false

    // --- SAFETY PATCH: Reusable Bitmap Buffer (Zero Memory Churn) ---
    private var reusableBitmap: Bitmap? = null
    private var reusableCanvas: Canvas? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContentView(R.layout.activity_main)

        openGlView = findViewById(R.id.surfaceView)
        overlayContainer = findViewById(R.id.overlayContainer)
        btnGoLive = findViewById(R.id.btnGoLive)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        btnBluetoothMic = findViewById(R.id.btnBluetoothMic)

        // Initialize Stream Engine
        streamEngine = StreamEngine(this, openGlView, this)

        // Link the OpenGlView surface lifecycle to the stream engine
        openGlView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                checkPermissionsAndStartPreview(holder)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                streamEngine.stopPreview()
            }
        })

        btnSwitchCamera.setOnClickListener {
            streamEngine.switchCamera()
        }

        btnBluetoothMic.setOnClickListener {
            if (streamEngine.rtmpCamera.isStreaming) {
                Toast.makeText(this, "Stop stream first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2)
                return@setOnClickListener
            }
            streamEngine.toggleBluetoothMic()
        }

        btnGoLive.setOnClickListener {
            if (streamEngine.rtmpCamera.isStreaming) {
                streamEngine.stopStream()
            } else {
                Toast.makeText(this, "Add YouTube URL logic here", Toast.LENGTH_SHORT).show()
                // streamEngine.startStream(rtmpUrl)
            }
        }

        openGlView.setOnTouchListener { _, event ->
            if (event.pointerCount > 1) {
                streamEngine.setZoom(event)
                true
            } else false
        }
    }

    private fun checkPermissionsAndStartPreview(holder: SurfaceHolder? = null) {
        val targetHolder = holder ?: openGlView.holder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !streamEngine.hasCameraPermissions()) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
        } else {
            // Permissions are granted, start the preview on the actual surface
            streamEngine.tryStartCameraPreview(targetHolder.surface)
        }
    }

    // --- SAFETY PATCH: Bitmap Buffer Re-use Logic ---
    private fun updateSnapshot(delay: Long = 200) {
        if (!streamEngine.rtmpCamera.isOnPreview || overlayContainer.width == 0 || overlayContainer.height == 0 || pendingRefresh) return
        pendingRefresh = true
        
        overlayHandler.postDelayed({
            try {
                val w = overlayContainer.width
                val h = overlayContainer.height
                
                // Create only once, reuse forever! (Claude's Fix)
                if (reusableBitmap == null || reusableBitmap!!.width != w || reusableBitmap!!.height != h) {
                    reusableBitmap?.recycle()
                    reusableBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    reusableCanvas = Canvas(reusableBitmap!!)
                }
                
                reusableBitmap!!.eraseColor(Color.TRANSPARENT)
                overlayContainer.draw(reusableCanvas!!)
                
                streamEngine.setOverlayBitmap(reusableBitmap!!)
            } catch (e: Exception) { e.printStackTrace() }
            pendingRefresh = false
        }, delay)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            checkPermissionsAndStartPreview()
        }
    }

    override fun onConnectionSuccess() {
        runOnUiThread {
            btnGoLive.text = "STOP STREAM"
            btnGoLive.setBackgroundColor(Color.parseColor("#E53935"))
            Toast.makeText(this, "🔥 YOU ARE LIVE!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onConnectionFailed(reason: String) {
        runOnUiThread {
            btnGoLive.text = "GO LIVE"
            Toast.makeText(this, "RTMP Error: $reason", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDisconnect() {
        runOnUiThread {
            btnGoLive.text = "GO LIVE"
            btnGoLive.setBackgroundColor(Color.parseColor("#D32F2F"))
        }
    }

    override fun onMicStatusChanged(message: String, isSuccess: Boolean) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            if (isSuccess && message.contains("selected")) {
                btnBluetoothMic.setColorFilter(Color.parseColor("#4CAF50"))
            } else {
                btnBluetoothMic.clearColorFilter()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayHandler.removeCallbacksAndMessages(null)
        
        // --- SAFETY PATCH: Prevent Activity Leak ---
        streamEngine.detachCallback()
        streamEngine.release()
        
        reusableBitmap?.let { if (!it.isRecycled) it.recycle() }
        reusableBitmap = null
        reusableCanvas = null
    }
}
