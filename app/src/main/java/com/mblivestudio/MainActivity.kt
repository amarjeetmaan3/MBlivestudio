package com.mblivestudio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
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
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
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
import java.util.Calendar

class MainActivity : Activity(), ConnectChecker, SurfaceHolder.Callback {

    private lateinit var rtmpCamera: RtmpCamera2
    private lateinit var openGlView: OpenGlView
    private lateinit var overlayContainer: RelativeLayout
    private lateinit var imageFilterRender: ImageObjectFilterRender
    
    private val cameraLayoutFilter = com.mblivestudio.filters.CameraLayoutFilterRender()

    private lateinit var dragScoreboard: LinearLayout
    private lateinit var scoreMainText: TextView
    private lateinit var scoreSubText: TextView

    private lateinit var btnGoLive: Button
    private lateinit var btnStreamsManager: Button
    private lateinit var ivProfilePhoto: ImageView
    private lateinit var tvLiveTimer: TextView
    private lateinit var tvViewerCount: TextView
    private lateinit var tvApiQuota: TextView

    private lateinit var commentsPanel: LinearLayout
    private lateinit var tvCommentsFeed: TextView
    private lateinit var commentsScrollView: ScrollView
    private lateinit var tvStreamChatOverlay: TextView

    private lateinit var btnOverlayMenu: ImageButton
    private lateinit var btnOverlayDone: Button
    private var currentMode = "DRAG"
    private val resizeHandles = mutableListOf<View>()
    private val cropFrameViews = mutableListOf<View>()

    private var selectedOverlay: View? = null
    private var isAudioMuted = false
    private var isMenuExpanded = false
    private var isBluetoothMicActive = false
    private lateinit var audioManager: AudioManager

    private enum class MicRoute { PHONE, BLUETOOTH, WIRED }
    private var detectedMicRoute = MicRoute.PHONE
    private var bluetoothCommunicationDevice: AudioDeviceInfo? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null

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

    private var streamWidth = 1920
    private var streamHeight = 1080
    private var streamBitrate = 5_000_000
    private var lastAppliedZoomFactor = 1f

    private var pendingTitle: String = ""
    private var pendingDesc: String = ""
    private var pendingPrivacy: String = "unlisted"
    private var pendingScheduleTimeMs: Long = 0L
    private var pendingThumbnailUri: android.net.Uri? = null
    private var thumbnailPreviewImageView: ImageView? = null

    private var youtubeClient: YouTube? = null
    private var currentLiveChatId: String? = null
    private var currentBroadcastId: String? = null
    
    private var chatNextPageToken: String? = null
    private var chatPollingActive = false
    private val chatHandler = Handler(Looper.getMainLooper())
    
    private val streamChatHistory = mutableListOf<String>()

    private var dailyQuotaUsed = 0

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

    // FIX 1: LOWER THIRD CONTINUOUS TICKER HANDLER
    private val tickerHandler = Handler(Looper.getMainLooper())
    private val tickerRunnable = object : Runnable {
        override fun run() {
            updateSnapshot(50) // Faster refresh for smooth ticker animation
            tickerHandler.postDelayed(this, 100)
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
        
        btnOverlayMenu = findViewById(R.id.btnOverlayMenu)
        btnOverlayDone = findViewById(R.id.btnOverlayDone)

        // FIX 2: COLOR PICKER MOVED TO ⋮ MENU
        btnOverlayMenu.setOnClickListener {
            val target = selectedOverlay ?: return@setOnClickListener
            val popup = PopupMenu(this, btnOverlayMenu)
            popup.menu.add("Resize")
            
            if (target !is TextView && target.tag != "LOWER_THIRD") {
                popup.menu.add("Crop")
            }
            
            if (target is TextView || target is EditText || target.tag == "LOWER_THIRD") {
                popup.menu.add("Change Color")
            }
            
            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Resize" -> enterResizeMode(target)
                    "Crop" -> enterCropMode(target)
                    "Change Color" -> showCustomColorPickerDialog()
                }
                true
            }
            popup.show()
        }

        btnOverlayDone.setOnClickListener {
            currentMode = "DRAG"
            btnOverlayDone.visibility = View.GONE
            val root = findViewById<RelativeLayout>(R.id.rootLayout)
            resizeHandles.forEach { root.removeView(it) }
            resizeHandles.clear()
            cropFrameViews.forEach { root.removeView(it) }
            cropFrameViews.clear()
            selectedOverlay?.let { 
                if (it is EditText) it.clearFocus()
                it.setOnTouchListener(null)
                makeDraggableAndScalable(it) 
            }
            updateOverlayMenuButtonPosition()
            updateSnapshot()
        }

        val btnToggleMenu: ImageButton = findViewById(R.id.btnToggleMenu)
        val menuLabelsContainer: LinearLayout = findViewById(R.id.menuLabelsContainer)

        btnToggleMenu.setOnClickListener {
            if (isMenuExpanded) {
                menuLabelsContainer.visibility = View.GONE
                btnToggleMenu.setImageResource(R.drawable.ic_arrow_down)
                isMenuExpanded = false
            } else {
                menuLabelsContainer.visibility = View.VISIBLE
                btnToggleMenu.setImageResource(R.drawable.ic_arrow_up)
                isMenuExpanded = true
            }
        }

        val btnSwitchCamera: ImageButton = findViewById(R.id.btnSwitchCamera)
        val btnMicToggle: ImageButton = findViewById(R.id.btnMicToggle)
        val btnBluetoothMic: ImageButton = findViewById(R.id.btnBluetoothMic)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        registerAudioDeviceMonitoring()
        updateDetectedMicRoute(false)
        val btnToggleLayouts: ImageButton = findViewById(R.id.btnToggleLayouts)
        val btnToggleOverlays: ImageButton = findViewById(R.id.btnToggleOverlays)
        val btnToggleComments: ImageButton = findViewById(R.id.btnToggleComments)
        val btnLiveText: ImageButton = findViewById(R.id.btnLiveText)

        val popupLayouts: LinearLayout = findViewById(R.id.popupLayouts)
        val popupOverlays: LinearLayout = findViewById(R.id.popupOverlays)
        val btnCloseLayouts: Button = findViewById(R.id.btnCloseLayouts)
        val btnCloseOverlays: Button = findViewById(R.id.btnCloseOverlays)

        btnToggleLayouts.setOnClickListener { popupLayouts.visibility = View.VISIBLE; popupOverlays.visibility = View.GONE }
        btnToggleOverlays.setOnClickListener { popupOverlays.visibility = View.VISIBLE; popupLayouts.visibility = View.GONE }
        btnCloseLayouts.setOnClickListener { popupLayouts.visibility = View.GONE }
        btnCloseOverlays.setOnClickListener { popupOverlays.visibility = View.GONE }

