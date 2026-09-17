package com.mblivestudio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.mblivestudio.filters.CameraLayoutFilterRender
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.library.rtmp.RtmpCamera2
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.ApiException
import com.google.api.services.youtube.YouTube
import com.pedro.library.view.OpenGlView

internal enum class MicRoute { PHONE, BLUETOOTH, WIRED }

class MainActivity : Activity(), ConnectChecker, SurfaceHolder.Callback {

    // GLOBAL MEMORY: यह कभी डिलीट नहीं होगा, चाहे ऐप स्विच हो या घूमे
    companion object {
        @SuppressLint("StaticFieldLeak")
        var activeRtmpCamera: RtmpCamera2? = null
        var globalPrivacyMode = false
        var globalSlateBitmap: Bitmap? = null
        var globalThumbnailUri: android.net.Uri? = null
    }

    // BACKWARD COMPATIBILITY: Dialogs.kt और YouTubeApi.kt के लिए लिंकर
    internal var pendingThumbnailUri: android.net.Uri?
        get() = globalThumbnailUri
        set(value) { globalThumbnailUri = value }

    internal lateinit var rtmpCamera: RtmpCamera2
    internal lateinit var openGlView: OpenGlView
    internal lateinit var overlayContainer: RelativeLayout
    internal lateinit var imageFilterRender: ImageObjectFilterRender
    internal val cameraLayoutFilter = CameraLayoutFilterRender()

    internal lateinit var ivStreamSlate: ImageView 
    internal var isBackgrounded = false

    internal lateinit var dragScoreboard: LinearLayout
    internal lateinit var scoreMainText: TextView
    internal lateinit var scoreSubText: TextView

    internal lateinit var btnGoLive: Button
    internal lateinit var btnStreamsManager: Button
    internal lateinit var ivProfilePhoto: ImageView
    internal lateinit var tvLiveTimer: TextView
    internal lateinit var tvViewerCount: TextView
    internal lateinit var tvApiQuota: TextView

    internal lateinit var commentsPanel: LinearLayout
    internal lateinit var tvCommentsFeed: TextView
    internal lateinit var commentsScrollView: ScrollView
    internal lateinit var tvStreamChatOverlay: TextView
    
    internal lateinit var switchChatSync: Switch
    internal lateinit var switchViewerSync: Switch
    internal lateinit var switchShowQuota: Switch
    internal lateinit var switchShowViewers: Switch

    internal lateinit var btnOverlayMenu: ImageButton
    internal lateinit var btnOverlayDone: Button
    internal var currentMode = "DRAG"
    internal val resizeHandles = mutableListOf<View>()
    internal val cropFrameViews = mutableListOf<View>()

    internal var selectedOverlay: View? = null
    
    internal var isAudioMuted = false
    internal var isBluetoothMicActive = false
    internal lateinit var audioManager: AudioManager

    internal var detectedMicRoute = MicRoute.PHONE
    internal var bluetoothCommunicationDevice: AudioDeviceInfo? = null
    internal var audioDeviceCallback: AudioDeviceCallback? = null
    internal var scoStateReceiver: android.content.BroadcastReceiver? = null
    internal val scoConnectTimeoutHandler = Handler(Looper.getMainLooper())

    internal val PICK_IMAGE_REQUEST = 101
    internal val SIGN_IN_REQUEST = 102
    internal val REQUEST_AUTHORIZATION = 1001
    internal val PICK_THUMBNAIL_REQUEST = 103

    internal lateinit var googleSignInClient: GoogleSignInClient
    internal var connectedAccountEmail: String? = null

    internal var retryCount = 0
    internal val MAX_RETRIES = 3
    internal var generatedRtmpUrl: String? = null

    internal val overlayHandler = Handler(Looper.getMainLooper())
    internal var pendingRefresh = false
    internal var refreshQueued = false
    internal var surfaceReady = false

    internal var bitmapA: Bitmap? = null
    internal var canvasA: Canvas? = null
    internal var bitmapB: Bitmap? = null
    internal var canvasB: Canvas? = null
    internal var useBufferA = true

    internal var streamWidth = 1280
    internal var streamHeight = 720
    internal var streamBitrate = 3_000_000
    internal var streamFps = 30 

