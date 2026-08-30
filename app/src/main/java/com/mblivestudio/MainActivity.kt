package com.mblivestudio

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView
import com.pedro.encoder.input.gl.render.filters.AndroidViewFilterRender

class MainActivity : Activity(), ConnectChecker, SurfaceHolder.Callback {
    
    private lateinit var rtmpCamera: RtmpCamera2
    private lateinit var openGlView: OpenGlView
    private lateinit var overlayContainer: RelativeLayout
    private lateinit var viewFilterRender: AndroidViewFilterRender

    private lateinit var webOverlay: WebView
    private lateinit var inputWebUrl: EditText
    private lateinit var btnLoadWeb: Button
    private lateinit var btnGoLive: Button

    private var isWebVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        openGlView = findViewById(R.id.surfaceView)
        overlayContainer = findViewById(R.id.overlayContainer)
        webOverlay = findViewById(R.id.webOverlay)
        inputWebUrl = findViewById(R.id.inputWebUrl)
        btnLoadWeb = findViewById(R.id.btnLoadWeb)
        btnGoLive = findViewById(R.id.btnGoLive)
        
        rtmpCamera = RtmpCamera2(openGlView, this)
        openGlView.holder.addCallback(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
        }

        // मास्टर फिल्टर जो WebView को भी स्ट्रीम में भेजेगा
        viewFilterRender = AndroidViewFilterRender()
        viewFilterRender.view = overlayContainer
        rtmpCamera.glInterface.setFilter(viewFilterRender)

        // WEB OVERLAY SETUP (Transparent & JavaScript Enabled)
        webOverlay.setBackgroundColor(Color.TRANSPARENT)
        webOverlay.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webOverlay.settings.javaScriptEnabled = true
        webOverlay.settings.domStorageEnabled = true
        webOverlay.settings.mediaPlaybackRequiresUserGesture = false
        webOverlay.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webOverlay.webViewClient = WebViewClient()

        // WEB OVERLAY BUTTON LOGIC
        btnLoadWeb.setOnClickListener {
            if (!isWebVisible) {
                val url = inputWebUrl.text.toString()
                if (url.isNotEmpty()) {
                    webOverlay.loadUrl(url)
                    webOverlay.visibility = View.VISIBLE
                    btnLoadWeb.text = "CLOSE WEB OVERLAY"
                    isWebVisible = true
                } else {
                    Toast.makeText(this, "Please enter a valid URL", Toast.LENGTH_SHORT).show()
                }
            } else {
                webOverlay.loadUrl("about:blank")
                webOverlay.visibility = View.GONE
                btnLoadWeb.text = "LOAD WEB OVERLAY"
                isWebVisible = false
            }
        }

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

    private fun startCameraPreview() {
        if (!rtmpCamera.isOnPreview) {
            if (rtmpCamera.prepareAudio() && rtmpCamera.prepareVideo()) rtmpCamera.startPreview()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        startCameraPreview()
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (rtmpCamera.isStreaming) rtmpCamera.stopStream()
        if (rtmpCamera.isOnPreview) rtmpCamera.stopPreview()
    }

    override fun onAuthError() {}
    override fun onAuthSuccess() {}
    override fun onConnectionFailed(reason: String) {}
    override fun onConnectionStarted(url: String) {}
    override fun onConnectionSuccess() {}
    override fun onDisconnect() {}
    override fun onNewBitrate(bitrate: Long) {}
}
