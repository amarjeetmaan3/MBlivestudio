package com.mblivestudio

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.widget.Toast
import com.pedro.encoder.input.sources.video.Camera2Source

internal fun MainActivity.tryStartCameraPreview() {
    if (!surfaceReady || rtmpCamera.isOnPreview) return
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

    var isSuccess = false

    val fallback = if (streamWidth >= streamHeight) {
        listOf(
            Triple(streamWidth, streamHeight, streamBitrate),
            Triple(1280, 720, 3_000_000),
            Triple(854, 480, 1_500_000),
            Triple(640, 480, 1_000_000)
        )
    } else {
        listOf(
            Triple(streamWidth, streamHeight, streamBitrate),
            Triple(720, 1280, 3_000_000),
            Triple(480, 854, 1_500_000),
            Triple(480, 640, 1_000_000)
        )
    }

    for (res in fallback) {
        val fpsCandidates = if (streamFps == 30) intArrayOf(30) else intArrayOf(streamFps, 30)
        for (fps in fpsCandidates) {
            try {
                if (rtmpCamera.prepareVideo(res.first, res.second, fps, res.third, 2, 0)) {
                    streamWidth = res.first
                    streamHeight = res.second
                    streamBitrate = res.third
                    streamFps = fps
                    isSuccess = true
                    break
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (isSuccess) break
    }

    if (!isSuccess) {
        try { isSuccess = rtmpCamera.prepareVideo() } catch (e: Exception) { e.printStackTrace() }
    }

    var aReady = false
    if (isBluetoothMicActive) {
        try { aReady = rtmpCamera.prepareAudio(64 * 1024, 16000, false, false, false) } catch (_: Exception) {}
        if (!aReady) try { aReady = rtmpCamera.prepareAudio(64 * 1024, 32000, false, false, false) } catch (_: Exception) {}
        if (!aReady) try { aReady = rtmpCamera.prepareAudio(64 * 1024, 44100, false, false, false) } catch (_: Exception) {}
    } else {
        val useEchoCanceler = detectedMicRoute == MicRoute.PHONE
        try { aReady = rtmpCamera.prepareAudio(64 * 1024, 44100, false, useEchoCanceler, true) } catch (_: Exception) {}
        if (!aReady) try { aReady = rtmpCamera.prepareAudio(64 * 1024, 32000, false, useEchoCanceler, true) } catch (_: Exception) {}
        if (!aReady) try { aReady = rtmpCamera.prepareAudio(64 * 1024, 44100, false, false, false) } catch (_: Exception) {}
    }
    if (!aReady) {
        try { aReady = rtmpCamera.prepareAudio() } catch (e: Exception) { e.printStackTrace() }
    }

    if (isSuccess && aReady) {
        applyCurrentMicrophoneDevice()
        cameraLayoutFilter.setRect(0f, 0f, 1f, 1f)
        cameraLayoutFilter.setBackgroundColor(0.07f, 0.07f, 0.07f)
        rtmpCamera.getGlInterface().setFilter(cameraLayoutFilter)

        imageFilterRender.setScale(100f, 100f)
        imageFilterRender.setPosition(0f, 0f)
        rtmpCamera.getGlInterface().addFilter(imageFilterRender)

        try {
            rtmpCamera.startPreview()
            updateSnapshot(1000)
        } catch (e: Exception) {
            e.printStackTrace()
            try { rtmpCamera.stopPreview() } catch (_: Exception) {}
            Toast.makeText(this, "CAMERA ERROR: ${e.message ?: "Preview failed"}", Toast.LENGTH_LONG).show()
        }
    } else {
        Toast.makeText(this, "CAMERA ERROR: Device encoder not supported.", Toast.LENGTH_LONG).show()
    }
}

internal fun MainActivity.drawOverlayToStreamBitmap(bitmap: Bitmap) {
    val sourceW = overlayContainer.width.toFloat()
    val sourceH = overlayContainer.height.toFloat()
    if (sourceW <= 0f || sourceH <= 0f) return

    val targetW = bitmap.width.toFloat()
    val targetH = bitmap.height.toFloat()

    // 100% "Full Fill View" Fix: 
    // यह कैलकुलेशन पता लगाती है कि OpenGL ने वीडियो को पूरी स्क्रीन पर भरने के लिए कितना ज़ूम किया है।
    val fillScale = maxOf(sourceW / targetW, sourceH / targetH)
    
    // यह पता लगाता है कि स्क्रीन से बाहर वीडियो का कितना हिस्सा कट (Crop) रहा है।
    val xOffset = (sourceW - targetW * fillScale) / 2f
    val yOffset = (sourceH - targetH * fillScale) / 2f

    val save = canvasFor(bitmap).save()
    val canvas = canvasFor(bitmap)
    
    // अब हम कैनवास को बिल्कुल उतना ही रिवर्स-स्केल और शिफ्ट कर देंगे ताकि वीडियो के अंदर
    // ग्राफ़िक ठीक उसी जगह छपे जहाँ वो आपकी स्क्रीन पर है।
    canvas.scale(1f / fillScale, 1f / fillScale)
    canvas.translate(-xOffset, -yOffset)
    
    overlayContainer.draw(canvas)
    canvas.restoreToCount(save)
}

internal fun MainActivity.canvasFor(bitmap: Bitmap): Canvas {
    return if (bitmap === bitmapA) canvasA!! else canvasB!!
}

internal fun MainActivity.updateSnapshot(delay: Long = 100) {
    if (!rtmpCamera.isOnPreview || overlayContainer.width == 0 || overlayContainer.height == 0) return
    if (pendingRefresh) { refreshQueued = true; return }
    pendingRefresh = true
    overlayHandler.postDelayed({
        try {
            val w = streamWidth.coerceAtLeast(1)
            val h = streamHeight.coerceAtLeast(1)
            useBufferA = !useBufferA
            if (useBufferA) {
                if (bitmapA == null || bitmapA!!.isRecycled || bitmapA!!.width != w || bitmapA!!.height != h) {
                    bitmapA?.recycle(); bitmapA = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); canvasA = Canvas(bitmapA!!)
                }
                bitmapA!!.eraseColor(Color.TRANSPARENT); drawOverlayToStreamBitmap(bitmapA!!); imageFilterRender.setImage(bitmapA!!)
            } else {
                if (bitmapB == null || bitmapB!!.isRecycled || bitmapB!!.width != w || bitmapB!!.height != h) {
                    bitmapB?.recycle(); bitmapB = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); canvasB = Canvas(bitmapB!!)
                }
                bitmapB!!.eraseColor(Color.TRANSPARENT); drawOverlayToStreamBitmap(bitmapB!!); imageFilterRender.setImage(bitmapB!!)
            }
        } catch (e: Exception) { e.printStackTrace() } finally {
            pendingRefresh = false
            if (refreshQueued) { refreshQueued = false; updateSnapshot(0) }
        }
    }, delay)
}

internal fun MainActivity.sendSyntheticZoomEvent(action: Int, pointerDistance: Float, delta: Float) {
    val now = android.os.SystemClock.uptimeMillis()
    val props = arrayOf(MotionEvent.PointerProperties(), MotionEvent.PointerProperties()); props[0].id = 0; props[1].id = 1
    val coords = arrayOf(MotionEvent.PointerCoords(), MotionEvent.PointerCoords()); coords[0].x = 0f; coords[0].y = 0f; coords[1].x = pointerDistance; coords[1].y = 0f
    val event = MotionEvent.obtain(now, now, action, 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    try { rtmpCamera.setZoom(event, delta) } catch (e: Exception) {}
    event.recycle()
}

internal fun MainActivity.applyCameraLayout(rect: FloatArray) { 
    cameraLayoutFilter.setRect(rect[0], rect[1], rect[2], rect[3])
    cameraLayoutFilter.setBackgroundColor(0.07f, 0.07f, 0.07f) 
}
