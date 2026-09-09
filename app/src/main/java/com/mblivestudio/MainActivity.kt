package com.mblivestudio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.*
import com.pedro.library.view.OpenGlView

class MainActivity : Activity(), EngineCallback {

    private lateinit var streamEngine: StreamEngine
    private lateinit var openGlView: OpenGlView
    private lateinit var overlayContainer: RelativeLayout
    
    // Core Buttons
    private lateinit var btnGoLive: Button
    private lateinit var btnSwitchCamera: ImageButton
    private lateinit var btnBluetoothMic: ImageButton
    
    // Restored UI Buttons
    private lateinit var btnAccount: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnOverlay: ImageButton
    private lateinit var btnLiveText: ImageButton
    private lateinit var btnOrientation: ImageButton

    private val overlayHandler = Handler(Looper.getMainLooper())
    private var pendingRefresh = false
    private var reusableBitmap: Bitmap? = null
    private var reusableCanvas: Canvas? = null
    private var isLandscape = true

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
        
        // Find Restored Buttons
        btnAccount = findViewById(R.id.btnAccount)
        btnSettings = findViewById(R.id.btnSettings)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnLiveText = findViewById(R.id.btnLiveText)
        btnOrientation = findViewById(R.id.btnOrientation)

        streamEngine = StreamEngine(this, openGlView, this)

        openGlView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                checkPermissionsAndStartPreview(holder)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                streamEngine.stopPreview()
            }
        })

        setupClickListeners()
    }

    private fun setupClickListeners() {
        btnSwitchCamera.setOnClickListener { streamEngine.switchCamera() }

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
            }
        }

        // Restored Button Actions
        btnAccount.setOnClickListener { Toast.makeText(this, "Account Menu Clicked", Toast.LENGTH_SHORT).show() }
        btnSettings.setOnClickListener { Toast.makeText(this, "Settings Opened", Toast.LENGTH_SHORT).show() }
        btnOverlay.setOnClickListener { Toast.makeText(this, "Overlay Manager Opened", Toast.LENGTH_SHORT).show() }
        btnLiveText.setOnClickListener { Toast.makeText(this, "Live Text Editor Opened", Toast.LENGTH_SHORT).show() }
        
        btnOrientation.setOnClickListener {
            isLandscape = !isLandscape
            requestedOrientation = if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            Toast.makeText(this, "Orientation Switched", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissionsAndStartPreview(holder: SurfaceHolder? = null) {
        val targetHolder = holder ?: openGlView.holder
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !streamEngine.hasCameraPermissions()) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
        } else {
            streamEngine.tryStartCameraPreview(targetHolder.surface)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) checkPermissionsAndStartPreview()
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
            Toast.makeText(this, "Error: $reason", Toast.LENGTH_LONG).show()
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
            if (isSuccess && message == "Mic Active") {
                btnBluetoothMic.setColorFilter(Color.parseColor("#4CAF50"))
            } else {
                btnBluetoothMic.setColorFilter(Color.parseColor("#F44336"))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayHandler.removeCallbacksAndMessages(null)
        streamEngine.detachCallback()
        streamEngine.release()
        reusableBitmap?.let { if (!it.isRecycled) it.recycle() }
        reusableBitmap = null
        reusableCanvas = null
    }
}
