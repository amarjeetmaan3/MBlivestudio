package com.mblivestudio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender

import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.ApiException

import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.*
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL

class MainActivity : Activity(), ConnectChecker, SurfaceHolder.Callback {

    private lateinit var rtmpCamera: RtmpCamera2
    private lateinit var openGlView: OpenGlView
    private lateinit var overlayContainer: RelativeLayout
    private lateinit var imageFilterRender: ImageObjectFilterRender
    
    private val cameraLayoutFilter = com.mblivestudio.filters.CameraLayoutFilterRender()

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
    private lateinit var btnTextColors: Button

    private lateinit var btnGoLive: Button
    private lateinit var btnSwitchCamera: Button
    private lateinit var btnMicToggle: Button
    private lateinit var btnRatio169: Button
    private lateinit var btnRatio916: Button

    private lateinit var ivProfilePhoto: ImageView
    private lateinit var tvChannelName: TextView
    private lateinit var tvLiveTimer: TextView

    private lateinit var commentsPanel: LinearLayout
    private lateinit var tvCommentsFeed: TextView
    private lateinit var commentsScrollView: ScrollView
    private lateinit var btnToggleComments: Button

    private var selectedOverlay: View? = null
    private var isAudioMuted = false

    private val PICK_IMAGE_REQUEST = 101
    private val SIGN_IN_REQUEST = 102
    private val REQUEST_AUTHORIZATION = 1001
    private val PICK_THUMBNAIL_REQUEST = 103

    private lateinit var googleSignInClient: GoogleSignInClient
    private var connectedAccountEmail: String? = null

    private var retryCount = 0
    private val MAX_RETRIES = 3
    private var generatedRtmpUrl: String? = null

    private val overlayHandler = Handler(Looper.getMainLooper())
    private var pendingRefresh = false

    private var lastOverlayBitmap: Bitmap? = null
    private var surfaceReady = false

    private var streamWidth = 1280
    private var streamHeight = 720
    private var streamBitrate = 2_000_000 // was 3_500_000 — lowered for battery life

    // Feature 9: tracks current zoom factor (1x = no zoom) so the slider
    // can compute an incremental delta, same as a real pinch would.
    private var lastAppliedZoomFactor = 1f

    private var pendingTitle: String = ""
    private var pendingDesc: String = ""
    private var pendingPrivacy: String = "unlisted"
    private var pendingThumbnailUri: android.net.Uri? = null
    private var thumbnailPreviewImageView: ImageView? = null

    private var youtubeClient: YouTube? = null
    private var currentLiveChatId: String? = null
    private var chatNextPageToken: String? = null
    private var chatPollingActive = false
    private val chatHandler = Handler(Looper.getMainLooper())

