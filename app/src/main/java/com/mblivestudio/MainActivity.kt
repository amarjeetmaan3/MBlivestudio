package com.mblivestudio

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView

class MainActivity : Activity(), ConnectChecker {
    
    private lateinit var rtmpCamera: RtmpCamera2
    private lateinit var openGlView: OpenGlView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        openGlView = findViewById(R.id.surfaceView)
        rtmpCamera = RtmpCamera2(openGlView, this)

        // Request Camera & Mic Permissions on Startup
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
            }
        }

        val btnGoLive = findViewById<Button>(R.id.btnGoLive)
        btnGoLive.setOnClickListener {
            if (!rtmpCamera.isStreaming) {
                if (rtmpCamera.prepareAudio() && rtmpCamera.prepareVideo()) {
                    // Test RTMP URL (Will be replaced by YouTube API later)
                    rtmpCamera.startStream("rtmp://a.rtmp.youtube.com/live2/YOUR_STREAM_KEY")
                    btnGoLive.text = "STOP STREAM"
                }
            } else {
                rtmpCamera.stopStream()
                btnGoLive.text = "GO LIVE"
            }
        }
    }

    override fun onAuthError() {}
    override fun onAuthSuccess() {}
    override fun onConnectionClosed() {}
    override fun onConnectionFailed(reason: String) {}
    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {}
    override fun onDisconnect() {}
    override fun onNewBitrate(bitrate: Long) {}
}
