package com.mblivestudio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.view.MotionEvent
import android.view.Surface
import androidx.core.content.ContextCompat
import io.github.thibaultbee.streampack.core.interfaces.setCameraId
import io.github.thibaultbee.streampack.core.interfaces.startPreview
import io.github.thibaultbee.streampack.core.interfaces.startStream
import io.github.thibaultbee.streampack.core.interfaces.stopPreview
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig
import io.github.thibaultbee.streampack.core.streamers.single.cameraSingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.setConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

interface EngineCallback {
    fun onConnectionSuccess()
    fun onConnectionFailed(reason: String)
    fun onDisconnect()
    fun onMicStatusChanged(message: String, isSuccess: Boolean)
}

class StreamEngine(
    private val context: Context,
    private val openGlView: Any? = null,
    private var callback: EngineCallback? = null
) {
    var streamer: SingleStreamer? = null
        private set

    val overlayFactory = OverlayCompositor.Factory()

    private var currentCameraId: String = ""
    private var isFront: Boolean = false

    var isStreamingActive = false
    var isPreviewActive = false
    private var isMicMuted = false

    inner class RtmpCameraBridge {
        val isStreaming: Boolean get() = isStreamingActive
        val isOnPreview: Boolean get() = isPreviewActive
        
        fun stopStream() {
            isStreamingActive = false
            CoroutineScope(Dispatchers.IO).launch { runCatching { streamer?.stopStream() } }
            callback?.onDisconnect()
        }
        
        fun stopPreview() {
            isPreviewActive = false
            CoroutineScope(Dispatchers.IO).launch { runCatching { streamer?.stopPreview() } }
        }
        
        fun startStream(url: String) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    goLive(url)
                    isStreamingActive = true
                    callback?.onConnectionSuccess()
                } catch (e: Exception) {
                    callback?.onConnectionFailed(e.message ?: "Stream error")
                }
            }
        }
    }

    val rtmpCamera = RtmpCameraBridge()

    fun stopStream() { rtmpCamera.stopStream() }
    fun startStream(url: String) { rtmpCamera.startStream(url) }
    fun stopPreview() { rtmpCamera.stopPreview() }

    fun hasCameraPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    fun tryStartCameraPreview(previewSurface: Surface? = null) {
        if (!hasCameraPermissions() || isPreviewActive) {
            callback?.onConnectionFailed("Camera or Mic permission missing")
            return
        }
        
        val actualSurface = previewSurface ?: (openGlView as? android.view.SurfaceView)?.holder?.surface
        
        if (actualSurface == null) {
            callback?.onConnectionFailed("Target Surface is null")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                initializeCamera(EngineVideoConfig(1280, 720, 30, 3000000))
                startCameraPreview(actualSurface)
                isPreviewActive = true
            } catch (e: Exception) {
                callback?.onConnectionFailed("Preview Error: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun switchCamera() { CoroutineScope(Dispatchers.IO).launch { flipCamera() } }
    
    fun toggleBluetoothMic() {
        isMicMuted = !isMicMuted
        muteAudio(isMicMuted)
        callback?.onMicStatusChanged(if (isMicMuted) "Mic Muted" else "Mic Active", true)
    }
    
    fun setZoom(event: MotionEvent) {}
    fun setOverlayBitmap(bitmap: Bitmap) { updateOverlay(bitmap) }
    fun detachCallback() { callback = null }
    fun release() { close() }

    suspend fun initializeCamera(videoConfig: EngineVideoConfig, targetRotation: Int? = null) {
        closeCurrentStreamer()

        val cameraId = defaultBackCameraId() ?: throw IllegalStateException("No back camera found")
        currentCameraId = cameraId
        isFront = false

        val newStreamer = cameraSingleStreamer(
            context = context,
            cameraId = cameraId,
            surfaceProcessorFactory = overlayFactory
        )

        targetRotation?.let { newStreamer.setTargetRotation(it) }

        val audioConfig = AudioConfig(startBitrate = 128_000, sampleRate = 44_100, channelConfig = AudioFormat.CHANNEL_IN_STEREO)
        val videoStreamConfig = VideoConfig(startBitrate = videoConfig.bitrateBps, resolution = android.util.Size(videoConfig.width, videoConfig.height), fps = videoConfig.fps)

        newStreamer.setConfig(audioConfig, videoStreamConfig)
        streamer = newStreamer
    }

    fun updateOverlay(bitmap: Bitmap?) {
        overlayFactory.setOverlayBitmap(bitmap)
    }

    suspend fun startCameraPreview(surface: Surface) {
        val s = streamer ?: throw IllegalStateException("Streamer is not initialized")
        s.startPreview(surface)
    }

    suspend fun goLive(rtmpUrl: String) {
        val s = streamer ?: throw IllegalStateException("Streamer is not initialized")
        if (rtmpUrl.isBlank()) throw IllegalArgumentException("RTMP URL is empty")
        s.startStream(rtmpUrl)
    }

    suspend fun flipCamera(): Boolean {
        val s = streamer ?: return isFront
        val nextId = if (isFront) defaultBackCameraId() else defaultFrontCameraId()
        if (nextId == null) return isFront

        return try {
            s.stopPreview() // Fixes camera freeze
            s.setCameraId(nextId)
            currentCameraId = nextId
            isFront = !isFront
            
            val surface = (openGlView as? android.view.SurfaceView)?.holder?.surface
            if (surface != null) s.startPreview(surface)
            
            isFront
        } catch (e: Exception) {
            e.printStackTrace()
            isFront
        }
    }

    fun muteAudio(muted: Boolean) {
        try {
            val audioSettings = streamer?.javaClass?.getMethod("getAudioSettings")?.invoke(streamer)
            audioSettings?.javaClass?.getMethod("setMuted", Boolean::class.javaPrimitiveType)?.invoke(audioSettings, muted)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun closeCurrentStreamer() {
        runCatching { streamer?.stopPreview() }
        runCatching { streamer?.stopStream() }
        runCatching { streamer?.release() }
        streamer = null
    }

    fun close() {
        runBlocking(Dispatchers.Default) { closeCurrentStreamer() }
    }

    private fun defaultBackCameraId(): String? = findCameraId(CameraCharacteristics.LENS_FACING_BACK)
    private fun defaultFrontCameraId(): String? = findCameraId(CameraCharacteristics.LENS_FACING_FRONT)

    private fun findCameraId(facing: Int): String? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
        }
    }
}

data class EngineVideoConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int
)
