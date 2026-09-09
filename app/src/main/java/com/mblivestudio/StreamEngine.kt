package com.mblivestudio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.mblivestudio.filters.CameraLayoutFilterRender
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView

/**
 * Owns rtmpCamera and every camera/encoder/audio-routing concern that used
 * to live directly in MainActivity. Step 1 of the background-streaming
 * migration: this is a PURE EXTRACTION — no behavior changes, no Service
 * yet. MainActivity calls into this exactly where it used to call
 * rtmpCamera / the old private functions directly.
 *
 * Design note for Step 2: this class never touches UI (no Toast, no
 * Button, no findViewById) and never holds an Activity reference longer
 * than construction — only [StreamEngineCallback] talks back to whoever
 * owns it. That's deliberate: when this gets wrapped in a foreground
 * Service next, the Service can hold this exact class unchanged and just
 * forward callback events differently (e.g. via LiveData/broadcast)
 * instead of MainActivity receiving them directly.
 */
class StreamEngine(
    private val context: Context,
    private val openGlView: OpenGlView,
    connectChecker: ConnectChecker,
    private val callback: StreamEngineCallback
) : SurfaceHolder.Callback {

    enum class MicRoute { PHONE, BLUETOOTH, WIRED }

    interface StreamEngineCallback {
        fun onCameraError(message: String)
        fun onMicRouteChanged(route: MicRoute, showMessage: Boolean, message: String)
        fun onBluetoothMicResult(success: Boolean, message: String)
    }

    val rtmpCamera: RtmpCamera2 = RtmpCamera2(openGlView, connectChecker)
    private val imageFilterRender = ImageObjectFilterRender()
    private val cameraLayoutFilter = CameraLayoutFilterRender()

    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var surfaceReady = false
    var streamWidth = 1920
    var streamHeight = 1080
    var streamBitrate = 5_000_000
    private var lastAppliedZoomFactor = 1f

    private var isBluetoothMicActive = false
    private var detectedMicRoute = MicRoute.PHONE
    private var bluetoothCommunicationDevice: AudioDeviceInfo? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var scoStateReceiver: android.content.BroadcastReceiver? = null
    private val scoConnectTimeoutHandler = Handler(Looper.getMainLooper())

    init {
        openGlView.holder.addCallback(this)
        registerAudioDeviceMonitoring()
        updateDetectedMicRoute(false)
    }

    // ============================================================
    // Public control surface — MainActivity calls these
    // ============================================================

    val isOnPreview: Boolean get() = rtmpCamera.isOnPreview
    val isStreaming: Boolean get() = rtmpCamera.isStreaming

    fun startStream(url: String) = rtmpCamera.startStream(url)
    fun stopStream() { try { rtmpCamera.stopStream() } catch (_: Exception) {} }

    fun applyCameraLayout(rect: FloatArray) {
        cameraLayoutFilter.setRect(rect[0], rect[1], rect[2], rect[3])
        cameraLayoutFilter.setBackgroundColor(0.07f, 0.07f, 0.07f)
    }

    fun sendSyntheticZoomEvent(action: Int, pointerDistance: Float, delta: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties(), MotionEvent.PointerProperties())
        props[0].id = 0; props[1].id = 1
        val coords = arrayOf(MotionEvent.PointerCoords(), MotionEvent.PointerCoords())
        coords[0].x = 0f; coords[0].y = 0f
        coords[1].x = pointerDistance; coords[1].y = 0f
        val event = MotionEvent.obtain(now, now, action, 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        try { rtmpCamera.setZoom(event, delta) } catch (_: Exception) {}
        event.recycle()
    }

    fun hasCameraPermissions(): Boolean =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun tryStartCameraPreview() {
        if (surfaceReady && hasCameraPermissions()) startCameraPreview()
    }

    fun startCameraPreview() {
        if (!hasCameraPermissions() || rtmpCamera.isOnPreview) return
        var isSuccess = false
        val fallback = if (streamWidth >= streamHeight)
            listOf(Triple(streamWidth, streamHeight, streamBitrate), Triple(1280, 720, 3_000_000), Triple(854, 480, 1_500_000), Triple(640, 480, 1_000_000))
        else
            listOf(Triple(streamWidth, streamHeight, streamBitrate), Triple(720, 1280, 3_000_000), Triple(480, 854, 1_500_000), Triple(480, 640, 1_000_000))
        for (res in fallback) {
            try { if (rtmpCamera.prepareVideo(res.first, res.second, 30, res.third, 2, 0)) { isSuccess = true; break } } catch (_: Exception) {}
        }
        if (!isSuccess) try { isSuccess = rtmpCamera.prepareVideo() } catch (_: Exception) {}

        var aReady = false
        if (isBluetoothMicActive) {
            try { aReady = rtmpCamera.prepareAudio(32 * 1024, 16000, false, false, false) } catch (_: Exception) {}
            if (!aReady) {
                try { aReady = rtmpCamera.prepareAudio(16 * 1024, 8000, false, false, false) } catch (_: Exception) {}
            }
        } else {
            val useEchoCanceler = detectedMicRoute == MicRoute.PHONE
            try { aReady = rtmpCamera.prepareAudio(128 * 1024, 44100, true, useEchoCanceler, true) } catch (_: Exception) {}
            if (!aReady) {
                try { aReady = rtmpCamera.prepareAudio(128 * 1024, 44100, false, useEchoCanceler, true) } catch (_: Exception) {}
            }
        }
        if (!aReady) {
            try { aReady = rtmpCamera.prepareAudio() } catch (_: Exception) { }
        }

        if (isSuccess && aReady) {
            rtmpCamera.glInterface.setFilter(cameraLayoutFilter)
            rtmpCamera.glInterface.addFilter(imageFilterRender)
            rtmpCamera.startPreview()
        } else {
            callback.onCameraError("CAMERA ERROR: Device encoder not supported.")
        }
    }

    fun stopPreview() { try { rtmpCamera.stopPreview() } catch (_: Exception) {} }

    fun release() {
        clearBluetoothRoute()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioDeviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
            }
        } catch (_: Exception) {}
        audioDeviceCallback = null
        isBluetoothMicActive = false
        try { if (rtmpCamera.isStreaming) rtmpCamera.stopStream() } catch (_: Exception) {}
        try { if (rtmpCamera.isOnPreview) rtmpCamera.stopPreview() } catch (_: Exception) {}
    }

    // ============================================================
    // Bluetooth / mic routing — moved verbatim from MainActivity.
    // Toggle no longer takes a Button; UI feedback goes back through
    // the callback instead, so this class stays UI-free.
    // ============================================================

    private fun registerAudioDeviceMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        audioDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                updateDetectedMicRoute(true)
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                val bluetoothWasRemoved = removedDevices.any { isBluetoothInputType(it.type) }
                if (bluetoothWasRemoved && isBluetoothMicActive) {
                    isBluetoothMicActive = false
                    bluetoothCommunicationDevice = null
                    clearBluetoothRoute()
                    restartCameraForAudioChange(250)
                }
                updateDetectedMicRoute(true)
            }
        }
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
    }

    private fun isBluetoothInputType(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    private fun isWiredInputType(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
        type == AudioDeviceInfo.TYPE_USB_DEVICE ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && type == AudioDeviceInfo.TYPE_USB_HEADSET)

    private fun getInputDevices(): List<AudioDeviceInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
        return try { audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList() } catch (_: Exception) { emptyList() }
    }

    private fun findBluetoothInput(): AudioDeviceInfo? = getInputDevices().firstOrNull { isBluetoothInputType(it.type) }
    private fun findWiredInput(): AudioDeviceInfo? = getInputDevices().firstOrNull { isWiredInputType(it.type) }

    private fun updateDetectedMicRoute(showToast: Boolean) {
        val route = when {
            isBluetoothMicActive && findBluetoothInput() != null -> MicRoute.BLUETOOTH
            findWiredInput() != null -> MicRoute.WIRED
            else -> MicRoute.PHONE
        }
        val changed = route != detectedMicRoute
        detectedMicRoute = route
        if (showToast && changed) {
            val label = when (route) {
                MicRoute.BLUETOOTH -> "Bluetooth mic detected"
                MicRoute.WIRED -> "Wired/USB mic detected"
                MicRoute.PHONE -> "Phone mic active"
            }
            callback.onMicRouteChanged(route, true, label)
        } else {
            callback.onMicRouteChanged(route, false, "")
        }
    }

    /**
     * Caller (MainActivity) must already have BLUETOOTH_CONNECT granted —
     * permission REQUESTS can only be triggered from an Activity, so that
     * check/request stays outside this class on purpose.
     */
    @SuppressLint("MissingPermission")
    fun toggleBluetoothMic() {
        if (!isBluetoothMicActive) {
            val btInput = findBluetoothInput()
            if (btInput == null) {
                callback.onBluetoothMicResult(false, "Bluetooth earbuds/neckband mic not available. Connect it first.")
                updateDetectedMicRoute(false)
                return
            }
            if (routeBluetoothMic()) {
                isBluetoothMicActive = true
                bluetoothCommunicationDevice = btInput
                detectedMicRoute = MicRoute.BLUETOOTH
                callback.onBluetoothMicResult(true, "Bluetooth mic selected: ${btInput.productName}")
                restartCameraForAudioChange(300)
            } else {
                isBluetoothMicActive = false
                bluetoothCommunicationDevice = null
                callback.onBluetoothMicResult(false, "Bluetooth mic could not be selected. Phone/Wired mic remains active.")
            }
        } else {
            clearBluetoothRoute()
            isBluetoothMicActive = false
            bluetoothCommunicationDevice = null
            updateDetectedMicRoute(false)
            val fallbackName = if (findWiredInput() != null) "Wired/USB mic" else "Phone mic"
            callback.onBluetoothMicResult(true, "Bluetooth mic off — $fallbackName selected")
            restartCameraForAudioChange(250)
        }
    }

    @SuppressLint("MissingPermission")
    private fun routeBluetoothMic(): Boolean {
        return try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val communicationDevice = audioManager.availableCommunicationDevices.firstOrNull { isBluetoothInputType(it.type) } ?: return false
                val accepted = audioManager.setCommunicationDevice(communicationDevice)
                if (!accepted) return false
                Handler(Looper.getMainLooper()).postDelayed({ updateDetectedMicRoute(false) }, 150)
                true
            } else {
                if (!audioManager.isBluetoothScoAvailableOffCall) return false
                val filter = android.content.IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                        when (state) {
                            AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                                scoConnectTimeoutHandler.removeCallbacksAndMessages(null)
                                try { context.unregisterReceiver(this) } catch (_: Exception) {}
                                scoStateReceiver = null
                                restartCameraForAudioChange(150)
                            }
                            AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                                scoConnectTimeoutHandler.removeCallbacksAndMessages(null)
                                try { context.unregisterReceiver(this) } catch (_: Exception) {}
                                scoStateReceiver = null
                                isBluetoothMicActive = false
                                bluetoothCommunicationDevice = null
                                callback.onBluetoothMicResult(false, "Bluetooth mic disconnected.")
                                updateDetectedMicRoute(false)
                            }
                        }
                    }
                }
                scoStateReceiver = receiver
                context.registerReceiver(receiver, filter)
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true

                scoConnectTimeoutHandler.postDelayed({
                    scoStateReceiver?.let {
                        try { context.unregisterReceiver(it) } catch (_: Exception) {}
                        scoStateReceiver = null
                        isBluetoothMicActive = false
                        bluetoothCommunicationDevice = null
                        try { audioManager.stopBluetoothSco() } catch (_: Exception) {}
                        try { audioManager.isBluetoothScoOn = false } catch (_: Exception) {}
                        try { audioManager.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}
                        updateDetectedMicRoute(false)
                        callback.onBluetoothMicResult(false, "Bluetooth mic connection timed out.")
                    }
                }, 8000)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun clearBluetoothRoute() {
        scoStateReceiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) {} }
        scoStateReceiver = null
        scoConnectTimeoutHandler.removeCallbacksAndMessages(null)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
    }

    private fun restartCameraForAudioChange(delayMs: Long) {
        if (!rtmpCamera.isOnPreview || rtmpCamera.isStreaming) return
        try { rtmpCamera.stopPreview() } catch (_: Exception) {}
        Handler(Looper.getMainLooper()).postDelayed({ tryStartCameraPreview() }, delayMs)
    }

    // ============================================================
    // SurfaceHolder.Callback — was on MainActivity, now owned here
    // since this class owns the openGlView reference.
    // ============================================================

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
}
