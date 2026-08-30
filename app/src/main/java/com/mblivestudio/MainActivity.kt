package com.mblivestudio

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.Button
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView

// यहाँ SurfaceHolder.Callback ऐड किया गया है
class MainActivity : Activity(), ConnectChecker, SurfaceHolder.Callback {
    
    private lateinit var rtmpCamera: RtmpCamera2
    private lateinit var openGlView: OpenGlView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        openGlView = findViewById(R.id.surfaceView)
        rtmpCamera = RtmpCamera2(openGlView, this)

        // स्क्रीन रेडी होते ही कैमरा ऑन करने का ट्रिगर
        openGlView.holder.addCallback(this)

        // परमिशंस मांगना
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
            }
        }

        val btnGoLive = findViewById<Button>(R.id.btnGoLive)
        btnGoLive.setOnClickListener {
            if (!rtmpCamera.isStreaming) {
                if (rtmpCamera.prepareAudio() && rtmpCamera.prepareVideo()) {
                    rtmpCamera.startStream("rtmp://a.rtmp.youtube.com/live2/YOUR_STREAM_KEY")
                    btnGoLive.text = "STOP STREAM"
                }
            } else {
                rtmpCamera.stopStream()
                btnGoLive.text = "GO LIVE"
            }
        }
    }

    // --- कैमरा प्रीव्यू को आटोमेटिक कंट्रोल करने के फंक्शन ---

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // जैसे ही काली स्क्रीन लोड होगी, कैमरा प्रीव्यू स्टार्ट हो जाएगा
        if (!rtmpCamera.isOnPreview) {
            rtmpCamera.startPreview()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // ऐप बंद करने पर कैमरा और स्ट्रीम दोनों स्टॉप हो जाएंगे
        if (rtmpCamera.isStreaming) {
            rtmpCamera.stopStream()
        }
        if (rtmpCamera.isOnPreview) {
            rtmpCamera.stopPreview()
        }
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
