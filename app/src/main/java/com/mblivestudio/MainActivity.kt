package com.mblivestudio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
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
    
    // 🌟 NEW: Retry Engine Variables 🌟
    private var retryCount = 0
    private val MAX_RETRIES = 3
    private var generatedRtmpUrl: String? = null

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
            
            retryCount = 0 // Reset retries before starting
            createYouTubeBroadcast()
        }

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
            override fun afterTextChanged(s: Editable?) { 
                if (selectedOverlay is TextView && selectedOverlay != scoreMainText && selectedOverlay != scoreSubText) { 
                    (selectedOverlay as TextView).text = s.toString() 
                } 
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        etScoreMain.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { scoreMainText.text = s.toString() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        etScoreSub.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { scoreSubText.text = s.toString() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnAddText.setOnClickListener { 
            val text = etControlText.text.toString().trim()
            if (text.isNotEmpty()) { 
                addTextOverlayToScreen(text)
                etControlText.text.clear() 
            } 
        }
        
        btnAddLogo.setOnClickListener { 
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "image/*"
            startActivityForResult(intent, PICK_IMAGE_REQUEST) 
        }
        
        btnRemoveSelected.setOnClickListener { 
            selectedOverlay?.let { 
                if (it != dragScoreboard) { 
                    overlayContainer.removeView(it)
                    selectedOverlay = null 
                } 
            } 
        }
        
        btnToggleScore.setOnClickListener { 
            val isVis = dragScoreboard.visibility == View.VISIBLE
            dragScoreboard.visibility = if(isVis) View.GONE else View.VISIBLE
            btnToggleScore.text = if(isVis) "SHOW SCORECARD ON SCREEN" else "HIDE SCORECARD" 
        }

        makeDraggableAndScalable(dragScoreboard)
        
        btnApplyWeb.setOnClickListener {
            if (webOverlay.visibility == View.GONE) {
                val url = etWebUrl.text.toString().trim()
                if (url.isNotEmpty()) {
                    val finalUrl = if (!url.startsWith("http")) "https://$url" else url
                    webOverlay.loadUrl(finalUrl)
                    webOverlay.visibility = View.VISIBLE
                    btnApplyWeb.text = "HIDE WEB OVERLAY"
                }
            } else {
                webOverlay.visibility = View.GONE
                webOverlay.loadUrl("about:blank")
                btnApplyWeb.text = "SHOW WEB OVERLAY"
            }
        }
    }

    private fun createYouTubeBroadcast() {
        btnGoLive.text = "1/4: CONNECTING API..."
        btnGoLive.isEnabled = false

        val titleInput = etStreamTitle.text.toString().trim()
        val descInput = etStreamDesc.text.toString().trim()
        val privacyInput = spinnerPrivacy.selectedItem.toString().lowercase()

        val finalTitle = if (titleInput.isNotEmpty()) titleInput else "Live from M.B. Live Studio"
        val finalDesc = if (descInput.isNotEmpty()) descInput else "Streaming via Android App"

        Thread {
            try {
                val credential = GoogleAccountCredential.usingOAuth2(this@MainActivity, listOf("https://www.googleapis.com/auth/youtube"))
                val signInAccount = GoogleSignIn.getLastSignedInAccount(this@MainActivity)
                if (signInAccount?.account != null) credential.selectedAccount = signInAccount.account
                else credential.selectedAccountName = connectedAccountEmail

                val transport = NetHttpTransport()
                val jsonFactory = GsonFactory.getDefaultInstance()
                val youtube = YouTube.Builder(transport, jsonFactory, credential).setApplicationName("MBLiveStudio").build()

                runOnUiThread { btnGoLive.text = "2/4: CREATING ROOM..." }

                val broadcastSnippet = LiveBroadcastSnippet()
                broadcastSnippet.title = finalTitle
                broadcastSnippet.description = finalDesc
                broadcastSnippet.scheduledStartTime = DateTime(System.currentTimeMillis()) 

                val broadcastStatus = LiveBroadcastStatus()
                broadcastStatus.privacyStatus = privacyInput
                broadcastStatus.selfDeclaredMadeForKids = false

                val broadcastContentDetails = LiveBroadcastContentDetails()
                broadcastContentDetails.enableAutoStart = true
                broadcastContentDetails.latencyPreference = "ultraLow" 

                var broadcast = LiveBroadcast()
                broadcast.snippet = broadcastSnippet
                broadcast.status = broadcastStatus
                broadcast.contentDetails = broadcastContentDetails
                broadcast = youtube.liveBroadcasts().insert("snippet,status,contentDetails", broadcast).execute()

                runOnUiThread { btnGoLive.text = "3/4: GETTING KEY..." }

                val streamSnippet = LiveStreamSnippet()
                streamSnippet.title = "$finalTitle - Key"

                val cdn = CdnSettings()
                cdn.ingestionType = "rtmp"
                cdn.resolution = "variable" 
                cdn.frameRate = "variable"

                var stream = LiveStream()
                stream.snippet = streamSnippet
                stream.cdn = cdn
                stream = youtube.liveStreams().insert("snippet,cdn", stream).execute()

                val bindRequest = youtube.liveBroadcasts().bind(broadcast.id, "id,contentDetails")
                bindRequest.streamId = stream.id
                bindRequest.execute()

                val finalUrl = "${stream.cdn.ingestionInfo.ingestionAddress}/${stream.cdn.ingestionInfo.streamName}"
                generatedRtmpUrl = finalUrl // Save for retry

                runOnUiThread { 
                    btnGoLive.text = "4/4: CONNECTING CAMERA..." 
                    try {
                        rtmpCamera.startStream(finalUrl)
                    } catch (e: Exception) {
                        btnGoLive.text = "GO LIVE"
                        btnGoLive.isEnabled = true
                        Toast.makeText(this@MainActivity, "Encoder Error: Failed to start streaming.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    btnGoLive.text = "GO LIVE"
                    btnGoLive.isEnabled = true
                    Toast.makeText(this@MainActivity, "API Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_AUTHORIZATION) {
            val account = GoogleSignIn.getLastSignedInAccount(this)
            if (GoogleSignIn.hasPermissions(account, Scope("https://www.googleapis.com/auth/youtube"))) {
                Toast.makeText(this, "Permission Granted! Click GO LIVE again.", Toast.LENGTH_LONG).show()
            }
        }
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null) {
            data.data?.let { uri ->
                val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                addImageOverlayToScreen(bitmap)
            }
        }
        if (requestCode == SIGN_IN_REQUEST) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                connectedAccountEmail = account?.email
                btnSignInYouTube.text = "SIGNED IN AS: ${account?.email}"
                btnSignInYouTube.setBackgroundColor(Color.parseColor("#4CAF50"))
                btnSignInYouTube.setTextColor(Color.WHITE)
            } catch (e: ApiException) { }
        }
    }

    private fun addTextOverlayToScreen(text: String) {
        val textView = TextView(this).apply {
            this.text = text
            setTextColor(Color.YELLOW)
            textSize = 30f
            setTypeface(null, Typeface.BOLD)
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply { 
                addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE) 
            }
        }
        overlayContainer.addView(textView)
        makeDraggableAndScalable(textView)
        selectedOverlay = textView
    }

    private fun addImageOverlayToScreen(bitmap: Bitmap) {
        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            layoutParams = RelativeLayout.LayoutParams(300, 300).apply { 
                addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE) 
            }
        }
        overlayContainer.addView(imageView)
        makeDraggableAndScalable(imageView)
        selectedOverlay = imageView
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggableAndScalable(view: View) {
        val scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean { 
                view.scaleX *= detector.scaleFactor 
                view.scaleY *= detector.scaleFactor 
                return true 
            }
        })
        
        var dX = 0f
        var dY = 0f
        
        view.setOnTouchListener { v, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (!scaleGestureDetector.isInProgress) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { 
                        dX = v.x - event.rawX
                        dY = v.y - event.rawY
                        selectedOverlay = v
                        if (v is TextView && v != dragScoreboard) { 
                            etControlText.setText(v.text) 
                        } 
                    }
                    MotionEvent.ACTION_MOVE -> { 
                        v.x = event.rawX + dX
                        v.y = event.rawY + dY 
                    }
                }
            }
            true
        }
    }

    private fun startCameraPreview() { 
        if (!rtmpCamera.isOnPreview) { 
            var vReady = false
            val widths = intArrayOf(1280, 854, 640, 640, 480)
            val heights = intArrayOf(720, 480, 480, 360, 360)
            
            for (i in widths.indices) {
                try {
                    if (rtmpCamera.prepareVideo(widths[i], heights[i], 30)) {
                        vReady = true
                        break
                    }
                } catch (e: Exception) {}
            }
            
            if (!vReady) {
                try { 
                    vReady = rtmpCamera.prepareVideo() 
                } catch (e: Exception) {}
            }

            var aReady = false
            try { 
                aReady = rtmpCamera.prepareAudio() 
            } catch (e: Exception) { }

            if (vReady && aReady) {
                rtmpCamera.startPreview()
            } else {
                runOnUiThread { 
                    Toast.makeText(this, "CAMERA ERROR: This device/camera is not supported.", Toast.LENGTH_LONG).show() 
                }
            }
        } 
    }
    
    override fun onConnectionSuccess() {
        runOnUiThread {
            retryCount = 0 // Reset retries on success
            btnGoLive.text = "STOP STREAM"
            btnGoLive.isEnabled = true
            btnGoLive.setBackgroundColor(Color.parseColor("#E53935"))
            Toast.makeText(this@MainActivity, "🔥 YOU ARE LIVE! (YouTube will Auto-Start in ~10s)", Toast.LENGTH_LONG).show()
        }
    }

    // 🌟 AUTO-RETRY LOGIC 🌟
    override fun onConnectionFailed(reason: String) {
        if (retryCount < MAX_RETRIES && generatedRtmpUrl != null) {
            retryCount++
            runOnUiThread {
                btnGoLive.text = "RETRYING ($retryCount/3)..."
                Toast.makeText(this@MainActivity, "Network slow, retrying connection...", Toast.LENGTH_SHORT).show()
            }
            
            // 2 सेकंड इंतज़ार करें ताकि नेटवर्क रिफ्रेश हो सके, फिर दोबारा कनेक्ट करें
            Thread {
                Thread.sleep(2000)
                try {
                    rtmpCamera.startStream(generatedRtmpUrl!!)
                } catch (e: Exception) {}
            }.start()
            
        } else {
            runOnUiThread {
                btnGoLive.text = "GO LIVE"
                btnGoLive.isEnabled = true
                try { rtmpCamera.stopStream() } catch (e: Exception) {}
                Toast.makeText(this@MainActivity, "RTMP ERROR: $reason", Toast.LENGTH_LONG).show()
                etControlText.setText("RTMP FAILED: $reason")
            }
        }
    }

    override fun onDisconnect() {
        runOnUiThread {
            btnGoLive.text = "GO LIVE"
            btnGoLive.isEnabled = true
            btnGoLive.setBackgroundColor(Color.parseColor("#D32F2F"))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { super.onRequestPermissionsResult(requestCode, permissions, grantResults); startCameraPreview() }
    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { startCameraPreview() }
    override fun surfaceDestroyed(holder: SurfaceHolder) { if (rtmpCamera.isStreaming) rtmpCamera.stopStream(); if (rtmpCamera.isOnPreview) rtmpCamera.stopPreview() }
    override fun onAuthError() {}
    override fun onAuthSuccess() {}
    override fun onConnectionStarted(url: String) {}
    override fun onNewBitrate(bitrate: Long) {}
}
