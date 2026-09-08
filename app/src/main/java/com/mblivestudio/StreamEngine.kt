package com.mblivestudio

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.view.Surface
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import io.github.thibaultbee.streampack.core.interfaces.setCameraId
import io.github.thibaultbee.streampack.core.interfaces.startPreview
import io.github.thibaultbee.streampack.core.interfaces.startStream
import io.github.thibaultbee.streampack.core.interfaces.stopPreview
import io.github.thibaultbee.streampack.core.streamers.single.AudioConfig
import io.github.thibaultbee.streampack.core.streamers.single.SingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.VideoConfig
import io.github.thibaultbee.streampack.core.streamers.single.cameraSingleStreamer
import io.github.thibaultbee.streampack.core.streamers.single.setConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class StreamEngine(private val context: Context) {

    var streamer: SingleStreamer? = null
        private set

    // जादुई EGL फैक्ट्री यहाँ जुड़ गई है
    val overlayFactory = OverlayCompositor.Factory()

    private var currentCameraId: String = ""
    private var isFront: Boolean = false

    suspend fun initializeCamera(
        videoConfig: EngineVideoConfig,
        targetRotation: Int? = null
    ) {
        closeCurrentStreamer()

        val cameraId = defaultBackCameraId()
            ?: throw IllegalStateException("No back camera found")

        currentCameraId = cameraId
        isFront = false

        // StreamPack को तुम्हारी कस्टम EGL सरफेस फैक्ट्री दे दी गई है
        val newStreamer = cameraSingleStreamer(
            context = context,
            cameraId = cameraId,
            surfaceProcessorFactory = overlayFactory
        )

        targetRotation?.let {
            newStreamer.setTargetRotation(it)
        }

        val audioConfig = AudioConfig(
            startBitrate = 128_000,
            sampleRate = 44_100,
            channelConfig = AudioFormat.CHANNEL_IN_STEREO
        )

        val videoStreamConfig = VideoConfig(
            startBitrate = videoConfig.bitrateBps,
            resolution = android.util.Size(
                videoConfig.width,
                videoConfig.height
            ),
            fps = videoConfig.fps
        )

        newStreamer.setConfig(
            audioConfig,
            videoStreamConfig
        )

        streamer = newStreamer
        awaitCameraSource()
    }

    fun updateOverlay(bitmap: Bitmap?) {
        overlayFactory.setOverlayBitmap(bitmap)
    }

    suspend fun startCameraPreview(surface: Surface) {
        val s = streamer ?: throw IllegalStateException("Streamer is not initialized")
        s.startPreview(surface)
        awaitCameraSource()
    }

    suspend fun stopCameraPreview() {
        runCatching { streamer?.stopPreview() }
    }

    suspend fun goLive(rtmpUrl: String) {
        val s = streamer ?: throw IllegalStateException("Streamer is not initialized")
        if (rtmpUrl.isBlank()) throw IllegalArgumentException("RTMP URL is empty")
        s.startStream(rtmpUrl)
    }

    suspend fun stopLive() {
        runCatching { streamer?.stopStream() }
    }

    suspend fun flipCamera(): Boolean {
        val s = streamer ?: return isFront
        val nextId = if (isFront) defaultBackCameraId() else defaultFrontCameraId()
        if (nextId == null) return isFront

        return try {
            s.setCameraId(nextId)
            currentCameraId = nextId
            isFront = !isFront
            awaitCameraSource()
            isFront
        } catch (_: Exception) {
            isFront
        }
    }

    fun muteAudio(muted: Boolean) {
        try {
            val audioSettings = streamer?.javaClass?.getMethod("getAudioSettings")?.invoke(streamer)
            audioSettings?.javaClass?.getMethod("setMuted", Boolean::class.javaPrimitiveType)?.invoke(audioSettings, muted)
        } catch (_: Exception) {}
    }

    suspend fun awaitCameraSource(timeoutMs: Long = 5_000): ICameraSource? {
        val s = streamer ?: return null
        return withTimeoutOrNull(timeoutMs) {
            s.videoInput.sourceFlow.filterNotNull().filterIsInstance<ICameraSource>().first()
        }
    }

    suspend fun isTorchAvailableAsync(): Boolean {
        val source = awaitCameraSource() ?: return false
        return source.settings.flash.isAvailable
    }

    suspend fun setTorch(enabled: Boolean) {
        val source = awaitCameraSource() ?: throw IllegalStateException("Camera source is not ready")
        if (!source.settings.flash.isAvailable) throw IllegalStateException("Flashlight is not available on this camera")
        source.settings.flash.setIsEnable(enabled)
    }

    fun isFrontCamera(): Boolean = isFront

    private suspend fun closeCurrentStreamer() {
        runCatching { streamer?.stopPreview() }
        runCatching { streamer?.stopStream() }
        runCatching { streamer?.release() }
        streamer = null
    }

    suspend fun release() {
        closeCurrentStreamer()
    }

    fun close() {
        runBlocking(Dispatchers.Default) { release() }
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
