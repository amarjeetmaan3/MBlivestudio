package com.mblivestudio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast

internal fun MainActivity.tryStartCameraPreview() {
    if (!surfaceReady || rtmpCamera.isOnPreview || MainActivity.globalPrivacyMode) return
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

    var isSuccess = false
    val displayRotation = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    val isPortrait = displayRotation == android.view.Surface.ROTATION_0 || displayRotation == android.view.Surface.ROTATION_180
    
    val maxBase = maxOf(streamWidth, streamHeight)
    val minBase = minOf(streamWidth, streamHeight)
    
    // डाइनैमिक रिज़ॉल्यूशन: अगर सीधा है तो Portrait (720x1280), अगर टेढ़ा है तो Landscape (1280x720)
    val targetWidth = if (isPortrait) minBase else maxBase
    val targetHeight = if (isPortrait) maxBase else minBase
    
    // डाइनैमिक एंगल: कैमरा मैट्रिक्स को UI के हिसाब से रोटेट करना
    val rotation = when (displayRotation) {
        android.view.Surface.ROTATION_0 -> 90
        android.view.Surface.ROTATION_90 -> 0
        android.view.Surface.ROTATION_180 -> 270
        android.view.Surface.ROTATION_270 -> 180
        else -> 0
    }

    // फॉलबैक भी डाइनैमिक हो गया ताकि घुमाने पर वीडियो स्ट्रेच न हो
    val fallback = listOf(
        Triple(targetWidth, targetHeight, streamBitrate),
        Triple(if (isPortrait) 720 else 1280, if (isPortrait) 1280 else 720, 3_000_000),
        Triple(if (isPortrait) 480 else 854, if (isPortrait) 854 else 480, 1_500_000),
        Triple(if (isPortrait) 480 else 640, if (isPortrait) 640 else 480, 1_000_000)
    )

    for (res in fallback) {
        val fpsCandidates = if (streamFps == 30) intArrayOf(30) else intArrayOf(streamFps, 30)
        for (fps in fpsCandidates) {
            try {
                if (rtmpCamera.prepareVideo(res.first, res.second, fps, res.third, 2, rotation)) {
                    streamWidth = res.first
                    streamHeight = res.second
                    streamBitrate = res.third
                    streamFps = fps
                    isSuccess = true
                    break
                }
            } catch (e: Exception) { e.printStackTrace() }
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
            updateSnapshot(0)
        } catch (e: Exception) {
            e.printStackTrace()
            try { rtmpCamera.stopPreview() } catch (_: Exception) {}
        }
    }
}

internal fun MainActivity.drawOverlayToStreamBitmap(bitmap: Bitmap) {
    val sourceW = overlayContainer.width.toFloat()
    val sourceH = overlayContainer.height.toFloat()
    if (sourceW <= 0f || sourceH <= 0f) return

    val targetW = bitmap.width.toFloat()
    val targetH = bitmap.height.toFloat()

    val fillScale = maxOf(sourceW / targetW, sourceH / targetH)
    val xOffset = (sourceW - targetW * fillScale) / 2f
    val yOffset = (sourceH - targetH * fillScale) / 2f

    val save = canvasFor(bitmap).save()
    val canvas = canvasFor(bitmap)
    
    canvas.scale(1f / fillScale, 1f / fillScale)
    canvas.translate(-xOffset, -yOffset)
    
    if (MainActivity.globalPrivacyMode) {
        canvas.drawColor(Color.parseColor("#121212")) 
        MainActivity.globalSlateBitmap?.let { slate ->
            val scale = maxOf(sourceW / slate.width, sourceH / slate.height)
            val sw = slate.width * scale
            val sh = slate.height * scale
            val sx = (sourceW - sw) / 2f
            val sy = (sourceH - sh) / 2f
            val destRect = android.graphics.RectF(sx, sy, sx + sw, sy + sh)
            canvas.drawBitmap(slate, null, destRect, null)
        }
    } else {
        if (isBackgrounded || !surfaceReady) {
            canvas.drawColor(Color.parseColor("#121212"))
        }
        overlayContainer.draw(canvas)
    }
    
    canvas.restoreToCount(save)
}

internal fun MainActivity.canvasFor(bitmap: Bitmap): Canvas {
    return if (bitmap === bitmapA) canvasA!! else canvasB!!
}

internal fun MainActivity.updateSnapshot(delay: Long = 0) {
    if ((!rtmpCamera.isOnPreview && !rtmpCamera.isStreaming && !MainActivity.globalPrivacyMode) || overlayContainer.width == 0 || overlayContainer.height == 0) return
    
    val action = Runnable {
        try {
            val w = streamWidth.coerceAtLeast(1)
            val h = streamHeight.coerceAtLeast(1)
            useBufferA = !useBufferA
            val currentBitmap = if (useBufferA) {
                if (bitmapA == null || bitmapA!!.isRecycled || bitmapA!!.width != w || bitmapA!!.height != h) {
                    bitmapA?.recycle(); bitmapA = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); canvasA = Canvas(bitmapA!!)
                }
                bitmapA!!
            } else {
                if (bitmapB == null || bitmapB!!.isRecycled || bitmapB!!.width != w || bitmapB!!.height != h) {
                    bitmapB?.recycle(); bitmapB = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); canvasB = Canvas(bitmapB!!)
                }
                bitmapB!!
            }
            currentBitmap.eraseColor(Color.TRANSPARENT)
            drawOverlayToStreamBitmap(currentBitmap)
            imageFilterRender.setImage(currentBitmap)
        } catch (e: Exception) { e.printStackTrace() }
    }
    
    if (delay > 0) {
        Handler(Looper.getMainLooper()).postDelayed(action, delay)
    } else {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else Handler(Looper.getMainLooper()).post(action)
    }
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