    private var liveStartTimeMillis: Long = 0L
    private var timerRunning = false
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!timerRunning) return
            val elapsed = System.currentTimeMillis() - liveStartTimeMillis
            val hours = elapsed / 3_600_000
            val minutes = (elapsed / 60_000) % 60
            val seconds = (elapsed / 1000) % 60
            tvLiveTimer.text = String.format("%02d:%02d:%02d", hours, minutes, seconds)
            timerHandler.postDelayed(this, 1000)
        }
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase)
        System.setProperty("java.net.preferIPv4Stack", "true")
        System.setProperty("java.net.preferIPv6Addresses", "false")
    }

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
        btnTextColors = findViewById(R.id.btnTextColors)

        btnGoLive = findViewById(R.id.btnGoLive)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        btnMicToggle = findViewById(R.id.btnMicToggle)
        btnRatio169 = findViewById(R.id.btnRatio169)
        btnRatio916 = findViewById(R.id.btnRatio916)
        val btnLayoutFull: Button = findViewById(R.id.btnLayoutFull)
        val btnLayoutSplit: Button = findViewById(R.id.btnLayoutSplit)
        val btnLayoutSplitRight: Button = findViewById(R.id.btnLayoutSplitRight)
        val btnLayoutSplitTop: Button = findViewById(R.id.btnLayoutSplitTop)
        val btnLayoutSplitBottom: Button = findViewById(R.id.btnLayoutSplitBottom)
        val btnLayoutCornerTL: Button = findViewById(R.id.btnLayoutCornerTL)
        val btnLayoutCornerTR: Button = findViewById(R.id.btnLayoutCornerTR)
        val btnLayoutCornerBL: Button = findViewById(R.id.btnLayoutCornerBL)
        val btnLayoutCornerBR: Button = findViewById(R.id.btnLayoutCornerBR)

        ivProfilePhoto = findViewById(R.id.ivProfilePhoto)
        tvChannelName = findViewById(R.id.tvChannelName)
        tvLiveTimer = findViewById(R.id.tvLiveTimer)

        commentsPanel = findViewById(R.id.commentsPanel)
        tvCommentsFeed = findViewById(R.id.tvCommentsFeed)
        commentsScrollView = findViewById(R.id.commentsScrollView)
        btnToggleComments = findViewById(R.id.btnToggleComments)

        rtmpCamera = RtmpCamera2(openGlView, this)
        openGlView.holder.addCallback(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !hasCameraPermissions()) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
        }

        imageFilterRender = ImageObjectFilterRender()
        overlayContainer.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        webOverlay.setBackgroundColor(Color.TRANSPARENT)
        webOverlay.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        webOverlay.settings.javaScriptEnabled = true
        webOverlay.settings.domStorageEnabled = true
        webOverlay.webViewClient = WebViewClient()
        webOverlay.webChromeClient = WebChromeClient()

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .requestScopes(Scope("https://www.googleapis.com/auth/youtube"))
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null) {
            connectedAccountEmail = account.email
            applyAccountToHeader(account)
        }

        val accountRowClick = View.OnClickListener {
            if (GoogleSignIn.getLastSignedInAccount(this) == null) {
                startActivityForResult(googleSignInClient.signInIntent, SIGN_IN_REQUEST)
            }
        }
        ivProfilePhoto.setOnClickListener(accountRowClick)
        tvChannelName.setOnClickListener(accountRowClick)

        btnGoLive.setOnClickListener {
            if (rtmpCamera.isStreaming) {
                // Feature 11: confirm before ending the live stream — no
                // accidental taps should end a match broadcast mid-way.
                AlertDialog.Builder(this)
                    .setTitle("Stop Live Stream?")
                    .setMessage("This will end your YouTube broadcast. Are you sure?")
                    .setPositiveButton("Stop Stream") { _, _ -> stopLiveStream() }
                    .setNegativeButton("Cancel", null)
                    .show()
                return@setOnClickListener
            }

            if (!rtmpCamera.isOnPreview) {
                tryStartCameraPreview()
                Toast.makeText(this, "Camera starting, try LIVE again in a moment.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val currentAccount = GoogleSignIn.getLastSignedInAccount(this)
            if (currentAccount == null) {
                Toast.makeText(this@MainActivity, "Please Sign In with YouTube first!", Toast.LENGTH_SHORT).show()
                startActivityForResult(googleSignInClient.signInIntent, SIGN_IN_REQUEST)
                return@setOnClickListener
            }

            val youtubeScope = Scope("https://www.googleapis.com/auth/youtube")
            if (!GoogleSignIn.hasPermissions(currentAccount, youtubeScope)) {
                GoogleSignIn.requestPermissions(this, REQUEST_AUTHORIZATION, currentAccount, youtubeScope)
                return@setOnClickListener
            }

            showGoLiveDialog()
        }

        btnRatio169.setOnClickListener { applyAspectRatio(1280, 720, 2_000_000) }
        btnRatio916.setOnClickListener { applyAspectRatio(720, 1280, 2_000_000) }
        
        btnLayoutFull.setOnClickListener { applyCameraLayout(com.mblivestudio.filters.CameraLayoutFilterRender.FULL) }
        btnLayoutSplit.setOnClickListener { applyCameraLayout(com.mblivestudio.filters.CameraLayoutFilterRender.SPLIT_LEFT) }
        btnLayoutSplitRight.setOnClickListener { applyCameraLayout(com.mblivestudio.filters.CameraLayoutFilterRender.SPLIT_RIGHT) }
        btnLayoutSplitTop.setOnClickListener { applyCameraLayout(com.mblivestudio.filters.CameraLayoutFilterRender.SPLIT_TOP) }
        btnLayoutSplitBottom.setOnClickListener { applyCameraLayout(com.mblivestudio.filters.CameraLayoutFilterRender.SPLIT_BOTTOM) }
        btnLayoutCornerTL.setOnClickListener { applyCameraLayout(com.mblivestudio.filters.CameraLayoutFilterRender.CORNER_TOP_LEFT) }
        btnLayoutCornerTR.setOnClickListener { applyCameraLayout(com.mblivestudio.filters.CameraLayoutFilterRender.CORNER_TOP_RIGHT) }
        btnLayoutCornerBL.setOnClickListener { applyCameraLayout(com.mblivestudio.filters.CameraLayoutFilterRender.CORNER_BOTTOM_LEFT) }
        btnLayoutCornerBR.setOnClickListener { applyCameraLayout(com.mblivestudio.filters.CameraLayoutFilterRender.CORNER_BOTTOM_RIGHT) }

        btnSwitchCamera.setOnClickListener {
            try {
                rtmpCamera.switchCamera()
                if (!rtmpCamera.isStreaming) {
                    rtmpCamera.stopPreview()
                    tryStartCameraPreview()
                }
            } catch (e: Exception) {}
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

        val scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean { return true }
        })
        openGlView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (event.pointerCount >= 2) {
                try {
                    rtmpCamera.setZoom(event, scaleGestureDetector.scaleFactor)
                    lastAppliedZoomFactor = (lastAppliedZoomFactor * scaleGestureDetector.scaleFactor).coerceIn(1f, 5f)
                } catch (e: Exception) {}
            }
            true
        }

        // Feature 9: zoom slider — reuses the exact same confirmed
        // rtmpCamera.setZoom(event, delta) call as pinch, just fed by
        // slider position instead of a real touch. A synthetic 2-pointer
        // MotionEvent is built because that's the only zoom entry point
        // this library version exposes.
        val seekZoom: SeekBar = findViewById(R.id.seekZoom)
        seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val targetZoomFactor = 1f + (progress / 100f) * 4f // 1x .. 5x
                val delta = targetZoomFactor / lastAppliedZoomFactor
                lastAppliedZoomFactor = targetZoomFactor

                val now = android.os.SystemClock.uptimeMillis()
                val props = arrayOf(MotionEvent.PointerProperties(), MotionEvent.PointerProperties())
                props[0].id = 0; props[1].id = 1
                val coords = arrayOf(MotionEvent.PointerCoords(), MotionEvent.PointerCoords())
                coords[0].x = 0f; coords[0].y = 0f
                coords[1].x = 100f; coords[1].y = 100f
                val fakeEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
                try { rtmpCamera.setZoom(fakeEvent, delta) } catch (e: Exception) {}
                fakeEvent.recycle()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        etControlText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (selectedOverlay is TextView && selectedOverlay != scoreMainText && selectedOverlay != scoreSubText) {
                    (selectedOverlay as TextView).text = s.toString()
                    updateSnapshot()
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        etScoreMain.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { scoreMainText.text = s.toString(); updateSnapshot() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, before: Int, count: Int, after: Int) {}
        })

        etScoreSub.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { scoreSubText.text = s.toString(); updateSnapshot() }
            override fun beforeTextChanged(s: CharSequence?, before: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, before: Int, count: Int, after: Int) {}
        })

        btnAddText.setOnClickListener { val text = etControlText.text.toString().trim(); if (text.isNotEmpty()) { addTextOverlayToScreen(text); etControlText.text.clear() } }
        btnAddLogo.setOnClickListener { val intent = Intent(Intent.ACTION_GET_CONTENT); intent.type = "image/*"; startActivityForResult(intent, PICK_IMAGE_REQUEST) }
        btnRemoveSelected.setOnClickListener { selectedOverlay?.let { if (it != dragScoreboard) { overlayContainer.removeView(it); selectedOverlay = null; updateSnapshot() } } }
        btnToggleScore.setOnClickListener { val isVis = dragScoreboard.visibility == View.VISIBLE; dragScoreboard.visibility = if (isVis) View.GONE else View.VISIBLE; btnToggleScore.text = if (isVis) "SHOW SCORECARD ON SCREEN" else "HIDE SCORECARD"; updateSnapshot() }

        btnTextColors.setOnClickListener { showTextColorDialog() }

        makeDraggableAndScalable(dragScoreboard)

        // Feature 13: comments panel is studio-only (not burned into the
        // stream, see XML), so it's safe to reuse the same drag logic —
        // no snapshot/updateSnapshot() call needed since it never
        // touches overlayContainer.
        makeStudioPanelDraggable(commentsPanel)

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
            overlayHandler.postDelayed({ updateSnapshot() }, 2000)
        }

        btnToggleComments.setOnClickListener {
            val isVis = commentsPanel.visibility == View.VISIBLE
            commentsPanel.visibility = if (isVis) View.GONE else View.VISIBLE
            btnToggleComments.text = if (isVis) "SHOW LIVE CHAT (STUDIO ONLY)" else "HIDE LIVE CHAT"
        }
    }

    private fun applyAccountToHeader(account: com.google.android.gms.auth.api.signin.GoogleSignInAccount) {
        tvChannelName.text = account.displayName ?: account.email ?: "Signed in"
        val photoUrl = account.photoUrl
        if (photoUrl != null) {
            Thread {
                try {
                    val input = URL(photoUrl.toString()).openStream()
                    val bmp = BitmapFactory.decodeStream(input)
                    input.close()
                    val circular = cropToCircle(bmp)
                    runOnUiThread { ivProfilePhoto.setImageBitmap(circular) }
                } catch (e: Exception) { e.printStackTrace() }
            }.start()
        }
    }

    private fun cropToCircle(bitmap: Bitmap): Bitmap {
        val size = minOf(bitmap.width, bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        paint.shader = shader
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return output
    }

    private fun showGoLiveDialog() {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val etTitle = EditText(this).apply {
            hint = "Broadcast Title"
            setText(pendingTitle)
        }
        val etDesc = EditText(this).apply {
            hint = "Description"
            setText(pendingDesc)
        }
        val privacyOptions = arrayOf("Public", "Unlisted", "Private")
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, privacyOptions)
            setSelection(privacyOptions.indexOfFirst { it.equals(pendingPrivacy, ignoreCase = true) }.coerceAtLeast(1))
        }

        val thumbBoxWidth = (140 * resources.displayMetrics.density).toInt()
        val thumbBoxHeight = (90 * resources.displayMetrics.density).toInt()
        val thumbPreview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(thumbBoxWidth, thumbBoxHeight).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#333333"))
            pendingThumbnailUri?.let { setImageURI(it) }
        }
        thumbnailPreviewImageView = thumbPreview

        val thumbWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            addView(thumbPreview)
        }

        val btnThumbnail = Button(this).apply { text = "CHOOSE THUMBNAIL" }
        val btnPreview = Button(this).apply { text = "PREVIEW" }
        val btnLayout = Button(this).apply { text = "ADJUST OVERLAY LAYOUT" }
        val btnConfirmLive = Button(this).apply {
            text = "GO LIVE"
            setBackgroundColor(Color.parseColor("#D32F2F"))
            setTextColor(Color.WHITE)
        }

        listOf(etTitle, etDesc, spinner, thumbWrapper, btnThumbnail, btnPreview, btnLayout, btnConfirmLive).forEach {
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = (8 * resources.displayMetrics.density).toInt()
            it.layoutParams = lp
            container.addView(it)
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(container)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Go Live Setup")
            .setView(scrollView)
            .setNegativeButton("Cancel", null)
            .create()

        btnThumbnail.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT); intent.type = "image/*"
            startActivityForResult(intent, PICK_THUMBNAIL_REQUEST)
        }

        btnPreview.setOnClickListener {
            val t = etTitle.text.toString().trim().ifEmpty { "Live from M.B. Live Studio" }
            Toast.makeText(this, "Title: $t\nPrivacy: ${spinner.selectedItem}\nThumbnail: ${if (pendingThumbnailUri != null) "Selected" else "None"}", Toast.LENGTH_LONG).show()
        }

        btnLayout.setOnClickListener {
            pendingTitle = etTitle.text.toString()
            pendingDesc = etDesc.text.toString()
            pendingPrivacy = spinner.selectedItem.toString().lowercase()
            dialog.dismiss()
            Toast.makeText(this, "Adjust your overlay layout, then tap LIVE again.", Toast.LENGTH_LONG).show()
        }

        btnConfirmLive.setOnClickListener {
            pendingTitle = etTitle.text.toString()
            pendingDesc = etDesc.text.toString()
            pendingPrivacy = spinner.selectedItem.toString().lowercase()
            dialog.dismiss()
            retryCount = 0
            createYouTubeBroadcast()
        }

        dialog.show()
    }

    private fun applyAspectRatio(width: Int, height: Int, bitrate: Int) {
        if (rtmpCamera.isStreaming) {
            Toast.makeText(this, "Stop the stream before changing aspect ratio.", Toast.LENGTH_SHORT).show()
            return
        }
        streamWidth = width
        streamHeight = height
        streamBitrate = bitrate

        btnRatio169.setBackgroundColor(Color.parseColor(if (width > height) "#4CAF50" else "#333333"))
        btnRatio916.setBackgroundColor(Color.parseColor(if (height > width) "#4CAF50" else "#333333"))

        if (rtmpCamera.isOnPreview) {
            try { rtmpCamera.stopPreview() } catch (e: Exception) {}
            tryStartCameraPreview()
        }
    }

    private fun showTextColorDialog() {
        val target = selectedOverlay
        if (target !is TextView) {
            Toast.makeText(this, "Select a text overlay first.", Toast.LENGTH_SHORT).show()
            return
        }

        val presetColors = listOf(
            "#FFFFFF", "#FFEB3B", "#FF5252", "#4CAF50", "#2196F3", "#FF9800", "#000000"
        )

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }

        container.addView(TextView(this).apply { text = "Font Color"; setTextColor(Color.WHITE) })
        container.addView(colorSwatchRow(presetColors) { color ->
            target.setTextColor(Color.parseColor(color))
            updateSnapshot()
        })

        container.addView(TextView(this).apply { text = "Background Color"; setTextColor(Color.WHITE); setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0) })
        container.addView(colorSwatchRow(presetColors) { color ->
            target.setBackgroundColor(Color.parseColor(color))
            updateSnapshot()
        })

        AlertDialog.Builder(this)
            .setTitle("Text Colors")
            .setView(container)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun colorSwatchRow(colors: List<String>, onPick: (String) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val swatchSize = (32 * resources.displayMetrics.density).toInt()
        colors.forEach { colorHex ->
            val swatch = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(swatchSize, swatchSize).apply {
                    marginEnd = (8 * resources.displayMetrics.density).toInt()
                }
                setBackgroundColor(Color.parseColor(colorHex))
                setOnClickListener { onPick(colorHex) }
            }
            row.addView(swatch)
        }
        return row
    }

    private fun startChatPolling(liveChatId: String) {
        currentLiveChatId = liveChatId
        chatNextPageToken = null
        chatPollingActive = true
        pollChatOnce()
    }

    private fun stopChatPolling() {
        chatPollingActive = false
        chatHandler.removeCallbacksAndMessages(null)
        currentLiveChatId = null
    }

    private fun pollChatOnce() {
        if (!chatPollingActive) return
        val chatId = currentLiveChatId ?: return
        val youtube = youtubeClient ?: return

        Thread {
            try {
                val request = youtube.liveChatMessages().list(chatId, "snippet,authorDetails")
                chatNextPageToken?.let { request.pageToken = it }
                val response = request.execute()
                chatNextPageToken = response.nextPageToken

                val newLines = response.items.orEmpty().mapNotNull { msg ->
                    val author = msg.authorDetails?.displayName ?: "Viewer"
                    val text = msg.snippet?.displayMessage ?: return@mapNotNull null
                    "$author: $text"
                }

                if (newLines.isNotEmpty()) {
                    runOnUiThread {
                        val existing = tvCommentsFeed.text.toString()
                        val combined = (existing.lines() + newLines).takeLast(30).joinToString("\n")
                        tvCommentsFeed.text = combined
                        commentsScrollView.post { commentsScrollView.fullScroll(View.FOCUS_DOWN) }
                    }
                }

                val delay = (response.pollingIntervalMillis ?: 10000L).coerceAtLeast(8000L)
                if (chatPollingActive) chatHandler.postDelayed({ pollChatOnce() }, delay)
            } catch (e: Exception) {
                e.printStackTrace()
                if (chatPollingActive) chatHandler.postDelayed({ pollChatOnce() }, 15000L)
            }
        }.start()
    }

    private fun startStudioTimer() {
        liveStartTimeMillis = System.currentTimeMillis()
        timerRunning = true
        tvLiveTimer.visibility = View.VISIBLE
        timerHandler.post(timerRunnable)
    }

    private fun stopStudioTimer() {
        timerRunning = false
        timerHandler.removeCallbacksAndMessages(null)
        tvLiveTimer.visibility = View.GONE
        tvLiveTimer.text = "00:00:00"
    }

    private fun updateSnapshot() {
        if (!rtmpCamera.isOnPreview || overlayContainer.width == 0 || overlayContainer.height == 0) return

        if (pendingRefresh) return
        pendingRefresh = true

        overlayHandler.postDelayed({
            try {
                val newBitmap = Bitmap.createBitmap(overlayContainer.width, overlayContainer.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(newBitmap)
                overlayContainer.draw(canvas)

                imageFilterRender.setImage(newBitmap)
                imageFilterRender.setScale(100f, 100f)
                imageFilterRender.setPosition(0f, 0f)

                lastOverlayBitmap?.let { old ->
                    if (!old.isRecycled) old.recycle()
                }
                lastOverlayBitmap = newBitmap
            } catch (e: Exception) {
                e.printStackTrace()
            }
            pendingRefresh = false
        }, 200)
    }

    private fun stopLiveStream() {
        btnGoLive.isEnabled = false
        btnGoLive.text = "STOPPING..."

        Thread {
            try { rtmpCamera.stopStream() } catch (e: Exception) {}
            runOnUiThread {
                try { rtmpCamera.stopPreview() } catch (e: Exception) {}
                tryStartCameraPreview()
                btnGoLive.text = "LIVE"
                btnGoLive.isEnabled = true
                btnGoLive.setBackgroundColor(Color.parseColor("#D32F2F"))
                Toast.makeText(this@MainActivity, "Stream Stopped.", Toast.LENGTH_SHORT).show()
                generatedRtmpUrl = null
                stopChatPolling()
                stopStudioTimer()
            }
        }.start()
    }

    private fun createYouTubeBroadcast() {
        btnGoLive.text = "1/4: CONNECTING API..."
        btnGoLive.isEnabled = false

        val finalTitle = pendingTitle.trim().ifEmpty { "Live from M.B. Live Studio" }
        val finalDesc = pendingDesc.trim().ifEmpty { "Streaming via Android App" }
        val privacyInput = pendingPrivacy

        Thread {
            try {
                val credential = GoogleAccountCredential.usingOAuth2(this@MainActivity, listOf("https://www.googleapis.com/auth/youtube"))
                val signInAccount = GoogleSignIn.getLastSignedInAccount(this@MainActivity)
                if (signInAccount?.account != null) credential.selectedAccount = signInAccount.account
                else credential.selectedAccountName = connectedAccountEmail

                val transport = NetHttpTransport()
                val jsonFactory = GsonFactory.getDefaultInstance()

                val youtube = YouTube.Builder(transport, jsonFactory, HttpRequestInitializer { request ->
                    credential.initialize(request)
                    request.connectTimeout = 10000
                    request.readTimeout = 10000
                    request.numberOfRetries = 0
                }).setApplicationName("MBLiveStudio").build()
                youtubeClient = youtube

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

                val liveChatId = broadcast.snippet?.liveChatId

                pendingThumbnailUri?.let { uri ->
                    try {
                        val stream = contentResolver.openInputStream(uri)
                        if (stream != null) {
                            val content = InputStreamContent("image/jpeg", stream)
                            youtube.thumbnails().set(broadcast.id, content).execute()
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }

                runOnUiThread { btnGoLive.text = "3/4: GETTING KEY..." }

                val streamSnippet = LiveStreamSnippet()
                streamSnippet.title = "$finalTitle - Key"

                val cdn = CdnSettings()
                cdn.ingestionType = "rtmp"
                cdn.resolution = "variable"
                cdn.frameRate = "variable"

                var stream2 = LiveStream()
                stream2.snippet = streamSnippet
                stream2.cdn = cdn
                stream2 = youtube.liveStreams().insert("snippet,cdn", stream2).execute()

                val bindRequest = youtube.liveBroadcasts().bind(broadcast.id, "id,contentDetails")
                bindRequest.streamId = stream2.id
                bindRequest.execute()

                var ingestionUrl = stream2.cdn.ingestionInfo.ingestionAddress
                var resolvedIp: String? = null

                try {
                    val host = if (ingestionUrl.contains("b.rtmp")) "b.rtmp.youtube.com" else "a.rtmp.youtube.com"
                    val addresses = InetAddress.getAllByName(host)
                    resolvedIp = addresses.firstOrNull { it is Inet4Address }?.hostAddress
                } catch (e: Exception) { e.printStackTrace() }

                val finalUrl = if (resolvedIp != null && ingestionUrl.contains("a.rtmp.youtube.com")) {
                    ingestionUrl.replace("a.rtmp.youtube.com", resolvedIp) + "/" + stream2.cdn.ingestionInfo.streamName
                } else {
                    ingestionUrl.replace("a.rtmp", "b.rtmp") + "/" + stream2.cdn.ingestionInfo.streamName
                }

                generatedRtmpUrl = finalUrl

                runOnUiThread {
                    btnGoLive.text = "4/4: CONNECTING CAMERA..."
                    try {
                        rtmpCamera.startStream(finalUrl)
                        if (liveChatId != null) startChatPolling(liveChatId)
                    } catch (e: Exception) {
                        btnGoLive.text = "LIVE"
                        btnGoLive.isEnabled = true
                        Toast.makeText(this@MainActivity, "Stream Error: Failed to start.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    btnGoLive.text = "LIVE"
                    btnGoLive.isEnabled = true
                    Toast.makeText(this@MainActivity, "Timeout/API Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_AUTHORIZATION) {
            val account = GoogleSignIn.getLastSignedInAccount(this)
            if (GoogleSignIn.hasPermissions(account, Scope("https://www.googleapis.com/auth/youtube"))) {
                Toast.makeText(this, "Permission Granted! Tap LIVE again.", Toast.LENGTH_LONG).show()
            }
        }
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null) {
            data.data?.let { uri ->
                val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                addImageOverlayToScreen(bitmap)
            }
        }
        if (requestCode == PICK_THUMBNAIL_REQUEST && resultCode == RESULT_OK && data != null) {
            data.data?.let { uri ->
                pendingThumbnailUri = uri
                thumbnailPreviewImageView?.setImageURI(uri)
            }
        }
        if (requestCode == SIGN_IN_REQUEST) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                connectedAccountEmail = account?.email
                if (account != null) applyAccountToHeader(account)
            } catch (e: ApiException) { }
        }
    }

    private fun addTextOverlayToScreen(text: String) {
        val textView = TextView(this).apply {
            this.text = text; setTextColor(Color.YELLOW); textSize = 30f; setTypeface(null, Typeface.BOLD)
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply { addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE) }
        }
        overlayContainer.addView(textView)
        makeDraggableAndScalable(textView); selectedOverlay = textView
        updateSnapshot()
    }

    private fun addImageOverlayToScreen(bitmap: Bitmap) {
        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap); layoutParams = RelativeLayout.LayoutParams(300, 300).apply { addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE) }
        }
        overlayContainer.addView(imageView)
        makeDraggableAndScalable(imageView); selectedOverlay = imageView
        updateSnapshot()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggableAndScalable(view: View) {
        val scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean { view.scaleX *= detector.scaleFactor; view.scaleY *= detector.scaleFactor; return true }
        })
        var dX = 0f; var dY = 0f
        view.setOnTouchListener { v, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (!scaleGestureDetector.isInProgress) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dX = v.x - event.rawX; dY = v.y - event.rawY; selectedOverlay = v; if (v is TextView && v != dragScoreboard) { etControlText.setText(v.text) } }
                    MotionEvent.ACTION_MOVE -> { v.x = event.rawX + dX; v.y = event.rawY + dY }
                    MotionEvent.ACTION_UP -> { updateSnapshot() }
                }
            }
            true
        }
    }

    // Feature 13: plain drag, no scale, no selectedOverlay/updateSnapshot
    // — used only for studio-only panels (comments) that sit outside
    // overlayContainer and never touch the broadcast pipeline.
    @SuppressLint("ClickableViewAccessibility")
    private fun makeStudioPanelDraggable(view: View) {
        var dX = 0f; var dY = 0f
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dX = v.x - event.rawX; dY = v.y - event.rawY }
                MotionEvent.ACTION_MOVE -> { v.x = event.rawX + dX; v.y = event.rawY + dY }
            }
            true
        }
    }

    private fun hasCameraPermissions(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun tryStartCameraPreview() {
        if (surfaceReady && hasCameraPermissions()) {
            startCameraPreview()
        }
    }

    private fun startCameraPreview() {
        if (!hasCameraPermissions()) return

        if (!rtmpCamera.isOnPreview) {
            var isSuccess = false
            val fallback = if (streamWidth >= streamHeight) Triple(854, 480, 1_500_000) else Triple(480, 854, 1_500_000)
            val resolutions = listOf(
                Triple(streamWidth, streamHeight, streamBitrate),
                fallback,
                Triple(640, 480, 1_000_000)
            )

            for (res in resolutions) {
                try {
                    if (rtmpCamera.prepareVideo(res.first, res.second, 30, res.third, 2, 0)) {
                        isSuccess = true
                        break
                    }
                } catch (e: Exception) {}
            }

            if (!isSuccess) {
                try {
                    isSuccess = rtmpCamera.prepareVideo()
                } catch (e: Exception) {}
            }

            var aReady = false
            try { aReady = rtmpCamera.prepareAudio() } catch (e: Exception) { }

            if (isSuccess && aReady) {
                rtmpCamera.glInterface.setFilter(cameraLayoutFilter)
                rtmpCamera.glInterface.addFilter(imageFilterRender)
                rtmpCamera.startPreview()
                overlayHandler.postDelayed({ updateSnapshot() }, 1000)
            } else {
                runOnUiThread { Toast.makeText(this, "CAMERA ERROR: Device encoder not supported.", Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onConnectionSuccess() {
        runOnUiThread {
            retryCount = 0
            btnGoLive.text = "STOP STREAM"
            btnGoLive.isEnabled = true
            btnGoLive.setBackgroundColor(Color.parseColor("#E53935"))
            Toast.makeText(this@MainActivity, "🔥 YOU ARE LIVE!", Toast.LENGTH_LONG).show()
            startStudioTimer()
        }
    }

    override fun onConnectionFailed(reason: String) {
        if (retryCount < MAX_RETRIES && generatedRtmpUrl != null) {
            retryCount++
            runOnUiThread {
                btnGoLive.text = "RETRYING ($retryCount/3)..."
            }
            Thread {
                Thread.sleep(2000)
                try { rtmpCamera.startStream(generatedRtmpUrl!!) } catch (e: Exception) {}
            }.start()
        } else {
            runOnUiThread {
                try { rtmpCamera.stopPreview() } catch (e: Exception) {}
                tryStartCameraPreview()

                btnGoLive.text = "LIVE"
                btnGoLive.isEnabled = true
                try { rtmpCamera.stopStream() } catch (e: Exception) {}
                Toast.makeText(this@MainActivity, "RTMP TIMEOUT: $reason", Toast.LENGTH_LONG).show()
                stopChatPolling()
                stopStudioTimer()
            }
        }
    }

    override fun onDisconnect() {
        runOnUiThread {
            btnGoLive.text = "LIVE"
            btnGoLive.isEnabled = true
            btnGoLive.setBackgroundColor(Color.parseColor("#D32F2F"))
            try { rtmpCamera.stopPreview() } catch (e: Exception) {}
            tryStartCameraPreview()
            stopChatPolling()
            stopStudioTimer()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        tryStartCameraPreview()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceReady = true
        tryStartCameraPreview()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        if (rtmpCamera.isStreaming) rtmpCamera.stopStream()
        if (rtmpCamera.isOnPreview) rtmpCamera.stopPreview()
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayHandler.removeCallbacksAndMessages(null)
        chatHandler.removeCallbacksAndMessages(null)
        timerHandler.removeCallbacksAndMessages(null)
        try { if (rtmpCamera.isStreaming) rtmpCamera.stopStream() } catch (e: Exception) {}
        try { if (rtmpCamera.isOnPreview) rtmpCamera.stopPreview() } catch (e: Exception) {}
        lastOverlayBitmap?.let { if (!it.isRecycled) it.recycle() }
        lastOverlayBitmap = null
    }

    override fun onAuthError() {}
    override fun onAuthSuccess() {}
    override fun onConnectionStarted(url: String) {}
    override fun onNewBitrate(bitrate: Long) {}
    
    private fun applyCameraLayout(rect: FloatArray) {
        cameraLayoutFilter.setRect(rect[0], rect[1], rect[2], rect[3])
        cameraLayoutFilter.setBackgroundColor(0.07f, 0.07f, 0.07f)
    }
}
