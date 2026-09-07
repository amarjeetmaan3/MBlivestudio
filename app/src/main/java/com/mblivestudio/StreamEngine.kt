package com.mblivestudio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender

interface EngineCallback {
    fun onConnectionSuccess()
    fun onConnectionFailed(reason: String)
    fun onDisconnect()
    fun onMicStatusChanged(message: String, isSuccess: Boolean)
}

class StreamEngine(
    private val context: Context,
    private val openGlView: OpenGlView,
    private var callback: EngineCallback?
) : ConnectChecker, SurfaceHolder.Callback {

    val rtmpCamera: RtmpCamera2 = RtmpCamera2(openGlView, this)
    private val imageFilterRender = ImageObjectFilterRender()
    private val cameraLayoutFilter = com.mblivestudio.filters.CameraLayoutFilterRender()

    private var audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioDeviceCallback: AudioDeviceCallback? = null
    var isBluetoothMicActive = false
    private var scoStateReceiver: android.content.BroadcastReceiver? = null
    private val scoConnectTimeoutHandler = Handler(Looper.getMainLooper())
    var surfaceReady = false

    init {
        openGlView.holder.addCallback(this)
        registerAudioDeviceMonitoring()
    }

    // --- SAFETY PATCH: Prevent Memory Leaks on App Switch ---
    fun detachCallback() {
        callback = null
    }

    fun hasCameraPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    fun tryStartCameraPreview(width: Int = 1280, height: Int = 720, bitrate: Int = 3_000_000) {
        if (!surfaceReady || !hasCameraPermissions() || rtmpCamera.isOnPreview) return

        var isSuccess = false
        try {
            if (rtmpCamera.prepareVideo(width, height, 30, bitrate, 2, 0)) isSuccess = true
        } catch (e: Exception) {}

        if (!isSuccess) try { isSuccess = rtmpCamera.prepareVideo() } catch (e: Exception) {}

        var aReady = false
        try {
            val useEchoCanceler = !isBluetoothMicActive
            aReady = rtmpCamera.prepareAudio(128 * 1024, 44100, true, useEchoCanceler, true)
        } catch (e: Exception) {}

        if (!aReady) try { aReady = rtmpCamera.prepareAudio() } catch (e: Exception) {}

        if (isSuccess && aReady) {
            rtmpCamera.glInterface.setFilter(cameraLayoutFilter)
            rtmpCamera.glInterface.addFilter(imageFilterRender)
            rtmpCamera.startPreview()
        } else {
            callback?.onMicStatusChanged("CAMERA ERROR: Encoder failed.", false)
        }
    }

    fun setOverlayBitmap(bitmap: Bitmap) {
        if (!rtmpCamera.isOnPreview) return
        imageFilterRender.setImage(bitmap)
        imageFilterRender.setScale(100f, 100f)
        imageFilterRender.setPosition(0f, 0f)
    }

    fun applyCameraLayout(rect: FloatArray) {
        cameraLayoutFilter.setRect(rect[0], rect[1], rect[2], rect[3])
        cameraLayoutFilter.setBackgroundColor(0.07f, 0.07f, 0.07f)
    }

    fun setZoom(event: MotionEvent) {
        try { rtmpCamera.setZoom(event) } catch (e: Exception) {}
    }

    fun switchCamera() {
        try { rtmpCamera.switchCamera() } catch (e: Exception) {}
    }

    fun startStream(url: String) {
        rtmpCamera.startStream(url)
    }

    fun stopStream() {
        if (rtmpCamera.isStreaming) rtmpCamera.stopStream()
    }

    fun stopPreview() {
        if (rtmpCamera.isOnPreview) rtmpCamera.stopPreview()
    }

    fun setVideoBitrateOnFly(bitrate: Int) {
        if (rtmpCamera.isStreaming) try { rtmpCamera.setVideoBitrateOnFly(bitrate) } catch (e: Exception) {}
    }

    // --- BLUETOOTH AUDIO ENGINE ---
    private fun registerAudioDeviceMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        audioDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (removedDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } && isBluetoothMicActive) {
                    isBluetoothMicActive = false
                    clearBluetoothRoute()
                    restartCameraForAudioChange(250)
                }
            }
        }
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
    }

    fun toggleBluetoothMic() {
        if (!isBluetoothMicActive) {
            if (routeBluetoothMic()) {
                isBluetoothMicActive = true
                callback?.onMicStatusChanged("Bluetooth mic selected", true)
                restartCameraForAudioChange(300)
            } else {
                callback?.onMicStatusChanged("Bluetooth mic failed", false)
            }
        } else {
            clearBluetoothRoute()
            isBluetoothMicActive = false
            callback?.onMicStatusChanged("Bluetooth mic off", true)
            restartCameraForAudioChange(250)
        }
    }

    @SuppressLint("MissingPermission")
    private fun routeBluetoothMic(): Boolean {
        return try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            true
        } catch (e: Exception) { false }
    }

    private fun clearBluetoothRoute() {
        try {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {}
    }

    private fun restartCameraForAudioChange(delayMs: Long) {
        if (!rtmpCamera.isOnPreview || rtmpCamera.isStreaming) return
        try { rtmpCamera.stopPreview() } catch (e: Exception) {}
        Handler(Looper.getMainLooper()).postDelayed({ tryStartCameraPreview() }, delayMs)
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioDeviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        }
        clearBluetoothRoute()
        stopStream()
        stopPreview()
    }

    // --- SURFACE CALLBACKS ---
    override fun surfaceCreated(holder: SurfaceHolder) {}
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceReady = true
        tryStartCameraPreview()
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        stopStream()
        stopPreview()
    }

    // --- RTMP CALLBACKS ---
    override fun onConnectionSuccess() { callback?.onConnectionSuccess() }
    override fun onConnectionFailed(reason: String) { callback?.onConnectionFailed(reason) }
    override fun onDisconnect() { callback?.onDisconnect() }
    override fun onAuthError() {}
    override fun onAuthSuccess() {}
    override fun onConnectionStarted(url: String) {}
    override fun onNewBitrate(bitrate: Long) { setVideoBitrateOnFly(bitrate.toInt()) }
}