        btnToggleComments.setOnClickListener {
            commentsPanel.visibility = if (commentsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        btnLiveText.setOnClickListener { popupOverlays.visibility = View.GONE; addLiveTextOverlay() }
        
        findViewById<ImageButton>(R.id.btnAddLowerThird).setOnClickListener { popupOverlays.visibility = View.GONE; showAddLowerThirdDialog() }

        btnMicToggle.setColorFilter(Color.parseColor("#4CAF50"))
        btnMicToggle.setOnClickListener {
            if (isAudioMuted) {
                rtmpCamera.enableAudio(); isAudioMuted = false
                btnMicToggle.setImageResource(R.drawable.ic_mic_on)
                btnMicToggle.setColorFilter(Color.parseColor("#4CAF50"))
            } else {
                rtmpCamera.disableAudio(); isAudioMuted = true
                btnMicToggle.setImageResource(R.drawable.ic_mic_on) 
                btnMicToggle.setColorFilter(Color.parseColor("#E53935"))
            }
        }

        btnSwitchCamera.setOnClickListener {
            try { rtmpCamera.switchCamera(); if (!rtmpCamera.isStreaming) { rtmpCamera.stopPreview(); tryStartCameraPreview() } } catch (e: Exception) {}
        }

        btnBluetoothMic.setOnClickListener {
            if (rtmpCamera.isStreaming) { Toast.makeText(this, "Stop the stream before switching mic source.", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2); return@setOnClickListener }
            toggleBluetoothMic(btnBluetoothMic)
        }

        findViewById<ImageButton>(R.id.btnLayoutFull).setOnClickListener { applyCameraLayout(com.mblivestudio.filters.CameraLayoutFilterRender.FULL); popupLayouts.visibility = View.GONE }
        findViewById<ImageButton>(R.id.btnLayoutSplit).setOnClickListener { applyCameraLayout(com.mblivestudio.filters.CameraLayoutFilterRender.SPLIT_LEFT); popupLayouts.visibility = View.GONE }
        findViewById<ImageButton>(R.id.btnLayoutCornerTL).setOnClickListener { applyCameraLayout(com.mblivestudio.filters.CameraLayoutFilterRender.CORNER_TOP_LEFT); popupLayouts.visibility = View.GONE }
        findViewById<ImageButton>(R.id.btnLayoutCornerBR).setOnClickListener { applyCameraLayout(com.mblivestudio.filters.CameraLayoutFilterRender.CORNER_BOTTOM_RIGHT); popupLayouts.visibility = View.GONE }

        findViewById<ImageButton>(R.id.btnAddText).setOnClickListener { popupOverlays.visibility = View.GONE; showAddTextDialog() }
        findViewById<ImageButton>(R.id.btnAddWebOverlay).setOnClickListener { popupOverlays.visibility = View.GONE; showAddWebDialog() }
        findViewById<ImageButton>(R.id.btnAddLogo).setOnClickListener { popupOverlays.visibility = View.GONE; val intent = Intent(Intent.ACTION_GET_CONTENT); intent.type = "image/*"; startActivityForResult(intent, PICK_IMAGE_REQUEST) }
        
        findViewById<ImageButton>(R.id.btnToggleScore).setOnClickListener { popupOverlays.visibility = View.GONE; if (dragScoreboard.visibility == View.VISIBLE) { dragScoreboard.visibility = View.GONE; updateSnapshot() } else { showScoreboardDialog() } }
        
        findViewById<ImageButton>(R.id.btnRemoveSelected).setOnClickListener { 
            popupOverlays.visibility = View.GONE
            selectedOverlay?.let { 
                if (it != dragScoreboard) { 
                    if (it.tag == "LOWER_THIRD") {
                        tickerHandler.removeCallbacks(tickerRunnable) // Stop loop
                    }
                    overlayContainer.removeView(it)
                    selectedOverlay = null
                    updateOverlayMenuButtonPosition()
                    updateSnapshot() 
                } 
            } 
        }

        findViewById<Button>(R.id.btnZoomIn).setOnClickListener { val targetZoom = (lastAppliedZoomFactor + 0.2f).coerceIn(1f, 5f); if (targetZoom != lastAppliedZoomFactor) { val delta = targetZoom / lastAppliedZoomFactor; lastAppliedZoomFactor = targetZoom; sendSyntheticZoomEvent(MotionEvent.ACTION_MOVE, 300f, delta) } }
        findViewById<Button>(R.id.btnZoomOut).setOnClickListener { val targetZoom = (lastAppliedZoomFactor - 0.2f).coerceIn(1f, 5f); if (targetZoom != lastAppliedZoomFactor) { val delta = targetZoom / lastAppliedZoomFactor; lastAppliedZoomFactor = targetZoom; sendSyntheticZoomEvent(MotionEvent.ACTION_MOVE, 300f, delta) } }

        rtmpCamera = RtmpCamera2(openGlView, this)
        openGlView.holder.addCallback(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !hasCameraPermissions()) requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
        imageFilterRender = ImageObjectFilterRender()
        overlayContainer.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().requestProfile().requestScopes(Scope("https://www.googleapis.com/auth/youtube")).build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account != null) { connectedAccountEmail = account.email; applyAccountToHeader(account) }

        ivProfilePhoto.setOnClickListener { 
            val acc = GoogleSignIn.getLastSignedInAccount(this)
            if (acc == null) {
                startActivityForResult(googleSignInClient.signInIntent, SIGN_IN_REQUEST) 
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Account Options")
                    .setMessage("Logged in as: ${acc.email}")
                    .setPositiveButton("Logout / Switch Channel") { _, _ ->
                        googleSignInClient.signOut().addOnCompleteListener {
                            connectedAccountEmail = null
                            ivProfilePhoto.setImageResource(android.R.drawable.sym_def_app_icon)
                            Toast.makeText(this, "Logged out. You can now login with another channel.", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }

        btnStreamsManager.setOnClickListener { showSavedStreamsManager() }

        btnGoLive.setOnClickListener {
            if (rtmpCamera.isStreaming) { AlertDialog.Builder(this).setTitle("Stop Live Stream?").setMessage("This will end your broadcast on YouTube.").setPositiveButton("End Stream") { _, _ -> stopLiveStream() }.setNegativeButton("Cancel", null).show(); return@setOnClickListener }
            if (!rtmpCamera.isOnPreview) { tryStartCameraPreview(); Toast.makeText(this, "Camera starting, try LIVE again in a moment.", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val currentAccount = GoogleSignIn.getLastSignedInAccount(this)
            if (currentAccount == null) { Toast.makeText(this, "Please Sign In with YouTube first!", Toast.LENGTH_SHORT).show(); startActivityForResult(googleSignInClient.signInIntent, SIGN_IN_REQUEST); return@setOnClickListener }
            if (!GoogleSignIn.hasPermissions(currentAccount, Scope("https://www.googleapis.com/auth/youtube"))) { GoogleSignIn.requestPermissions(this, REQUEST_AUTHORIZATION, currentAccount, Scope("https://www.googleapis.com/auth/youtube")); return@setOnClickListener }
            showGoLiveDialog()
        }

        makeDraggableAndScalable(dragScoreboard)
        makeStudioPanelDraggable(commentsPanel)
    }

    private fun loadQuota() {
        val prefs = getSharedPreferences("MBLivePrefs", Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val savedDate = prefs.getString("QuotaDate", "")
        if (savedDate != today) { dailyQuotaUsed = 0; prefs.edit().putString("QuotaDate", today).putInt("QuotaUsed", 0).apply() } else { dailyQuotaUsed = prefs.getInt("QuotaUsed", 0) }
        updateQuotaUI()
    }

    private fun addQuota(amount: Int) {
        dailyQuotaUsed += amount
        getSharedPreferences("MBLivePrefs", Context.MODE_PRIVATE).edit().putInt("QuotaUsed", dailyQuotaUsed).apply()
        runOnUiThread { updateQuotaUI() }
    }

    private fun updateQuotaUI() { tvApiQuota.text = "⚙️ $dailyQuotaUsed/10K" }

    private var scoStateReceiver: android.content.BroadcastReceiver? = null
    private val scoConnectTimeoutHandler = Handler(Looper.getMainLooper())

    private fun registerAudioDeviceMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        audioDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { updateDetectedMicRoute(true) }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (removedDevices.any { isBluetoothInputType(it.type) } && isBluetoothMicActive) { isBluetoothMicActive = false; bluetoothCommunicationDevice = null; clearBluetoothRoute(); restartCameraForAudioChange(250) }
                updateDetectedMicRoute(true)
            }
        }
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
    }

    private fun isBluetoothInputType(type: Int): Boolean { return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET) }
    private fun isWiredInputType(type: Int): Boolean { return type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_USB_DEVICE || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && type == AudioDeviceInfo.TYPE_USB_HEADSET) }
    private fun getInputDevices(): List<AudioDeviceInfo> { if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList(); return try { audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList() } catch (_: Exception) { emptyList() } }
    private fun findBluetoothInput(): AudioDeviceInfo? { return getInputDevices().firstOrNull { isBluetoothInputType(it.type) } }
    private fun findWiredInput(): AudioDeviceInfo? { return getInputDevices().firstOrNull { isWiredInputType(it.type) } }

    private fun updateDetectedMicRoute(showToast: Boolean) {
        val route = when { isBluetoothMicActive && findBluetoothInput() != null -> MicRoute.BLUETOOTH; findWiredInput() != null -> MicRoute.WIRED; else -> MicRoute.PHONE }
        val changed = route != detectedMicRoute; detectedMicRoute = route
        if (showToast && changed && !isFinishing) Toast.makeText(this, when(route){ MicRoute.BLUETOOTH->"Bluetooth mic detected"; MicRoute.WIRED->"Wired/USB mic detected"; MicRoute.PHONE->"Phone mic active" }, Toast.LENGTH_SHORT).show()
    }

    private fun toggleBluetoothMic(button: ImageButton) {
        if (!isBluetoothMicActive) {
            val btInput = findBluetoothInput()
            if (btInput == null) { Toast.makeText(this, "Bluetooth mic not available. Connect it first.", Toast.LENGTH_LONG).show(); updateDetectedMicRoute(false); return }
            if (routeBluetoothMic()) { isBluetoothMicActive = true; bluetoothCommunicationDevice = btInput; detectedMicRoute = MicRoute.BLUETOOTH; button.setColorFilter(Color.parseColor("#4CAF50")); Toast.makeText(this, "Bluetooth mic selected", Toast.LENGTH_SHORT).show(); restartCameraForAudioChange(300) } else { isBluetoothMicActive = false; bluetoothCommunicationDevice = null; button.clearColorFilter(); Toast.makeText(this, "Bluetooth mic failed.", Toast.LENGTH_LONG).show() }
        } else {
            clearBluetoothRoute(); isBluetoothMicActive = false; bluetoothCommunicationDevice = null; button.clearColorFilter(); updateDetectedMicRoute(false); Toast.makeText(this, "Bluetooth mic off", Toast.LENGTH_SHORT).show(); restartCameraForAudioChange(250)
        }
    }

    @SuppressLint("MissingPermission")
    private fun routeBluetoothMic(): Boolean {
        return try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val commDevice = audioManager.availableCommunicationDevices.firstOrNull { isBluetoothInputType(it.type) } ?: return false
                if (!audioManager.setCommunicationDevice(commDevice)) return false
                Handler(Looper.getMainLooper()).postDelayed({ updateDetectedMicRoute(false) }, 150); true
            } else {
                if (!audioManager.isBluetoothScoAvailableOffCall) return false
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        when (intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)) {
                            AudioManager.SCO_AUDIO_STATE_CONNECTED -> { scoConnectTimeoutHandler.removeCallbacksAndMessages(null); try { unregisterReceiver(this) } catch (_: Exception) {}; scoStateReceiver = null; restartCameraForAudioChange(150) }
                            AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> { scoConnectTimeoutHandler.removeCallbacksAndMessages(null); try { unregisterReceiver(this) } catch (_: Exception) {}; scoStateReceiver = null; isBluetoothMicActive = false; bluetoothCommunicationDevice = null; Toast.makeText(this@MainActivity, "Bluetooth mic disconnected.", Toast.LENGTH_SHORT).show(); updateDetectedMicRoute(false) }
                        }
                    }
                }
                scoStateReceiver = receiver; registerReceiver(receiver, android.content.IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
                audioManager.startBluetoothSco(); audioManager.isBluetoothScoOn = true
                scoConnectTimeoutHandler.postDelayed({ scoStateReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {}; scoStateReceiver = null; isBluetoothMicActive = false; bluetoothCommunicationDevice = null; try { audioManager.stopBluetoothSco(); audioManager.isBluetoothScoOn = false; audioManager.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}; updateDetectedMicRoute(false); Toast.makeText(this, "Bluetooth timeout.", Toast.LENGTH_LONG).show() } }, 8000); true
            }
        } catch (e: Exception) { false }
    }

    @SuppressLint("MissingPermission")
    private fun clearBluetoothRoute() {
        scoStateReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }; scoStateReceiver = null; scoConnectTimeoutHandler.removeCallbacksAndMessages(null)
        try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { audioManager.clearCommunicationDevice() } else { audioManager.stopBluetoothSco(); audioManager.isBluetoothScoOn = false }; audioManager.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}
    }

    private fun restartCameraForAudioChange(delayMs: Long) {
        if (!rtmpCamera.isOnPreview || rtmpCamera.isStreaming) return
        try { rtmpCamera.stopPreview() } catch (_: Exception) {}
        Handler(Looper.getMainLooper()).postDelayed({ tryStartCameraPreview() }, delayMs)
    }

    private fun refreshChatOverlayText(forcedHeight: Int? = null) {
        if (tvStreamChatOverlay.visibility != View.VISIBLE) return
        val maxLines = if ((forcedHeight ?: tvStreamChatOverlay.height) > 0 && tvStreamChatOverlay.lineHeight > 0) (forcedHeight ?: tvStreamChatOverlay.height) / tvStreamChatOverlay.lineHeight else 8
        tvStreamChatOverlay.text = streamChatHistory.takeLast(maxLines.coerceAtLeast(1)).joinToString("\n")
    }

    private fun updateOverlayMenuButtonPosition() {
        val target = selectedOverlay
        if (target == null || currentMode != "DRAG") { btnOverlayMenu.visibility = View.GONE; return }
        btnOverlayMenu.visibility = View.VISIBLE
        btnOverlayMenu.x = target.x + (target.width * target.scaleX) - (32 * resources.displayMetrics.density)
        btnOverlayMenu.y = target.y - (16 * resources.displayMetrics.density)
    }

    private fun positionDoneButton(target: View) { btnOverlayDone.x = target.x; btnOverlayDone.y = target.y - (40 * resources.displayMetrics.density) }

    private fun addResizeHandle(target: View, xAlign: Float, yAlign: Float, wMult: Int, hMult: Int, isEdge: Boolean) {
        val size = ((if (isEdge) 12 else 24) * resources.displayMetrics.density).toInt()
        val handle = View(this).apply { layoutParams = RelativeLayout.LayoutParams(size, size); setBackgroundColor(if (isEdge) Color.parseColor("#8800BCD4") else Color.parseColor("#00BCD4")) }
        val root = findViewById<RelativeLayout>(R.id.rootLayout); root.addView(handle); resizeHandles.add(handle)
        val updateHandlePos = { handle.x = target.x + (target.width * target.scaleX * xAlign) - size / 2; handle.y = target.y + (target.height * target.scaleY * yAlign) - size / 2 }
        updateHandlePos()
        var dX = 0f; var dY = 0f; var startW = 0; var startH = 0; var startX = 0f; var startY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dX = event.rawX; dY = event.rawY; startW = target.width; startH = target.height; startX = target.x; startY = target.y }
                MotionEvent.ACTION_MOVE -> {
                    var newW = startW + ((event.rawX - dX).toInt() * wMult); var newH = startH + ((event.rawY - dY).toInt() * hMult)
                    if (newW < 100) newW = 100; if (newH < 100) newH = 100
                    target.layoutParams.width = newW; target.layoutParams.height = newH; target.requestLayout()
                    if (wMult < 0) target.x = startX + (startW - newW); if (hMult < 0) target.y = startY + (startH - newH)
                    updateHandlePos(); if (target == tvStreamChatOverlay) refreshChatOverlayText(newH)
                    root.post { resizeHandles.forEach { (it.tag as? ()->Unit)?.invoke() } }; updateSnapshot()
                }
                MotionEvent.ACTION_UP -> updateSnapshot()
            }
            true
        }
        handle.tag = updateHandlePos
    }

    private fun showCropFrame(target: View) {
        val root = findViewById<RelativeLayout>(R.id.rootLayout); val density = resources.displayMetrics.density
        val border = View(this).apply { layoutParams = RelativeLayout.LayoutParams(target.width, target.height); background = android.graphics.drawable.GradientDrawable().apply { setStroke((2 * density).toInt(), Color.parseColor("#00BCD4")); setColor(Color.TRANSPARENT) }; x = target.x; y = target.y; isClickable = false }
        root.addView(border); cropFrameViews.add(border)
        listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f).forEach { (xAlign, yAlign) ->
            val dot = View(this).apply { layoutParams = RelativeLayout.LayoutParams((16 * density).toInt(), (16 * density).toInt()); setBackgroundColor(Color.parseColor("#00BCD4")); x = target.x + (target.width * xAlign) - (8 * density); y = target.y + (target.height * yAlign) - (8 * density); isClickable = false }
            root.addView(dot); cropFrameViews.add(dot)
        }
    }

    private fun enterResizeMode(target: View) {
        currentMode = "RESIZE"; btnOverlayMenu.visibility = View.GONE; btnOverlayDone.visibility = View.VISIBLE; positionDoneButton(target); target.setOnTouchListener(null)
        if (target is ImageView) target.scaleType = ImageView.ScaleType.FIT_XY
        addResizeHandle(target, 0f, 0f, -1, -1, false); addResizeHandle(target, 1f, 0f, 1, -1, false); addResizeHandle(target, 0f, 1f, -1, 1, false); addResizeHandle(target, 1f, 1f, 1, 1, false)
        addResizeHandle(target, 0.5f, 0f, 0, -1, true); addResizeHandle(target, 0.5f, 1f, 0, 1, true); addResizeHandle(target, 0f, 0.5f, -1, 0, true); addResizeHandle(target, 1f, 0.5f, 1, 0, true)
    }

    private fun enterCropMode(target: View) {
        currentMode = "CROP"; btnOverlayMenu.visibility = View.GONE; btnOverlayDone.visibility = View.VISIBLE; positionDoneButton(target); showCropFrame(target)
        if (target is ImageView) {
            val bmpW = target.drawable?.intrinsicWidth?.toFloat() ?: target.width.toFloat(); val bmpH = target.drawable?.intrinsicHeight?.toFloat() ?: target.height.toFloat()
            val fillScale = maxOf(target.width.toFloat() / bmpW, target.height.toFloat() / bmpH)
            target.scaleType = ImageView.ScaleType.MATRIX
            var scale = fillScale; var transX = (target.width - bmpW * scale) / 2f; var transY = (target.height - bmpH * scale) / 2f
            fun clampAndApply() { transX = transX.coerceIn(target.width - bmpW * scale, 0f); transY = transY.coerceIn(target.height - bmpH * scale, 0f); val matrix = android.graphics.Matrix(); matrix.postScale(scale, scale); matrix.postTranslate(transX, transY); target.imageMatrix = matrix; updateSnapshot() }
            clampAndApply()
            val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() { override fun onScale(d: ScaleGestureDetector): Boolean { scale = (scale * d.scaleFactor).coerceIn(fillScale, fillScale * 4f); clampAndApply(); return true } })
            var dX = 0f; var dY = 0f
            target.setOnTouchListener { _, event ->
                if (currentMode != "CROP") return@setOnTouchListener false
                scaleDetector.onTouchEvent(event)
                if (!scaleDetector.isInProgress) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { dX = transX - event.rawX; dY = transY - event.rawY }
                        MotionEvent.ACTION_MOVE -> { transX = event.rawX + dX; transY = event.rawY + dY; clampAndApply() }
                        MotionEvent.ACTION_UP -> updateSnapshot()
                    }
                }
                true
            }
        } else if (target is WebView) { target.settings.builtInZoomControls = true; target.settings.displayZoomControls = false; target.setOnTouchListener(null) }
    }

    private fun showAddTextDialog() {
        val input = EditText(this).apply { hint = "Enter text..."; inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("Add Text Overlay").setView(input).setPositiveButton("Add") { _, _ -> 
            if (input.text.toString().trim().isNotEmpty()) {
                val textView = TextView(this).apply { text = input.text.toString().trim(); setTextColor(Color.YELLOW); textSize = 30f; setTypeface(null, Typeface.BOLD); layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply { addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE) } }
                overlayContainer.addView(textView); makeDraggableAndScalable(textView); selectedOverlay = textView; updateOverlayMenuButtonPosition(); updateSnapshot()
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun showAddLowerThirdDialog() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40,20,40,20) }
        val titleInput = EditText(this).apply { hint = "Title (e.g. BREAKING NEWS)" }
        val msgInput = EditText(this).apply { hint = "Running Text (e.g. Subscribe to channel...)" }
        container.addView(titleInput); container.addView(msgInput)
        
        AlertDialog.Builder(this).setTitle("Add Lower Third Ticker").setView(container)
            .setPositiveButton("Add") { _, _ -> 
                val wrapper = LinearLayout(this).apply {
                    tag = "LOWER_THIRD"
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = RelativeLayout.LayoutParams(1200, RelativeLayout.LayoutParams.WRAP_CONTENT).apply { addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.TRUE); bottomMargin = 100; leftMargin = 50 }
                }
                val tvTitle = TextView(this).apply {
                    text = titleInput.text.toString()
                    setBackgroundColor(Color.parseColor("#1565C0"))
                    setTextColor(Color.WHITE)
                    textSize = 24f
                    setTypeface(null, Typeface.BOLD)
                    setPadding(30, 20, 30, 20)
                }
                val tvMsg = TextView(this).apply {
                    text = msgInput.text.toString()
                    setBackgroundColor(Color.parseColor("#C62828"))
                    setTextColor(Color.WHITE)
                    textSize = 24f
                    setPadding(30, 20, 30, 20)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
                    ellipsize = TextUtils.TruncateAt.MARQUEE
                    marqueeRepeatLimit = -1
                    isSingleLine = true
                    isSelected = true
                }
                wrapper.addView(tvTitle); wrapper.addView(tvMsg)
                overlayContainer.addView(wrapper)
                makeDraggableAndScalable(wrapper)
                selectedOverlay = wrapper
                
                // FIX 1: Start animating the ticker
                tickerHandler.post(tickerRunnable)
                
                updateOverlayMenuButtonPosition()
                updateSnapshot()
            }.setNegativeButton("Cancel", null).show()
    }

    // FIX 3: DYNAMIC COLOR PICKER (Checks if Lower Third or Normal Text)
    private fun showCustomColorPickerDialog() {
        val target = selectedOverlay ?: run { Toast.makeText(this, "Select a text/overlay first.", Toast.LENGTH_SHORT).show(); return }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40,20,40,20) }
        val previewBox = View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 150); setBackgroundColor(Color.WHITE) }
        
        var r = 255; var g = 255; var b = 255
        val rSeek = SeekBar(this).apply { max = 255; progress = 255 }
        val gSeek = SeekBar(this).apply { max = 255; progress = 255 }
        val bSeek = SeekBar(this).apply { max = 255; progress = 255 }

        val updateColor = { 
            r = rSeek.progress; g = gSeek.progress; b = bSeek.progress
            previewBox.setBackgroundColor(Color.rgb(r, g, b)) 
        }
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { updateColor() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }
        rSeek.setOnSeekBarChangeListener(listener); gSeek.setOnSeekBarChangeListener(listener); bSeek.setOnSeekBarChangeListener(listener)
        
        container.addView(previewBox)
        container.addView(TextView(this).apply { text = "Red"; setPadding(0,20,0,0) }); container.addView(rSeek)
        container.addView(TextView(this).apply { text = "Green"; setPadding(0,20,0,0) }); container.addView(gSeek)
        container.addView(TextView(this).apply { text = "Blue"; setPadding(0,20,0,0) }); container.addView(bSeek)
        
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0,30,0,0) }
        val dialog = AlertDialog.Builder(this).setTitle("Custom Color Picker").setView(container).setNegativeButton("Close", null).create()

        if (target.tag == "LOWER_THIRD" && target is LinearLayout) {
            val btnTitleBg = Button(this).apply { text = "Title BG"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            val btnTickerBg = Button(this).apply { text = "Ticker BG"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            val btnTextColor = Button(this).apply { text = "Text Color"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            
            btnTitleBg.setOnClickListener { (target.getChildAt(0) as? TextView)?.setBackgroundColor(Color.rgb(r, g, b)); updateSnapshot(); dialog.dismiss() }
            btnTickerBg.setOnClickListener { (target.getChildAt(1) as? TextView)?.setBackgroundColor(Color.rgb(r, g, b)); updateSnapshot(); dialog.dismiss() }
            btnTextColor.setOnClickListener { 
                (target.getChildAt(0) as? TextView)?.setTextColor(Color.rgb(r, g, b))
                (target.getChildAt(1) as? TextView)?.setTextColor(Color.rgb(r, g, b))
                updateSnapshot(); dialog.dismiss() 
            }
            btnRow.addView(btnTitleBg); btnRow.addView(btnTickerBg); btnRow.addView(btnTextColor)
        } else {
            val btnTextColor = Button(this).apply { text = "Apply to Text"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            val btnBgColor = Button(this).apply { text = "Apply to Background"; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            btnTextColor.setOnClickListener { (target as? TextView)?.setTextColor(Color.rgb(r, g, b)); updateSnapshot(); dialog.dismiss() }
            btnBgColor.setOnClickListener { (target as? TextView)?.setBackgroundColor(Color.rgb(r, g, b)); updateSnapshot(); dialog.dismiss() }
            btnRow.addView(btnTextColor); btnRow.addView(btnBgColor)
        }
        
        container.addView(btnRow)
        dialog.show()
    }

    private fun addLiveTextOverlay() {
        val liveEditText = EditText(this).apply {
            hint = "Start typing..."; setHintTextColor(Color.argb(128, 255, 255, 255)); setTextColor(Color.YELLOW); textSize = 30f; setTypeface(null, Typeface.BOLD); background = null; setShadowLayer(5f, 2f, 2f, Color.BLACK); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply { addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE) }
            addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateSnapshot() }; override fun afterTextChanged(s: Editable?) {} })
        }
        overlayContainer.addView(liveEditText); makeDraggableAndScalable(liveEditText); selectedOverlay = liveEditText; updateOverlayMenuButtonPosition(); updateSnapshot()
        liveEditText.requestFocus(); (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(liveEditText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun showScoreboardDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 20) }
        val mainInput = EditText(this).apply { hint = "Main Score (IND 245/3)" }
        val subInput = EditText(this).apply { hint = "Sub Score (Target: 312)" }
        layout.addView(mainInput); layout.addView(subInput)
        AlertDialog.Builder(this).setTitle("Update Scoreboard").setView(layout).setPositiveButton("Show") { _, _ -> scoreMainText.text = mainInput.text.toString(); scoreSubText.text = subInput.text.toString(); dragScoreboard.visibility = View.VISIBLE; updateSnapshot() }.setNegativeButton("Cancel", null).show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showAddWebDialog() {
        val input = EditText(this).apply { hint = "https://..." }
        AlertDialog.Builder(this).setTitle("Add Web Overlay").setView(input).setPositiveButton("Add") { _, _ ->
            val url = input.text.toString().trim()
            if (url.isNotEmpty()) {
                val finalUrl = if (!url.startsWith("http")) "https://$url" else url
                val displayMetrics = resources.displayMetrics; val boxWidth = (displayMetrics.widthPixels * 0.85).toInt(); val boxHeight = (displayMetrics.heightPixels * 0.85).toInt()
                val webView = WebView(this).apply {
                    layoutParams = RelativeLayout.LayoutParams(boxWidth, boxHeight).apply { addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE) }
                    setBackgroundColor(Color.TRANSPARENT); setLayerType(View.LAYER_TYPE_SOFTWARE, null); settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.useWideViewPort = true; settings.loadWithOverviewMode = true; webViewClient = WebViewClient(); webChromeClient = WebChromeClient(); loadUrl(finalUrl)
                }
                overlayContainer.addView(webView); makeDraggableAndScalable(webView); selectedOverlay = webView; updateOverlayMenuButtonPosition(); overlayHandler.postDelayed({ updateSnapshot() }, 2000)
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun applyAccountToHeader(account: com.google.android.gms.auth.api.signin.GoogleSignInAccount) {
        account.photoUrl?.let { url -> Thread { try { val input = URL(url.toString()).openStream(); val bmp = BitmapFactory.decodeStream(input); input.close(); val circular = cropToCircle(bmp); runOnUiThread { findViewById<ImageView>(R.id.ivProfilePhoto).setImageBitmap(circular) } } catch (e: Exception) { e.printStackTrace() } }.start() }
    }

    private fun cropToCircle(bitmap: Bitmap): Bitmap {
        val size = minOf(bitmap.width, bitmap.height); val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output); val paint = Paint(Paint.ANTI_ALIAS_FLAG); paint.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint); return output
    }

    private fun saveStreamLocally(title: String, broadcastId: String, chatId: String, rtmpUrl: String) {
        val prefs = getSharedPreferences("MBLiveStreams", Context.MODE_PRIVATE)
        val arr = org.json.JSONArray(prefs.getString("streams", "[]"))
        val obj = org.json.JSONObject().apply { put("title", title); put("broadcastId", broadcastId); put("chatId", chatId); put("rtmpUrl", rtmpUrl); put("time", System.currentTimeMillis()) }
        arr.put(obj); prefs.edit().putString("streams", arr.toString()).apply()
    }
    
    private fun removeSavedStream(broadcastId: String) {
        val prefs = getSharedPreferences("MBLiveStreams", Context.MODE_PRIVATE)
        val arr = org.json.JSONArray(prefs.getString("streams", "[]")); val newArr = org.json.JSONArray()
        for (i in 0 until arr.length()) { val obj = arr.getJSONObject(i); if (obj.getString("broadcastId") != broadcastId) newArr.put(obj) }
        prefs.edit().putString("streams", newArr.toString()).apply()
    }

    private fun showSavedStreamsManager() {
        val prefs = getSharedPreferences("MBLiveStreams", Context.MODE_PRIVATE)
        val arr = org.json.JSONArray(prefs.getString("streams", "[]"))
        if (arr.length() == 0) { Toast.makeText(this, "No saved streams. Click GO LIVE to create one.", Toast.LENGTH_SHORT).show(); return }
        
        val items = Array(arr.length()) { i -> arr.getJSONObject(i).getString("title") }
        AlertDialog.Builder(this).setTitle("Your Streams").setItems(items) { _, which ->
            val obj = arr.getJSONObject(which)
            val title = obj.getString("title")
            val bId = obj.getString("broadcastId")
            val link = "https://youtu.be/$bId"
            showStreamReadyDialog(title, link, obj.getString("rtmpUrl"), bId, obj.getString("chatId"))
        }.setNegativeButton("Close", null).show()
    }

    private fun showStreamReadyDialog(title: String, link: String, rtmpUrl: String, broadcastId: String, chatId: String) {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40,40,40,40) }
        container.addView(TextView(this).apply { text = "Stream Created Successfully!\n\nShare this link with your viewers:"; textSize = 16f; setPadding(0,0,0,20) })
        container.addView(TextView(this).apply { text = link; textSize = 18f; setTextColor(Color.parseColor("#2196F3")); setTypeface(null, Typeface.BOLD); setPadding(0,0,0,40) })
        
        val btnCopy = Button(this).apply { text = "COPY LINK"; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
        val btnShare = Button(this).apply { text = "SHARE LINK"; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT) }
        val btnStart = Button(this).apply { text = "▶ START CAMERA"; setBackgroundColor(Color.parseColor("#4CAF50")); setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin=40 } }
        container.addView(btnCopy); container.addView(btnShare); container.addView(btnStart)

        val dialog = AlertDialog.Builder(this).setTitle(title).setView(container).setNegativeButton("SAVE FOR LATER", null).create()
        
        btnCopy.setOnClickListener { val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; clipboard.setPrimaryClip(ClipData.newPlainText("Live Stream Link", link)); Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show() }
        btnShare.setOnClickListener { val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, title); putExtra(Intent.EXTRA_TEXT, "Join my live stream: $link") }; startActivity(Intent.createChooser(intent, "Share via")) }
        btnStart.setOnClickListener {
            dialog.dismiss()
            currentBroadcastId = broadcastId
            generatedRtmpUrl = rtmpUrl
            btnGoLive.text = "CONNECTING..."
            try { rtmpCamera.startStream(rtmpUrl); startChatPolling(chatId) } catch (e: Exception) { Toast.makeText(this, "Stream Error", Toast.LENGTH_LONG).show(); btnGoLive.text = "GO LIVE" }
        }
        dialog.show()
    }

    private fun showGoLiveDialog() {
        pendingScheduleTimeMs = 0L // Reset
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, padding, padding, padding) }
        val etTitle = EditText(this).apply { hint = "Broadcast Title"; setText(pendingTitle) }
        val etDesc = EditText(this).apply { hint = "Description"; setText(pendingDesc) }
        val privacyOptions = arrayOf("Public", "Unlisted", "Private")
        val spinner = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, privacyOptions); setSelection(privacyOptions.indexOfFirst { it.equals(pendingPrivacy, ignoreCase = true) }.coerceAtLeast(1)) }
        
        val btnTime = Button(this).apply { text = "SCHEDULE (OPTIONAL)" }
        val thumbPreview = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams((140 * resources.displayMetrics.density).toInt(), (90 * resources.displayMetrics.density).toInt()).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }; scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.parseColor("#333333")); pendingThumbnailUri?.let { setImageURI(it) } }
        thumbnailPreviewImageView = thumbPreview
        val thumbWrapper = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER; addView(thumbPreview) }
        val btnThumbnail = Button(this).apply { text = "CHOOSE THUMBNAIL" }
        val btnConfirmLive = Button(this).apply { text = "CREATE STREAM"; setBackgroundColor(Color.parseColor("#D32F2F")); setTextColor(Color.WHITE) }

        btnTime.setOnClickListener {
            val c = Calendar.getInstance()
            DatePickerDialog(this, { _, y, m, d ->
                TimePickerDialog(this, { _, h, min ->
                    val sel = Calendar.getInstance().apply { set(y, m, d, h, min, 0) }
                    pendingScheduleTimeMs = sel.timeInMillis
                    btnTime.text = "Scheduled: ${java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.US).format(sel.time)}"
                }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
        }

        listOf(etTitle, etDesc, spinner, btnTime, thumbWrapper, btnThumbnail, btnConfirmLive).forEach { val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = (8 * resources.displayMetrics.density).toInt(); it.layoutParams = lp; container.addView(it) }
        val dialog = AlertDialog.Builder(this).setTitle("Setup Broadcast").setView(ScrollView(this).apply { addView(container) }).setNegativeButton("Cancel", null).create()

        btnThumbnail.setOnClickListener { val intent = Intent(Intent.ACTION_GET_CONTENT); intent.type = "image/*"; startActivityForResult(intent, PICK_THUMBNAIL_REQUEST) }
        btnConfirmLive.setOnClickListener {
            pendingTitle = etTitle.text.toString(); pendingDesc = etDesc.text.toString(); pendingPrivacy = spinner.selectedItem.toString().lowercase()
            dialog.dismiss(); retryCount = 0; createYouTubeBroadcast()
        }
        dialog.show()
    }

    private fun startChatPolling(liveChatId: String) { currentLiveChatId = liveChatId; chatNextPageToken = null; chatPollingActive = true; pollChatOnce(); pollViewersOnce() }
    private fun stopChatPolling() { chatPollingActive = false; chatHandler.removeCallbacksAndMessages(null); currentLiveChatId = null; runOnUiThread { tvViewerCount.visibility = View.GONE } }

    private fun pollViewersOnce() {
        if (!chatPollingActive || currentBroadcastId == null) return
        val youtube = youtubeClient ?: return
        Thread {
            addQuota(1)
            try {
                val response = youtube.videos().list("liveStreamingDetails").setId(currentBroadcastId).execute()
                val details = response.items?.firstOrNull()?.liveStreamingDetails
                val viewers = details?.concurrentViewers?.toString() ?: "0"
                runOnUiThread { tvViewerCount.text = "👁️ $viewers"; tvViewerCount.visibility = View.VISIBLE }
            } catch (e: Exception) { e.printStackTrace() }
            if (chatPollingActive) chatHandler.postDelayed({ pollViewersOnce() }, 5000L)
        }.start()
    }

    private fun pollChatOnce() {
        if (!chatPollingActive) return
        val chatId = currentLiveChatId ?: return
        val youtube = youtubeClient ?: return
        Thread {
            addQuota(1)
            try {
                val request = youtube.liveChatMessages().list(chatId, "snippet,authorDetails")
                chatNextPageToken?.let { request.pageToken = it }
                val response = request.execute()
                chatNextPageToken = response.nextPageToken
                val newLines = response.items.orEmpty().mapNotNull { msg -> val author = msg.authorDetails?.displayName ?: "Viewer"; val text = msg.snippet?.displayMessage ?: return@mapNotNull null; "$author: $text" }
                if (newLines.isNotEmpty()) {
                    runOnUiThread {
                        tvCommentsFeed.text = (tvCommentsFeed.text.toString().lines() + newLines).takeLast(30).joinToString("\n")
                        commentsScrollView.post { commentsScrollView.fullScroll(View.FOCUS_DOWN) }
                        streamChatHistory.addAll(newLines); if (streamChatHistory.size > 50) streamChatHistory.subList(0, streamChatHistory.size - 50).clear()
                        refreshChatOverlayText(); updateSnapshot()
                    }
                }
                val youtubeDelay = response.pollingIntervalMillis ?: 5000L
                val finalDelay = if (youtubeDelay > 5000L) youtubeDelay else 5000L
                if (chatPollingActive) chatHandler.postDelayed({ pollChatOnce() }, finalDelay)
            } catch (e: Exception) {
                e.printStackTrace()
                if (chatPollingActive) chatHandler.postDelayed({ pollChatOnce() }, 10000L)
            }
        }.start()
    }

    private fun startStudioTimer() { liveStartTimeMillis = System.currentTimeMillis(); timerRunning = true; tvLiveTimer.visibility = View.VISIBLE; timerHandler.post(timerRunnable) }
    private fun stopStudioTimer() { timerRunning = false; timerHandler.removeCallbacksAndMessages(null); tvLiveTimer.visibility = View.GONE; tvLiveTimer.text = "00:00:00" }

    private fun updateSnapshot(delay: Long = 200) {
        if (!rtmpCamera.isOnPreview || overlayContainer.width == 0 || overlayContainer.height == 0 || pendingRefresh) return
        pendingRefresh = true
        overlayHandler.postDelayed({
            try {
                val newBitmap = Bitmap.createBitmap(overlayContainer.width, overlayContainer.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(newBitmap); overlayContainer.draw(canvas)
                imageFilterRender.setImage(newBitmap); imageFilterRender.setScale(100f, 100f); imageFilterRender.setPosition(0f, 0f)
                lastOverlayBitmap?.let { old -> if (!old.isRecycled) old.recycle() }
                lastOverlayBitmap = newBitmap
            } catch (e: Exception) { e.printStackTrace() }
            pendingRefresh = false
        }, delay)
    }

    private fun stopLiveStream() {
        btnGoLive.isEnabled = false; btnGoLive.text = "STOPPING..."
        Thread {
            currentBroadcastId?.let { broadcastId -> 
                try { youtubeClient?.liveBroadcasts()?.transition("complete", broadcastId, "status")?.execute() } catch (e: Exception) { e.printStackTrace() }
                removeSavedStream(broadcastId)
                currentBroadcastId = null 
            }
            try { rtmpCamera.stopStream() } catch (e: Exception) {}
            runOnUiThread {
                btnGoLive.text = "GO LIVE"; btnGoLive.isEnabled = true; btnGoLive.setBackgroundColor(Color.parseColor("#D32F2F"))
                Toast.makeText(this@MainActivity, "Stream Ended Permanently.", Toast.LENGTH_SHORT).show()
                generatedRtmpUrl = null; stopChatPolling(); stopStudioTimer()
            }
        }.start()
    }

    private fun createYouTubeBroadcast() {
        btnGoLive.text = "1/3: API..."; btnGoLive.isEnabled = false
        val finalTitle = pendingTitle.trim().ifEmpty { "Live from M.B. Live Studio" }
        val finalDesc = pendingDesc.trim().ifEmpty { "Streaming via Android App" }
        val privacyInput = pendingPrivacy
        Thread {
            addQuota(150)
            try {
                val credential = GoogleAccountCredential.usingOAuth2(this@MainActivity, listOf("https://www.googleapis.com/auth/youtube"))
                val signInAccount = GoogleSignIn.getLastSignedInAccount(this@MainActivity)
                if (signInAccount?.account != null) credential.selectedAccount = signInAccount.account else credential.selectedAccountName = connectedAccountEmail
                val youtube = YouTube.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), HttpRequestInitializer { request -> credential.initialize(request); request.connectTimeout = 10000; request.readTimeout = 10000; request.numberOfRetries = 0 }).setApplicationName("MBLiveStudio").build()
                youtubeClient = youtube
                runOnUiThread { btnGoLive.text = "2/3: ROOM..." }

                val scheduleTime = if (pendingScheduleTimeMs > 0) DateTime(pendingScheduleTimeMs) else DateTime(System.currentTimeMillis())
                val broadcastSnippet = LiveBroadcastSnippet().apply { title = finalTitle; description = finalDesc; scheduledStartTime = scheduleTime }
                val broadcastStatus = LiveBroadcastStatus().apply { privacyStatus = privacyInput; selfDeclaredMadeForKids = false }
                val broadcastContentDetails = LiveBroadcastContentDetails().apply { enableAutoStart = true; latencyPreference = "ultraLow" }
                val broadcast = youtube.liveBroadcasts().insert("snippet,status,contentDetails", LiveBroadcast().apply { snippet = broadcastSnippet; status = broadcastStatus; contentDetails = broadcastContentDetails }).execute()

                val bId = broadcast.id
                val liveChatId = broadcast.snippet?.liveChatId ?: ""
                pendingThumbnailUri?.let { uri -> try { val stream = contentResolver.openInputStream(uri); if (stream != null) { youtube.thumbnails().set(bId, InputStreamContent("image/jpeg", stream)).execute() } } catch (e: Exception) { e.printStackTrace() } }
                runOnUiThread { btnGoLive.text = "3/3: KEY..." }

                val stream2 = youtube.liveStreams().insert("snippet,cdn", LiveStream().apply { snippet = LiveStreamSnippet().apply { title = "$finalTitle - Key" }; cdn = CdnSettings().apply { ingestionType = "rtmp"; resolution = "variable"; frameRate = "variable" } }).execute()
                youtube.liveBroadcasts().bind(bId, "id,contentDetails").apply { streamId = stream2.id }.execute()

                val ingestionUrl = stream2.cdn.ingestionInfo.ingestionAddress
                var resolvedIp: String? = null
                try { val host = if (ingestionUrl.contains("b.rtmp")) "b.rtmp.youtube.com" else "a.rtmp.youtube.com"; resolvedIp = InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }?.hostAddress } catch (e: Exception) { e.printStackTrace() }
                val finalUrl = if (resolvedIp != null && ingestionUrl.contains("a.rtmp.youtube.com")) ingestionUrl.replace("a.rtmp.youtube.com", resolvedIp) + "/" + stream2.cdn.ingestionInfo.streamName else ingestionUrl.replace("a.rtmp", "b.rtmp") + "/" + stream2.cdn.ingestionInfo.streamName
                
                val shareLink = "https://youtu.be/$bId"
                saveStreamLocally(finalTitle, bId, liveChatId, finalUrl)

                runOnUiThread {
                    btnGoLive.text = "GO LIVE"
                    btnGoLive.isEnabled = true
                    showStreamReadyDialog(finalTitle, shareLink, finalUrl, bId, liveChatId)
                }
            } catch (e: Exception) { e.printStackTrace(); runOnUiThread { btnGoLive.text = "GO LIVE"; btnGoLive.isEnabled = true; Toast.makeText(this@MainActivity, "Timeout/API Error: ${e.message}", Toast.LENGTH_LONG).show() } }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_AUTHORIZATION && GoogleSignIn.hasPermissions(GoogleSignIn.getLastSignedInAccount(this), Scope("https://www.googleapis.com/auth/youtube"))) Toast.makeText(this, "Permission Granted! Tap LIVE again.", Toast.LENGTH_LONG).show()
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null) data.data?.let { uri -> addImageOverlayToScreen(MediaStore.Images.Media.getBitmap(contentResolver, uri)) }
        if (requestCode == PICK_THUMBNAIL_REQUEST && resultCode == RESULT_OK && data != null) data.data?.let { uri -> pendingThumbnailUri = uri; thumbnailPreviewImageView?.setImageURI(uri) }
        if (requestCode == SIGN_IN_REQUEST) try { val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java); connectedAccountEmail = account?.email; if (account != null) applyAccountToHeader(account) } catch (e: ApiException) { }
    }

    private fun addImageOverlayToScreen(bitmap: Bitmap) {
        val imageView = ImageView(this).apply { setImageBitmap(bitmap); layoutParams = RelativeLayout.LayoutParams(300, 300).apply { addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE) } }
        overlayContainer.addView(imageView); makeDraggableAndScalable(imageView); selectedOverlay = imageView; updateOverlayMenuButtonPosition(); updateSnapshot()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggableAndScalable(view: View) {
        val scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() { override fun onScale(detector: ScaleGestureDetector): Boolean { if (view is WebView) return false; view.scaleX *= detector.scaleFactor; view.scaleY *= detector.scaleFactor; return true } })
        var localDX = 0f; var localDY = 0f
        view.setOnTouchListener { v, event ->
            if (currentMode != "DRAG") return@setOnTouchListener false
            scaleGestureDetector.onTouchEvent(event)
            if (!scaleGestureDetector.isInProgress) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { localDX = v.x - event.rawX; localDY = v.y - event.rawY; selectedOverlay = v; updateOverlayMenuButtonPosition() }
                    MotionEvent.ACTION_MOVE -> { v.x = event.rawX + localDX; v.y = event.rawY + localDY; updateOverlayMenuButtonPosition() }
                    MotionEvent.ACTION_UP -> { updateSnapshot() }
                }
            }
            if (v is EditText) v.onTouchEvent(event)
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeStudioPanelDraggable(view: View) {
        var dX = 0f; var dY = 0f
        view.setOnTouchListener { v, event -> when (event.actionMasked) { MotionEvent.ACTION_DOWN -> { dX = v.x - event.rawX; dY = v.y - event.rawY }; MotionEvent.ACTION_MOVE -> { v.x = event.rawX + dX; v.y = event.rawY + dY } }; true }
    }

    private fun hasCameraPermissions(): Boolean = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun tryStartCameraPreview() { if (surfaceReady && hasCameraPermissions()) startCameraPreview() }
    
    private fun startCameraPreview() {
        if (!hasCameraPermissions() || rtmpCamera.isOnPreview) return
        var isSuccess = false
        val fallback = if (streamWidth >= streamHeight) listOf(Triple(streamWidth, streamHeight, streamBitrate), Triple(1280, 720, 3_000_000), Triple(854, 480, 1_500_000), Triple(640, 480, 1_000_000)) else listOf(Triple(streamWidth, streamHeight, streamBitrate), Triple(720, 1280, 3_000_000), Triple(480, 854, 1_500_000), Triple(480, 640, 1_000_000))
        for (res in fallback) { try { if (rtmpCamera.prepareVideo(res.first, res.second, 30, res.third, 2, 0)) { isSuccess = true; break } } catch (e: Exception) {} }
        if (!isSuccess) try { isSuccess = rtmpCamera.prepareVideo() } catch (e: Exception) {}
        
        var aReady = false
        if (isBluetoothMicActive) {
            try { aReady = rtmpCamera.prepareAudio(32 * 1024, 16000, false, false, false) } catch (e: Exception) {}
            if (!aReady) try { aReady = rtmpCamera.prepareAudio(16 * 1024, 8000, false, false, false) } catch (e: Exception) {}
        } else {
            val useEchoCanceler = detectedMicRoute == MicRoute.PHONE
            try { aReady = rtmpCamera.prepareAudio(128 * 1024, 44100, true, useEchoCanceler, true) } catch (e: Exception) {}
            if (!aReady) try { aReady = rtmpCamera.prepareAudio(128 * 1024, 44100, false, useEchoCanceler, true) } catch (e: Exception) {}
        }
        if (!aReady) try { aReady = rtmpCamera.prepareAudio() } catch (e: Exception) { }

        if (isSuccess && aReady) { 
            rtmpCamera.glInterface.setFilter(cameraLayoutFilter)
            rtmpCamera.glInterface.addFilter(imageFilterRender)
            rtmpCamera.startPreview()
            overlayHandler.postDelayed({ updateSnapshot() }, 1000) 
        } else { runOnUiThread { Toast.makeText(this, "CAMERA ERROR: Device encoder not supported.", Toast.LENGTH_LONG).show() } }
    }

    override fun onConnectionSuccess() { runOnUiThread { retryCount = 0; btnGoLive.text = "STOP STREAM"; btnGoLive.isEnabled = true; btnGoLive.setBackgroundColor(Color.parseColor("#E53935")); Toast.makeText(this@MainActivity, "🔥 YOU ARE LIVE!", Toast.LENGTH_LONG).show(); startStudioTimer() } }
    override fun onConnectionFailed(reason: String) {
        if (retryCount < MAX_RETRIES && generatedRtmpUrl != null) { retryCount++; runOnUiThread { btnGoLive.text = "RETRYING ($retryCount/3)..." }; Thread { Thread.sleep(2000); try { rtmpCamera.startStream(generatedRtmpUrl!!) } catch (e: Exception) {} }.start() } else { runOnUiThread { try { rtmpCamera.stopPreview() } catch (e: Exception) {}; tryStartCameraPreview(); btnGoLive.text = "GO LIVE"; btnGoLive.isEnabled = true; try { rtmpCamera.stopStream() } catch (e: Exception) {}; Toast.makeText(this@MainActivity, "RTMP TIMEOUT: $reason", Toast.LENGTH_LONG).show(); stopChatPolling(); stopStudioTimer() } }
    }
    override fun onDisconnect() { runOnUiThread { btnGoLive.text = "GO LIVE"; btnGoLive.isEnabled = true; btnGoLive.setBackgroundColor(Color.parseColor("#D32F2F")); try { rtmpCamera.stopPreview() } catch (e: Exception) {}; tryStartCameraPreview(); stopChatPolling(); stopStudioTimer() } }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { super.onRequestPermissionsResult(requestCode, permissions, grantResults); tryStartCameraPreview() }
    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { surfaceReady = true; tryStartCameraPreview() }
    
    override fun surfaceDestroyed(holder: SurfaceHolder) { 
        surfaceReady = false
        if (rtmpCamera.isStreaming) rtmpCamera.stopStream()
        if (rtmpCamera.isOnPreview) rtmpCamera.stopPreview() 
    }
    
    override fun onDestroy() {
        super.onDestroy()
        overlayHandler.removeCallbacksAndMessages(null); chatHandler.removeCallbacksAndMessages(null); timerHandler.removeCallbacksAndMessages(null); scoConnectTimeoutHandler.removeCallbacksAndMessages(null)
        tickerHandler.removeCallbacksAndMessages(null)
        scoStateReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }; scoStateReceiver = null
        try { clearBluetoothRoute() } catch (_: Exception) {}
        try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { audioDeviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) } } } catch (_: Exception) {}
        audioDeviceCallback = null; isBluetoothMicActive = false
        try { if (rtmpCamera.isStreaming) rtmpCamera.stopStream() } catch (e: Exception) {}
        try { if (rtmpCamera.isOnPreview) rtmpCamera.stopPreview() } catch (e: Exception) {}
        lastOverlayBitmap?.let { if (!it.isRecycled) it.recycle() }; lastOverlayBitmap = null
    }
    
    override fun onAuthError() {}
    override fun onAuthSuccess() {}
    override fun onConnectionStarted(url: String) {}
    override fun onNewBitrate(bitrate: Long) {}
    private fun applyCameraLayout(rect: FloatArray) { cameraLayoutFilter.setRect(rect[0], rect[1], rect[2], rect[3]); cameraLayoutFilter.setBackgroundColor(0.07f, 0.07f, 0.07f) }
    private fun sendSyntheticZoomEvent(action: Int, pointerDistance: Float, delta: Float) { val now = android.os.SystemClock.uptimeMillis(); val props = arrayOf(MotionEvent.PointerProperties(), MotionEvent.PointerProperties()); props[0].id = 0; props[1].id = 1; val coords = arrayOf(MotionEvent.PointerCoords(), MotionEvent.PointerCoords()); coords[0].x = 0f; coords[0].y = 0f; coords[1].x = pointerDistance; coords[1].y = 0f; val event = MotionEvent.obtain(now, now, action, 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0); try { rtmpCamera.setZoom(event, delta) } catch (e: Exception) {}; event.recycle() }
}
