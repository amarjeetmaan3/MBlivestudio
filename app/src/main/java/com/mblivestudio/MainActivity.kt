package com.mblivestudio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView
import com.pedro.encoder.input.gl.render.filters.AndroidViewFilterRender

class MainActivity : ComponentActivity(), ConnectChecker, SurfaceHolder.Callback {
    
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
    private lateinit var btnSwitchCamera: Button
    private lateinit var btnGoLive: Button

    // जो आइटम (टेक्स्ट/लोगो) स्क्रीन पर सेलेक्टेड होगा, वो यहाँ सेव होगा
    private var selectedOverlay: View? = null

    // गैलरी से फोटो चुनने का लॉजिक
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, it)
            addImageOverlayToScreen(bitmap)
        }
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
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        btnGoLive = findViewById(R.id.btnGoLive)
        
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
        webOverlay.settings.mediaPlaybackRequiresUserGesture = false
        webOverlay.webViewClient = WebViewClient()
        webOverlay.webChromeClient = WebChromeClient()

        // --- SIDE PANEL LIVE EDITING LOGIC ---
        
        // 1. Text Overlay Editor
        etControlText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (selectedOverlay is TextView && selectedOverlay != scoreMainText && selectedOverlay != scoreSubText) {
                    (selectedOverlay as TextView).text = s.toString()
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // 2. Scoreboard Live Editor
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

        // --- BUTTON ACTIONS ---

        btnAddText.setOnClickListener {
            val text = etControlText.text.toString().trim()
            if (text.isNotEmpty()) {
                addTextOverlayToScreen(text)
                etControlText.text.clear() // Clear box for next input
            } else {
                Toast.makeText(this, "Please type some text first", Toast.LENGTH_SHORT).show()
            }
        }

        btnAddLogo.setOnClickListener {
            pickImageLauncher.launch("image/*") // ओपन गैलरी
        }

        btnRemoveSelected.setOnClickListener {
            selectedOverlay?.let {
                if (it != dragScoreboard) { // स्कोरबोर्ड को डिलीट नहीं करेंगे, सिर्फ हाईड करेंगे
                    overlayContainer.removeView(it)
                    selectedOverlay = null
                    Toast.makeText(this, "Item Removed", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnToggleScore.setOnClickListener {
            val isVis = dragScoreboard.visibility == View.VISIBLE
            dragScoreboard.visibility = if(isVis) View.GONE else View.VISIBLE
            btnToggleScore.text = if(isVis) "SHOW SCORECARD ON SCREEN" else "HIDE SCORECARD"
        }

        btnSwitchCamera.setOnClickListener {
            rtmpCamera.switchCamera()
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

    // --- DYNAMIC CREATION ENGINES ---

    private fun addTextOverlayToScreen(text: String) {
        val textView = TextView(this).apply {
            this.text = text
            setTextColor(Color.YELLOW)
            textSize = 30f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE)
            }
        }
        overlayContainer.addView(textView)
        makeDraggableAndScalable(textView)
        selectedOverlay = textView // नया टेक्स्ट तुरंत सेलेक्ट हो जाएगा
    }

    private fun addImageOverlayToScreen(bitmap: Bitmap) {
        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            layoutParams = RelativeLayout.LayoutParams(250, 250).apply {
                addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE)
            }
        }
        overlayContainer.addView(imageView)
        makeDraggableAndScalable(imageView)
        selectedOverlay = imageView
    }

    // --- DRAG, ZOOM (SCALE) AND SELECT LOGIC ---
    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggableAndScalable(view: View) {
        val scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                view.scaleX *= scaleFactor
                view.scaleY *= scaleFactor
                return true
            }
        })

        var dX = 0f; var dY = 0f
        view.setOnTouchListener { v, event ->
            // पिंच-टू-ज़ूम चेक करें
            scaleGestureDetector.onTouchEvent(event)
            
            // अगर ज़ूम नहीं हो रहा, तो ड्रैग (मूव) करें
            if (!scaleGestureDetector.isInProgress) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dX = v.x - event.rawX
                        dY = v.y - event.rawY
                        
                        // टच करते ही आइटम 'Select' हो जाएगा
                        selectedOverlay = v
                        if (v is TextView && v != dragScoreboard) {
                            etControlText.setText(v.text) // साइड पैनल बॉक्स में उसका टेक्स्ट आ जाएगा
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
            if (rtmpCamera.prepareAudio() && rtmpCamera.prepareVideo()) rtmpCamera.startPreview()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startCameraPreview()
    }
    
    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { startCameraPreview() }
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
