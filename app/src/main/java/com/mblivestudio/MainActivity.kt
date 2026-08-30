package com.mblivestudio

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView

// नए Imports (Text Overlay और Filters के लिए)
import com.pedro.encoder.input.gl.render.filters.object.TextObjectFilterRender
import com.pedro.encoder.utils.gl.TextObject
import com.pedro.encoder.input.gl.render.filters.NoFilterRender

class MainActivity : Activity(), ConnectChecker, SurfaceHolder.Callback {
    
    private lateinit var rtmpCamera: RtmpCamera2
    private lateinit var openGlView: OpenGlView
    private lateinit var btnGoLive: Button
    private lateinit var btnTextOverlay: Button
    
    private var isTextVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        openGlView = findViewById(R.id.surfaceView)
        btnGoLive = findViewById(R.id.btnGoLive)
        btnTextOverlay = findViewById(R.id.btnTextOverlay)
        
        rtmpCamera = RtmpCamera2(openGlView, this)
        openGlView.holder.addCallback(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !hasPermissions()) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
        }

        // --- TEXT OVERLAY LOGIC ---
        btnTextOverlay.setOnClickListener {
            if (!isTextVisible) {
                // टेक्स्ट का डिज़ाइन सेट करना
                val textObject = TextObject()
                textObject.text = "MB LIVE STUDIO - EXCLUSIVE"
                textObject.textSize = 60f
                textObject.textColor = Color.YELLOW
                
                // टेक्स्ट को वीडियो स्ट्रीम के अंदर डालना
                val textFilter = TextObjectFilterRender()
                textFilter.setTextObject(textObject)
                
                rtmpCamera.glInterface.setFilter(textFilter)
                
                btnTextOverlay.text = "TEXT OVERLAY (ON)"
                isTextVisible = true
            } else {
                // फिल्टर हटाना
                rtmpCamera.glInterface.setFilter(NoFilterRender())
                
                btnTextOverlay.text = "TEXT OVERLAY (OFF)"
                isTextVisible = false
            }
        }

        // --- GO LIVE LOGIC ---
        btnGoLive.setOnClickListener {
            if (!rtmpCamera.isStreaming) {
                rtmpCamera.startStream("rtmp://a.rtmp.youtube.com/live2/YOUR_STREAM_KEY")
                btnGoLive.text = "STOP STREAM"
            } else {
                rtmpCamera.stopStream()
                btnGoLive.text = "GO LIVE"
            }
        }
    }

    private fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                   checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun startCameraPreview() {
        if (!rtmpCamera.isOnPreview) {
            if (rtmpCamera.prepareAudio() && rtmpCamera.prepareVideo()) {
                rtmpCamera.startPreview()
            } else {
                Toast.makeText(this, "कैमरा एरर", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPermissions()) {
            startCameraPreview()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (hasPermissions()) {
            startCameraPreview()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (rtmpCamera.isStreaming) rtmpCamera.stopStream()
        if (rtmpCamera.isOnPreview) rtmpCamera.stopPreview()
    }

    // --- नेटवर्क और स्ट्रीम स्टेटस के फंक्शन ---
    override fun onAuthError() {}
    override fun onAuthSuccess() {}
    override fun onConnectionFailed(reason: String) {}
    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {}
    override fun onDisconnect() {}
    override fun onNewBitrate(bitrate: Long) {}
}
