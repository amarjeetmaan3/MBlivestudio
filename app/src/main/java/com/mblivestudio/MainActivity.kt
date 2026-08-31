package com.mblivestudio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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

import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.ApiException

import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.*

class MainActivity : Activity(), ConnectChecker, SurfaceHolder.Callback {
    
    private lateinit var rtmpCamera: RtmpCamera2
    private lateinit var openGlView: OpenGlView
    private lateinit var overlayContainer: RelativeLayout
    private lateinit var viewFilterRender: AndroidViewFilterRender

    private lateinit var webOverlay: WebView
    private lateinit var etWebUrl: EditText
    private lateinit var btnApplyWeb: Button

    private lateinit var dragScoreboard: LinearLayout
    private lateinit var scoreMainText: TextView
    private lateinit var scoreSubText: TextView
    private lateinit var btnToggleScore: Button
    private lateinit var etScoreMain: EditText
    private lateinit var etScoreSub: EditText

    private lateinit var btnAddText: Button
    private lateinit var etControlText: EditText
    private lateinit var btnAddLogo: Button
    private lateinit var btnRemoveSelected: Button
    
    private lateinit var btnSignInYouTube: Button
    private lateinit var btnGoLive: Button
    private lateinit var btnSwitchCamera: Button
    private lateinit var btnMicToggle: Button

    private lateinit var etStreamTitle: EditText
    private lateinit var etStreamDesc: EditText
    private lateinit var spinnerPrivacy: Spinner

    private var selectedOverlay: View? = null
    private var isAudioMuted = false

    private val PICK_IMAGE_REQUEST = 101
    private val SIGN_IN_REQUEST = 102
    private val REQUEST_AUTHORIZATION = 1001

    private lateinit var googleSignInClient: GoogleSignInClient
    private var connectedAccountEmail: String? = null

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
        
        dragScoreboard = findViewById(R.id.dragScoreboard)
        scoreMainText = findViewById(R.id.scoreMainText)
        scoreSubText = findViewById(R.id.scoreSubText)
        btnToggleScore = findViewById(R.id.btnToggleScore)
        etScoreMain = findViewById(R.id.etScoreMain)
        etScoreSub = findViewById(R.id.etScoreSub)

        btnAddText = findViewById(R.id.btnAddText)
        etControlText = findViewById(R.id.etControlText)
        btnAddLogo = findViewById(R.id.btnAddLogo)
        btnRemoveSelected = findViewById(R.id.btnRemoveSelected)
        
        btnSignInYouTube = findViewById(R.id.btnSignInYouTube)
        btnGoLive = findViewById(R.id.btnGoLive)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        btnMicToggle = findViewById(R.id.btnMicToggle)

        etStreamTitle = findViewById(R.id.etStreamTitle)
        etStreamDesc = findViewById(R.id.etStreamDesc)
        spinnerPrivacy = findViewById(R.id.spinnerPrivacy)