    internal var pendingTitle: String = ""
    internal var pendingDesc: String = ""
    internal var pendingPrivacy: String = "unlisted"
    internal var pendingScheduleTimeMs: Long = 0L
    internal var thumbnailPreviewImageView: ImageView? = null

    internal var youtubeClient: YouTube? = null
    internal var currentLiveChatId: String? = null
    internal var currentBroadcastId: String? = null
    internal var currentStreamId: String? = null
    
    internal var chatNextPageToken: String? = null
    internal var chatPollingActive = false
    internal val chatHandler = Handler(Looper.getMainLooper())
    
    internal val streamChatHistory = mutableListOf<String>()

    internal var dailyQuotaUsed = 0
    internal var currentZoomDistance = 100f

    internal var liveStartTimeMillis: Long = 0L
    internal var timerRunning = false
    internal val timerHandler = Handler(Looper.getMainLooper())
    internal val timerRunnable = object : Runnable {
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

    internal val tickerHandler = Handler(Looper.getMainLooper())
    internal val tickerRunnable = object : Runnable {
        override fun run() {
            this@MainActivity.updateSnapshot(50) 
            tickerHandler.postDelayed(this, 150)
        }
    }

    internal val webSyncHandler = Handler(Looper.getMainLooper())
    internal val webSyncRunnable = object : Runnable {
        override fun run() {
            this@MainActivity.updateSnapshot(100)
            webSyncHandler.postDelayed(this, 1000)
        }
    }

    internal fun showSlate() {
        if (globalThumbnailUri != null) ivStreamSlate.setImageURI(globalThumbnailUri)
        else ivStreamSlate.setBackgroundColor(Color.parseColor("#121212"))
        ivStreamSlate.visibility = View.VISIBLE
        updateSnapshot(0)
    }

    internal fun hideSlate() {
        ivStreamSlate.visibility = View.GONE
        updateSnapshot(0)
    }

    internal fun lockOrientation() {
        val isLandscape = streamWidth > streamHeight
        requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    internal fun unlockOrientation() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
    }

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase)
        System.setProperty("java.net.preferIPv4Stack", "true")
        System.setProperty("java.net.preferIPv6Addresses", "false")
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        updateSnapshot(0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER

        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val maxDim = maxOf(streamWidth, streamHeight)
        val minDim = minOf(streamWidth, streamHeight)
        if (isLandscape) { streamWidth = maxDim; streamHeight = minDim } else { streamWidth = minDim; streamHeight = maxDim }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { window.setDecorFitsSystemWindows(false) }
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        setContentView(R.layout.activity_main)

        openGlView = findViewById(R.id.surfaceView)
        openGlView.setAspectRatioMode(AspectRatioMode.Fill)
        openGlView.layoutParams = openGlView.layoutParams.apply { width = ViewGroup.LayoutParams.MATCH_PARENT; height = ViewGroup.LayoutParams.MATCH_PARENT }
        openGlView.holder.addCallback(this)
        
        if (activeRtmpCamera == null) {
            rtmpCamera = RtmpCamera2(openGlView, this)
            activeRtmpCamera = rtmpCamera
        } else {
            rtmpCamera = activeRtmpCamera!!
            try { rtmpCamera.replaceView(openGlView) } catch(e: Exception) {}
        }
        
        imageFilterRender = ImageObjectFilterRender()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        registerAudioDeviceMonitoring()

        overlayContainer = findViewById(R.id.overlayContainer)
        
        ivStreamSlate = ImageView(this).apply {
            layoutParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#121212"))
            visibility = View.GONE
        }
        overlayContainer.addView(ivStreamSlate, 0)

        dragScoreboard = findViewById(R.id.dragScoreboard)
        scoreMainText = findViewById(R.id.scoreMainText)
        scoreSubText = findViewById(R.id.scoreSubText)

        btnGoLive = findViewById(R.id.btnGoLive)
        btnStreamsManager = findViewById(R.id.btnStreamsManager)
        ivProfilePhoto = findViewById(R.id.ivProfilePhoto)
        tvLiveTimer = findViewById(R.id.tvLiveTimer)
        tvViewerCount = findViewById(R.id.tvViewerCount)
        tvApiQuota = findViewById(R.id.tvApiQuota)
        
        loadQuota()

        commentsPanel = findViewById(R.id.commentsPanel)
        tvCommentsFeed = findViewById(R.id.tvCommentsFeed)
        commentsScrollView = findViewById(R.id.commentsScrollView)
        tvStreamChatOverlay = findViewById(R.id.tvStreamChatOverlay)
        tvStreamChatOverlay.setOnTouchListener(null)
        makeDraggableAndScalable(tvStreamChatOverlay)
        
        val tvChatDragHandle: TextView = findViewById(R.id.tvChatDragHandle)
        var cDx = 0f; var cDy = 0f
        tvChatDragHandle.setOnTouchListener { _, event -> 
            when (event.actionMasked) { 
                MotionEvent.ACTION_DOWN -> { cDx = commentsPanel.x - event.rawX; cDy = commentsPanel.y - event.rawY }
                MotionEvent.ACTION_MOVE -> { commentsPanel.x = event.rawX + cDx; commentsPanel.y = event.rawY + cDy } 
            }
            true 
        }

        val popupSettings: LinearLayout = findViewById(R.id.popupSettings)
        val btnSettings: ImageButton = findViewById(R.id.btnSettings)
        val btnCloseSettings: Button = findViewById(R.id.btnCloseSettings)
        
        btnSettings.setOnClickListener { popupSettings.visibility = View.VISIBLE }
        btnCloseSettings.setOnClickListener { popupSettings.visibility = View.GONE }

        switchChatSync = findViewById(R.id.switchChatSync)
        switchViewerSync = findViewById(R.id.switchViewerSync)
        switchShowQuota = findViewById(R.id.switchShowQuota)
        switchShowViewers = findViewById(R.id.switchShowViewers)

        switchShowQuota.setOnCheckedChangeListener { _, isChecked -> tvApiQuota.visibility = if (isChecked) View.VISIBLE else View.GONE }
        switchShowViewers.setOnCheckedChangeListener { _, isChecked -> tvViewerCount.visibility = if (chatPollingActive && isChecked) View.VISIBLE else View.GONE }

        btnOverlayMenu = findViewById(R.id.btnOverlayMenu)
        btnOverlayDone = findViewById(R.id.btnOverlayDone)

        btnOverlayMenu.setOnClickListener {
            val target = selectedOverlay ?: return@setOnClickListener
            val popup = PopupMenu(this, btnOverlayMenu)
            popup.menu.add("Resize"); if (target !is TextView && target.tag != "LOWER_THIRD") popup.menu.add("Crop")
            if (target is TextView || target is EditText || target.tag == "LOWER_THIRD") popup.menu.add("Change Color")
            popup.setOnMenuItemClickListener { item -> when (item.title) { "Resize" -> enterResizeMode(target); "Crop" -> enterCropMode(target); "Change Color" -> showCustomColorPickerDialog() }; true }
            popup.show()
        }

        btnOverlayDone.setOnClickListener {
            currentMode = "DRAG"; btnOverlayDone.visibility = View.GONE; val root = findViewById<RelativeLayout>(R.id.rootLayout)
            resizeHandles.forEach { root.removeView(it) }; resizeHandles.clear(); cropFrameViews.forEach { root.removeView(it) }; cropFrameViews.clear()
            selectedOverlay?.let { if (it is EditText) it.clearFocus(); it.setOnTouchListener(null); makeDraggableAndScalable(it) }
            updateOverlayMenuButtonPosition(); updateSnapshot()
        }

        val btnSwitchCamera: ImageButton = findViewById(R.id.btnSwitchCamera)
        val btnMicToggle: ImageButton = findViewById(R.id.btnMicToggle)
        val btnBluetoothMic: ImageButton = findViewById(R.id.btnBluetoothMic)
        
        // THE BLIND SLATE / PRIVACY MODE BUTTON
        val btnPrivacyMode: ImageButton = findViewById(R.id.btnOrientation)
        btnPrivacyMode.setImageResource(android.R.drawable.ic_menu_camera)
        if (globalPrivacyMode) { btnPrivacyMode.setColorFilter(Color.RED); showSlate() }
        
        btnPrivacyMode.setOnClickListener {
            globalPrivacyMode = !globalPrivacyMode
            if (globalPrivacyMode) {
                btnPrivacyMode.setColorFilter(Color.RED)
                rtmpCamera.disableAudio()
                showSlate()
            } else {
                btnPrivacyMode.clearColorFilter()
                if (!isAudioMuted) rtmpCamera.enableAudio()
                hideSlate()
            }
            updateSnapshot(0)
        }

        findViewById<Button>(R.id.btnToggleComments).setOnClickListener { popupSettings.visibility = View.GONE; commentsPanel.visibility = if (commentsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        findViewById<Button>(R.id.btnToggleStreamChat).setOnClickListener { popupSettings.visibility = View.GONE; if (tvStreamChatOverlay.visibility == View.VISIBLE) { tvStreamChatOverlay.visibility = View.GONE; updateSnapshot() } else { tvStreamChatOverlay.visibility = View.VISIBLE; tvStreamChatOverlay.bringToFront(); refreshChatOverlayText(); updateSnapshot() } }
        findViewById<Button>(R.id.btnAddText).setOnClickListener { popupSettings.visibility = View.GONE; showAddTextDialog() }
        findViewById<Button>(R.id.btnAddWebOverlay).setOnClickListener { popupSettings.visibility = View.GONE; showAddWebDialog() }
        findViewById<Button>(R.id.btnAddLogo).setOnClickListener { popupSettings.visibility = View.GONE; val intent = Intent(Intent.ACTION_GET_CONTENT); intent.type = "image/*"; startActivityForResult(intent, PICK_IMAGE_REQUEST) }
        findViewById<Button>(R.id.btnToggleScore).setOnClickListener { popupSettings.visibility = View.GONE; if (dragScoreboard.visibility == View.VISIBLE) { dragScoreboard.visibility = View.GONE; updateSnapshot() } else { showScoreboardDialog() } }
        findViewById<Button>(R.id.btnAddLowerThird).setOnClickListener { popupSettings.visibility = View.GONE; showAddLowerThirdDialog() }
        
        findViewById<ImageButton>(R.id.btnLayoutFull).setOnClickListener { applyCameraLayout(floatArrayOf(0f,0f,1f,1f)); popupSettings.visibility = View.GONE }
        findViewById<ImageButton>(R.id.btnLayoutSplit).setOnClickListener { applyCameraLayout(floatArrayOf(0f,0f,0.5f,1f)); popupSettings.visibility = View.GONE }
        findViewById<ImageButton>(R.id.btnLayoutCornerTL).setOnClickListener { applyCameraLayout(floatArrayOf(0f,0f,0.3f,0.3f)); popupSettings.visibility = View.GONE }
        findViewById<ImageButton>(R.id.btnLayoutCornerBR).setOnClickListener { applyCameraLayout(floatArrayOf(0.7f,0.7f,1f,1f)); popupSettings.visibility = View.GONE }

        findViewById<ImageButton>(R.id.btnLiveText).setOnClickListener { popupSettings.visibility = View.GONE; addLiveTextOverlay() }
        
        findViewById<ImageButton>(R.id.btnRemoveSelected).setOnClickListener { 
            popupSettings.visibility = View.GONE
            var target = selectedOverlay
            if (target == null) { val focusView = currentFocus; if (focusView is EditText && focusView.parent == overlayContainer) target = focusView }
            target?.let { 
                if (it != dragScoreboard) { 
                    if (it.tag == "LOWER_THIRD") tickerHandler.removeCallbacks(tickerRunnable)
                    if (it.tag == "WEB_OVERLAY") webSyncHandler.removeCallbacks(webSyncRunnable)
                    if (it is EditText) { it.clearFocus(); (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0) }
                    overlayContainer.removeView(it); if (selectedOverlay == it) selectedOverlay = null
                    updateOverlayMenuButtonPosition(); updateSnapshot() 
                } 
            } 
        }

        btnMicToggle.setImageResource(R.drawable.ic_mic_on)
        btnMicToggle.setOnClickListener {
            if (isAudioMuted) { rtmpCamera.enableAudio(); isAudioMuted = false; btnMicToggle.setImageResource(R.drawable.ic_mic_on) } 
            else { rtmpCamera.disableAudio(); isAudioMuted = true; btnMicToggle.setImageResource(R.drawable.ic_mic_off) }
        }

        btnSwitchCamera.setOnClickListener { try { rtmpCamera.switchCamera() } catch (e: Exception) {} }

        btnBluetoothMic.clearColorFilter()
        btnBluetoothMic.setOnClickListener {
            if (rtmpCamera.isStreaming) { Toast.makeText(this, "Stop the stream before switching mic source.", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2); return@setOnClickListener }
            toggleBluetoothMic(btnBluetoothMic)
        }

        var currentTouchEvent: MotionEvent? = null
        val scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean { currentTouchEvent?.let { event -> try { rtmpCamera.setZoom(event, detector.scaleFactor) } catch (e: Exception) {} }; return true }
        })

        openGlView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) { currentFocus?.clearFocus(); (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(openGlView.windowToken, 0) }
            if (event.pointerCount > 1) { currentTouchEvent = event; scaleGestureDetector.onTouchEvent(event); true } else false
        }
        
