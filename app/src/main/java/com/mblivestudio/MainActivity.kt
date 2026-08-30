package com.mblivestudio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView

// अब पूरी एंड्रॉइड स्क्रीन को ही फिल्टर में बदल देंगे
import com.pedro.encoder.input.gl.render.filters.AndroidViewFilterRender

class MainActivity : Activity(), ConnectChecker, SurfaceHolder.Callback {
    
    private lateinit var rtmpCamera: RtmpCamera2
    private lateinit var openGlView: OpenGlView
    private lateinit var overlayContainer: RelativeLayout
    private lateinit var viewFilterRender: AndroidViewFilterRender

    // Overlays
    private lateinit var dragLogo: TextView
    private lateinit var dragText: TextView
    private lateinit var dragScoreboard: LinearLayout
    private lateinit var scoreMainText: TextView
    private lateinit var scoreSubText: TextView

    // Buttons
    private lateinit var btnTitleText: Button
    private lateinit var btnScoreboard: Button
    private lateinit var btnLogo: Button
    private lateinit var btnGoLive: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        openGlView = findViewById(R.id.surfaceView)
        overlayContainer = findViewById(R.id.overlayContainer)
        
        dragLogo = findViewById(R.id.dragLogo)
        dragText = findViewById(R.id.dragText)
        dragScoreboard = findViewById(R.id.dragScoreboard)
        scoreMainText = findViewById(R.id.scoreMainText)
        scoreSubText = findViewById(R.id.scoreSubText)

        btnTitleText = findViewById(R.id.btnTitleText)
        btnScoreboard = findViewById(R.id.btnScoreboard)
        btnLogo = findViewById(R.id.btnLogo)
        btnGoLive = findViewById(R.id.btnGoLive)
        
        rtmpCamera = RtmpCamera2(openGlView, this)
        openGlView.holder.addCallback(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !hasPermissions()) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
        }

        // मास्टर फिल्टर स्टार्ट (जो पूरे Container को लाइव स्ट्रीम पर भेजेगा)
        viewFilterRender = AndroidViewFilterRender()
        viewFilterRender.view = overlayContainer
        rtmpCamera.glInterface.setFilter(viewFilterRender)

        // टच, ड्रैग और एडिट लॉजिक
        makeMovableAndEditable(dragText, "Edit Title") { newText -> dragText.text = newText }
        makeScoreboardMovableAndEditable(dragScoreboard)
        makeMovableAndEditable(dragLogo, "Edit Logo Text") { newText -> dragLogo.text = newText }

        // बटन्स टॉगल
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

        btnLogo.setOnClickListener {
            val isVis = dragLogo.visibility == View.VISIBLE
            dragLogo.visibility = if(isVis) View.GONE else View.VISIBLE
            btnLogo.text = if(isVis) "SHOW LOGO" else "HIDE LOGO"
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

    // --- DRAG & DOUBLE TAP TO EDIT LOGIC ---
    @SuppressLint("ClickableViewAccessibility")
    private fun makeMovableAndEditable(view: TextView, title: String, onUpdate: (String) -> Unit) {
        var dX = 0f; var dY = 0f; var lastTouchTime = 0L

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                    
                    val clickTime = System.currentTimeMillis()
                    // अगर यूजर ने 300ms के अंदर दोबारा टैप किया, तो एडिट खुलेगा
                    if (clickTime - lastTouchTime < 300) {
                        showEditDialog(title, view.text.toString(), onUpdate)
                    }
                    lastTouchTime = clickTime
                }
                MotionEvent.ACTION_MOVE -> {
                    v.animate().x(event.rawX + dX).y(event.rawY + dY).setDuration(0).start()
                }
            }
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeScoreboardMovableAndEditable(view: View) {
        var dX = 0f; var dY = 0f; var lastTouchTime = 0L

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                    
                    val clickTime = System.currentTimeMillis()
                    if (clickTime - lastTouchTime < 300) {
                        showScoreEditDialog()
                    }
                    lastTouchTime = clickTime
                }
                MotionEvent.ACTION_MOVE -> {
                    v.animate().x(event.rawX + dX).y(event.rawY + dY).setDuration(0).start()
                }
            }
            true
        }
    }

    private fun showEditDialog(title: String, currentText: String, onUpdate: (String) -> Unit) {
        val input = EditText(this)
        input.setText(currentText)
        
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Update") { _, _ -> onUpdate(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showScoreEditDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }
        
        val editMain = EditText(this).apply { setText(scoreMainText.text) }
        val editSub = EditText(this).apply { setText(scoreSubText.text) }
        
        layout.addView(TextView(this).apply { text = "Main Score (ex: IND 245/3)" })
        layout.addView(editMain)
        layout.addView(TextView(this).apply { text = "Sub Info (ex: Target: 312)" })
        layout.addView(editSub)

        AlertDialog.Builder(this)
            .setTitle("Update Scoreboard")
            .setView(layout)
            .setPositiveButton("Update") { _, _ ->
                scoreMainText.text = editMain.text.toString()
                scoreSubText.text = editSub.text.toString()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
            if (rtmpCamera.prepareAudio() && rtmpCamera.prepareVideo()) rtmpCamera.startPreview()
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
