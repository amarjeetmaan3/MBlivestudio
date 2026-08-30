package com.mblivestudio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
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

    // Web Overlay Components
    private lateinit var webOverlay: WebView
    private lateinit var etWebUrl: EditText
    private lateinit var btnApplyWeb: Button

    private lateinit var dragText: TextView
    private lateinit var dragScoreboard: LinearLayout
    private lateinit var btnTitleText: Button
    private lateinit var btnScoreboard: Button
    private lateinit var btnGoLive: Button

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        openGlView = findViewById(R.id.surfaceView)
        overlayContainer = findViewById(R.id.overlayContainer)
        
        webOverlay = findViewById(R.id.webOverlay)
        etWebUrl = findViewById(R.id.etWebUrl)
        btnApplyWeb = findViewById(R.id.btnApplyWeb)

        dragText = findViewById(R.id.dragText)
        dragScoreboard = findViewById(R.id.dragScoreboard)
        btnTitleText = findViewById(R.id.btnTitleText)
        btnScoreboard = findViewById(R.id.btnScoreboard)
        btnGoLive = findViewById(R.id.btnGoLive)
        
        rtmpCamera = RtmpCamera2(openGlView, this)
        openGlView.holder.addCallback(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
        }

        viewFilterRender = AndroidViewFilterRender()
        viewFilterRender.view = overlayContainer
        rtmpCamera.glInterface.setFilter(viewFilterRender)

        // WEB OVERLAY SETUP
        webOverlay.setBackgroundColor(Color.TRANSPARENT)
        webOverlay.setLayerType(View.LAYER_TYPE_SOFTWARE, null) 
        webOverlay.settings.javaScriptEnabled = true
        webOverlay.settings.domStorageEnabled = true
        webOverlay.settings.mediaPlaybackRequiresUserGesture = false
        webOverlay.webViewClient = WebViewClient()
        webOverlay.webChromeClient = WebChromeClient()

        btnApplyWeb.setOnClickListener {
            if (webOverlay.visibility == View.GONE) {
                val url = etWebUrl.text.toString().trim()
                if (url.isNotEmpty()) {
                    val finalUrl = if (!url.startsWith("http")) "https://$url" else url
                    webOverlay.loadUrl(finalUrl)
                    webOverlay.visibility = View.VISIBLE
                    btnApplyWeb.text = "HIDE WEB OVERLAY"
                } else {
                    Toast.makeText(this, "Please enter a URL", Toast.LENGTH_SHORT).show()
                }
            } else {
                webOverlay.visibility = View.GONE
                webOverlay.loadUrl("about:blank")
                btnApplyWeb.text = "SHOW WEB OVERLAY"
            }
        }

        btnTitleText.setOnClickListener {
            val isVis = dragText.visibility == View.VISIBLE
            dragText.visibility = if(isVis) View.GONE else View.VISIBLE
            btnTitleText.text = if(isVis) "SHOW TITLE" else "HIDE TITLE"
        }

        btnScoreboard.setOnClickListener {
            val isVis = dragScoreboard.visibility == View.VISIBLE
            dragScoreboard.visibility = if(isVis) View.GONE else View.VISIBLE
            btnScoreboard.text = if(isVis) "SHOW SCORECARD" else "HIDE SCORECARD"
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startCameraPreview()
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
