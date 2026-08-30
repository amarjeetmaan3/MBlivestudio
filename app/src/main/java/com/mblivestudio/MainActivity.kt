package com.mblivestudio

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView

class MainActivity : Activity(), ConnectChecker, SurfaceHolder.Callback {
    
    private lateinit var rtmpCamera: RtmpCamera2
    private lateinit var openGlView: OpenGlView
    private lateinit var btnGoLive: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        openGlView = findViewById(R.id.surfaceView)
        btnGoLive = findViewById(R.id.btnGoLive)
        rtmpCamera = RtmpCamera2(openGlView, this)

        openGlView.holder.addCallback(this)

        // परमिशंस चेक करना
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !hasPermissions()) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
        }

        // GO LIVE बटन सिर्फ स्ट्रीम चालू/बंद करेगा (कैमरा नहीं)
        btnGoLive.setOnClickListener {
            if (!rtmpCamera.isStreaming) {
                // प्रीव्यू पहले से चल रहा है, यहाँ से सीधा डेटा YouTube पर जाएगा
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

    // कैमरा प्रीव्यू स्टार्ट करने का अलग लॉजिक
    private fun startCameraPreview() {
        if (!rtmpCamera.isOnPreview) {
            if (rtmpCamera.prepareAudio() && rtmpCamera.prepareVideo()) {
                rtmpCamera.startPreview()
            } else {
                Toast.makeText(this, "कैमरा लोड करने में एरर", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // अगर यूजर पहली बार परमिशन Allow करता है, तो तुरंत कैमरा ऑन कर दो
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPermissions()) {
            startCameraPreview()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // स्क्रीन रेडी होते ही (और परमिशन होने पर) प्रीव्यू चालू
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
