package com.mblivestudio

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView

import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.utils.gl.ImageObject
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.encoder.input.gl.render.filters.NoFilterRender

class MainActivity : Activity(), ConnectChecker, SurfaceHolder.Callback {
    
    private lateinit var rtmpCamera: RtmpCamera2
    private lateinit var openGlView: OpenGlView
    private lateinit var btnGoLive: Button
    
    // Buttons
    private lateinit var btnTitleText: Button
    private lateinit var btnScoreboard: Button
    private lateinit var btnLogo: Button

    // Overlay States
    private var isTitleVisible = false
    private var isScoreVisible = false
    private var isLogoVisible = false

    // Master Overlay Engine
    private val imageFilter = ImageObjectFilterRender()
    private val imageObject = ImageObject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        openGlView = findViewById(R.id.surfaceView)
        btnGoLive = findViewById(R.id.btnGoLive)
        btnTitleText = findViewById(R.id.btnTitleText)
        btnScoreboard = findViewById(R.id.btnScoreboard)
        btnLogo = findViewById(R.id.btnLogo)
        
        rtmpCamera = RtmpCamera2(openGlView, this)
        openGlView.holder.addCallback(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !hasPermissions()) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
        }

        // --- BUTTON CLICKS ---
        btnTitleText.setOnClickListener {
            isTitleVisible = !isTitleVisible
            btnTitleText.text = if(isTitleVisible) "TITLE TEXT (ON)" else "TITLE TEXT (OFF)"
            refreshMasterOverlay()
        }

        btnScoreboard.setOnClickListener {
            isScoreVisible = !isScoreVisible
            btnScoreboard.text = if(isScoreVisible) "CRICKET SCORE (ON)" else "CRICKET SCORE (OFF)"
            refreshMasterOverlay()
        }

        btnLogo.setOnClickListener {
            isLogoVisible = !isLogoVisible
            btnLogo.text = if(isLogoVisible) "CHANNEL LOGO (ON)" else "CHANNEL LOGO (OFF)"
            refreshMasterOverlay()
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

    // --- MASTER OVERLAY ENGINE ---
    // यह फ़ंक्शन एक अदृश्य (Transparent) स्क्रीन बनाता है और आपके चुने हुए ऑप्शंस उस पर ड्रॉ करता है
    private fun refreshMasterOverlay() {
        if (!isTitleVisible && !isScoreVisible && !isLogoVisible) {
            rtmpCamera.glInterface.setFilter(NoFilterRender())
            return
        }

        // 1. पारदर्शी कैनवस बनाना (HD Resolution)
        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { isAntiAlias = true }

        // 2. LOGO (Top Right)
        if (isLogoVisible) {
            // अभी के लिए डिफ़ॉल्ट एंड्रॉइड आइकन यूज़ कर रहे हैं, बाद में आपकी कस्टम PNG लगा देंगे
            val logo = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
            canvas.drawBitmap(logo, 1150f, 30f, paint)
        }

        // 3. TITLE TEXT (Bottom Left)
        if (isTitleVisible) {
            paint.color = Color.YELLOW
            paint.textSize = 50f
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText("MB LIVE STUDIO", 40f, 680f, paint)
        }

        // 4. CRICKET SCOREBOARD (Bottom Right)
        if (isScoreVisible) {
            // स्कोर का बैकग्राउंड बॉक्स (Semi-transparent Black)
            paint.color = Color.parseColor("#99000000")
            canvas.drawRect(820f, 570f, 1260f, 700f, paint)
            
            // स्कोर टेक्स्ट
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.color = Color.WHITE
            paint.textSize = 45f
            canvas.drawText("IND 245/3 (40.2)", 850f, 630f, paint)
            
            paint.color = Color.YELLOW
            paint.textSize = 30f
            canvas.drawText("Target: 312 | CRR: 6.08", 850f, 675f, paint)
        }

        // 5. इस तैयार स्क्रीन को लाइव वीडियो पर ओवरले करना
        imageObject.loadBitmap(bitmap)
        imageFilter.setImageObject(imageObject)
        imageFilter.setPosition(TranslateTo.CENTER)
        imageFilter.setScale(100f, 100f) // पूरी स्क्रीन पर फिट
        
        rtmpCamera.glInterface.setFilter(imageFilter)
    }

    private fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                   checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun startCameraPreview() {
        if (!rtmpCamera.isOnPreview) {
            if (rtmpCamera.prepareAudio() && rtmpCamera.prepareVideo()) {
                rtmpCamera.startPreview()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPermissions()) startCameraPreview()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (hasPermissions()) startCameraPreview()
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