        findViewById<Button>(R.id.btnZoomIn).setOnClickListener { currentZoomDistance += 15f; sendSyntheticZoomEvent(MotionEvent.ACTION_MOVE, currentZoomDistance, 1f) }
        findViewById<Button>(R.id.btnZoomOut).setOnClickListener { currentZoomDistance -= 15f; if (currentZoomDistance < 100f) currentZoomDistance = 100f; sendSyntheticZoomEvent(MotionEvent.ACTION_MOVE, currentZoomDistance, 1f) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1) }

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().requestProfile().requestScopes(Scope("https://www.googleapis.com/auth/youtube")).build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null) { connectedAccountEmail = account.email; applyAccountToHeader(account) }

        ivProfilePhoto.setOnClickListener { 
            val acc = GoogleSignIn.getLastSignedInAccount(this)
            if (acc == null) { startActivityForResult(googleSignInClient.signInIntent, SIGN_IN_REQUEST) } 
            else { AlertDialog.Builder(this).setTitle("Account Options").setMessage("Logged in as: ${acc.email}").setPositiveButton("Logout") { _, _ -> googleSignInClient.signOut().addOnCompleteListener { connectedAccountEmail = null; ivProfilePhoto.setImageResource(android.R.drawable.sym_def_app_icon); } }.setNegativeButton("Cancel", null).show() }
        }

        btnStreamsManager.setOnClickListener { showSavedStreamsManager() }

        btnGoLive.setOnClickListener {
            if (rtmpCamera.isStreaming) { AlertDialog.Builder(this).setTitle("Stop Live Stream?").setMessage("This will end your broadcast on YouTube.").setPositiveButton("End Stream") { _, _ -> stopLiveStream() }.setNegativeButton("Cancel", null).show(); return@setOnClickListener }
            if (!rtmpCamera.isOnPreview) { tryStartCameraPreview(); return@setOnClickListener }
            val currentAccount = GoogleSignIn.getLastSignedInAccount(this)
            if (currentAccount == null) { startActivityForResult(googleSignInClient.signInIntent, SIGN_IN_REQUEST); return@setOnClickListener }
            if (!GoogleSignIn.hasPermissions(currentAccount, Scope("https://www.googleapis.com/auth/youtube"))) { GoogleSignIn.requestPermissions(this, REQUEST_AUTHORIZATION, currentAccount, Scope("https://www.googleapis.com/auth/youtube")); return@setOnClickListener }
            
            lockOrientation()
            showGoLiveDialog()
        }
        makeDraggableAndScalable(dragScoreboard)
        
        tickerHandler.post(tickerRunnable)
    }

    override fun onConnectionSuccess() { runOnUiThread { retryCount = 0; btnGoLive.text = "STOP STREAM"; btnGoLive.isEnabled = true; btnGoLive.setBackgroundColor(Color.parseColor("#E53935")); startStudioTimer() }; ensureBroadcastGoesLive() }
    
    override fun onConnectionFailed(reason: String) {
        if (retryCount < MAX_RETRIES && generatedRtmpUrl != null) { retryCount++; runOnUiThread { btnGoLive.text = "RETRYING ($retryCount/3)..." }; Thread { Thread.sleep(2000); try { rtmpCamera.startStream(generatedRtmpUrl!!) } catch (e: Exception) {} }.start() } else { StreamingService.stop(this@MainActivity); runOnUiThread { try { rtmpCamera.stopPreview() } catch (e: Exception) {}; unlockOrientation(); tryStartCameraPreview(); btnGoLive.text = "GO LIVE"; btnGoLive.isEnabled = true; try { rtmpCamera.stopStream() } catch (e: Exception) {}; stopChatPolling(); stopStudioTimer() } }
    }
    
    override fun onDisconnect() { StreamingService.stop(this@MainActivity); runOnUiThread { btnGoLive.text = "GO LIVE"; btnGoLive.isEnabled = true; btnGoLive.setBackgroundColor(Color.parseColor("#D32F2F")); try { rtmpCamera.stopPreview() } catch (e: Exception) {}; unlockOrientation(); tryStartCameraPreview(); stopChatPolling(); stopStudioTimer() } }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { super.onRequestPermissionsResult(requestCode, permissions, grantResults); tryStartCameraPreview() }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK && data != null) {
            when (requestCode) {
                PICK_IMAGE_REQUEST -> { try { val imageUri = data.data; if (imageUri != null) { addImageOverlayToScreen(BitmapFactory.decodeStream(contentResolver.openInputStream(imageUri))) } } catch (e: Exception) { e.printStackTrace() } }
                PICK_THUMBNAIL_REQUEST -> { 
                    globalThumbnailUri = data.data
                    thumbnailPreviewImageView?.setImageURI(globalThumbnailUri)
                    globalThumbnailUri?.let { uri ->
                        try { globalSlateBitmap = BitmapFactory.decodeStream(contentResolver.openInputStream(uri)) } catch (e: Exception) {}
                    }
                }
                SIGN_IN_REQUEST -> { val task = GoogleSignIn.getSignedInAccountFromIntent(data); try { val account = task.getResult(ApiException::class.java); connectedAccountEmail = account?.email; if (account != null) applyAccountToHeader(account) } catch (e: ApiException) { e.printStackTrace() } }
            }
        }
    }

    override fun onPause() { 
        super.onPause()
        if (!rtmpCamera.isStreaming && rtmpCamera.isOnPreview) { 
            try { rtmpCamera.stopPreview() } catch (e: Exception) {} 
        } 
    }
    
    override fun onResume() { 
        super.onResume() 
        if (activeRtmpCamera?.isStreaming == true) {
            lockOrientation()
        }
        if (surfaceReady && !rtmpCamera.isOnPreview && !rtmpCamera.isStreaming) {
            tryStartCameraPreview()
        } 
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!rtmpCamera.isStreaming) { try { rtmpCamera.stopPreview() } catch (e: Exception) {} }
        overlayHandler.removeCallbacksAndMessages(null); chatHandler.removeCallbacksAndMessages(null); timerHandler.removeCallbacksAndMessages(null); tickerHandler.removeCallbacksAndMessages(null); webSyncHandler.removeCallbacksAndMessages(null)
        bitmapA?.let { if (!it.isRecycled) it.recycle() }; bitmapA = null; canvasA = null; bitmapB?.let { if (!it.isRecycled) it.recycle() }; bitmapB = null; canvasB = null
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}
    
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { 
        openGlView.setAspectRatioMode(AspectRatioMode.Fill) 
        surfaceReady = true 
        if (isBackgrounded && rtmpCamera.isStreaming) {
            try { rtmpCamera.replaceView(openGlView) } catch (e: Exception) {}
            isBackgrounded = false
        } else if (!rtmpCamera.isOnPreview) { 
            tryStartCameraPreview() 
        } 
    }
    
    override fun surfaceDestroyed(holder: SurfaceHolder) { 
        surfaceReady = false
        isBackgrounded = true
        if (rtmpCamera.isStreaming) {
            try { rtmpCamera.replaceView(this) } catch (e: Exception) {}
        } else if (rtmpCamera.isOnPreview) {
            try { rtmpCamera.stopPreview() } catch (e: Exception) {}
        }
    }
    
    override fun onAuthError() { }
    override fun onAuthSuccess() { }
    override fun onConnectionStarted(url: String) {}
    override fun onNewBitrate(bitrate: Long) { if (rtmpCamera.isStreaming) { try { rtmpCamera.setVideoBitrateOnFly(bitrate.toInt()) } catch (e: Exception) {} } }
}