        val privacyOptions = arrayOf("Public", "Unlisted", "Private")
        spinnerPrivacy.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, privacyOptions)
        spinnerPrivacy.setSelection(1)
        
        rtmpCamera = RtmpCamera2(openGlView, this)
        openGlView.holder.addCallback(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
        }

        viewFilterRender = AndroidViewFilterRender()
        viewFilterRender.view = overlayContainer
        rtmpCamera.glInterface.setFilter(viewFilterRender)

        webOverlay.setBackgroundColor(Color.TRANSPARENT)
        webOverlay.setLayerType(View.LAYER_TYPE_SOFTWARE, null) 
        webOverlay.settings.javaScriptEnabled = true
        webOverlay.settings.domStorageEnabled = true
        webOverlay.webViewClient = WebViewClient()
        webOverlay.webChromeClient = WebChromeClient()

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope("https://www.googleapis.com/auth/youtube"))
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null) {
            connectedAccountEmail = account.email
            btnSignInYouTube.text = "SIGNED IN AS: ${account.email}"
            btnSignInYouTube.setBackgroundColor(Color.parseColor("#4CAF50"))
            btnSignInYouTube.setTextColor(Color.WHITE)
        }

        btnSignInYouTube.setOnClickListener {
            startActivityForResult(googleSignInClient.signInIntent, SIGN_IN_REQUEST)
        }

        btnGoLive.setOnClickListener {
            if (rtmpCamera.isStreaming) {
                rtmpCamera.stopStream()
                btnGoLive.text = "GO LIVE"
                btnGoLive.setBackgroundColor(Color.parseColor("#D32F2F"))
                Toast.makeText(this@MainActivity, "Stream Stopped.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            } 
            
            if (!rtmpCamera.isOnPreview) {
                Toast.makeText(this@MainActivity, "Camera not ready! Preparing...", Toast.LENGTH_SHORT).show()
                startCameraPreview()
                return@setOnClickListener
            }

            val currentAccount = GoogleSignIn.getLastSignedInAccount(this)
            if (currentAccount == null) {
                Toast.makeText(this@MainActivity, "Please Sign In with YouTube first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val youtubeScope = Scope("https://www.googleapis.com/auth/youtube")
            if (!GoogleSignIn.hasPermissions(currentAccount, youtubeScope)) {
                GoogleSignIn.requestPermissions(this, REQUEST_AUTHORIZATION, currentAccount, youtubeScope)
                return@setOnClickListener
            }
            
            createYouTubeBroadcast()
        }

        // 🌟 SAFE CAMERA FLIP ENGINE 🌟
        btnSwitchCamera.setOnClickListener { 
            try {
                rtmpCamera.switchCamera()
                if (!rtmpCamera.isStreaming) {
                    rtmpCamera.stopPreview()
                    startCameraPreview()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Cannot flip camera right now.", Toast.LENGTH_SHORT).show()
            }
        }

        btnMicToggle.setOnClickListener {
            if (isAudioMuted) {
                rtmpCamera.enableAudio()
                isAudioMuted = false
                btnMicToggle.text = "MIC: ON"
                btnMicToggle.setBackgroundColor(Color.parseColor("#4CAF50"))
            } else {
                rtmpCamera.disableAudio()
                isAudioMuted = true
                btnMicToggle.text = "MIC: OFF"
                btnMicToggle.setBackgroundColor(Color.parseColor("#E53935"))
            }
        }

        etControlText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { if (selectedOverlay is TextView && selectedOverlay != scoreMainText && selectedOverlay != scoreSubText) { (selectedOverlay as TextView).text = s.toString() } }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        etScoreMain.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { scoreMainText.text = s.toString() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}Apne `MainActivity.kt` mein purane `startCameraPreview()` method ko is naye **Smart Auto-Detect Engine** wale code se replace kar dijiye. Yeh **M.B. Live Studio** ko tablet par crash hone se bachaane ke liye device ke supported hardware resolutions ko dynamically fetch karega aur sabse stable settings auto-apply karega.

```kotlin
    // 🌟 SMART AUTO-DETECT ENGINE (Tablet Optimized) 🌟
    private fun startCameraPreview() { 
        if (rtmpCamera.isOnPreview) return

        var vReady = false

        // Engine Phase 1: Dynamically fetch hardware-supported resolutions
        val supportedSizes = rtmpCamera.resolutionsBack
        if (supportedSizes != null && supportedSizes.isNotEmpty()) {
            // Filter for safe streaming resolutions (Max 720p to prevent tablet MediaCodec crashes)
            val safeResolutions = supportedSizes.filter { it.width <= 1280 && it.height <= 720 }
                                                .sortedByDescending { it.width * it.height }

            for (size in safeResolutions) {
                try {
                    // Smart Bitrate Allocation (2 Mbps for HD, 1.2 Mbps for SD)
                    val targetBitrate = if (size.width >= 1280) 2000 * 1024 else 1200 * 1024
                    if (rtmpCamera.prepareVideo(size.width, size.height, 30, targetBitrate, false, 0)) {
                        vReady = true
                        runOnUiThread { 
                            Toast.makeText(this, "Engine Active: ${size.width}x${size.height}", Toast.LENGTH_SHORT).show() 
                        }
                        break
                    }
                } catch (e: Exception) {
                    continue // Agar ek fail ho jaye, toh next safe resolution try karega
                }
            }
        }

        // Engine Phase 2: Failsafe Hardcoded Fallback
        if (!vReady) {
            val fallbackWidths = intArrayOf(1280, 854, 640)
            val fallbackHeights = intArrayOf(720, 480, 360)
            for (i in fallbackWidths.indices) {
                try {
                    if (rtmpCamera.prepareVideo(fallbackWidths[i], fallbackHeights[i], 30)) {
                        vReady = true
                        break
                    }
                } catch (e: Exception) {}
            }
        }

        // Engine Phase 3: Absolute Library Default
        if (!vReady) {
            try { vReady = rtmpCamera.prepareVideo() } catch (e: Exception) {}
        }

        // Smart Audio Detector
        var aReady = false
        try {
            // Try High-Quality Stereo AAC first
            aReady = rtmpCamera.prepareAudio(128 * 1024, 44100, true, false, false) 
            if (!aReady) aReady = rtmpCamera.prepareAudio() // Standard fallback
        } catch (e: Exception) {}

        if (vReady && aReady) {
            rtmpCamera.startPreview()
        } else {
            runOnUiThread { 
                Toast.makeText(this, "CAMERA ERROR: Tablet hardware encoder not supported.", Toast.LENGTH_LONG).show() 
            }
        }
    }
