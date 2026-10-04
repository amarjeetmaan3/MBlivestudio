package com.mblivestudio

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.widget.Toast

internal fun MainActivity.tryStartCameraPreview() {
    if (!surfaceReady || rtmpCamera.isOnPreview) return
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

    var isSuccess = false

    val isPortrait = streamHeight > streamWidth
    
    val encWidth = if (isPortrait) streamHeight else streamWidth
    val encHeight = if (isPortrait) streamWidth else streamHeight
    val rotation = if (isPortrait) 90 else 0

    val fallback = listOf(
        Triple(encWidth, encHeight, streamBitrate),
        Triple(1280, 720, 3_000_000),
        Triple(854, 480, 1_500_000),
        Triple(640, 480, 1_000_000)
    )

    for (res in fallback) {
        val fpsCandidates = if (streamFps == 30) intArrayOf(30) else intArrayOf(streamFps, 30)
        for (fps in fpsCandidates) {
            try {
                if (rtmpCamera.prepareVideo(res.first, res.second, fps, res.third, 2, rotation)) {
                    streamWidth = if (isPortrait) res.second else res.first
                    streamHeight = if (isPortrait) res.first else res.second
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

    // BULLETPROOF AUDIO FIX: 
    // strictly Mono (false) and correct sample rates to prevent hardware artifacts[cite: 3]
    var aReady = false
    val isBluetooth = detectedMicRoute == MicRoute.BLUETOOTH
    
    val sampleRate = if (isBluetooth) 16000 else 32000
    val audioBitrate = 64 * 1024 // 64kbps is extremely stable for Mono voice
    
    try { 
        aReady = rtmpCamera.prepareAudio(audioBitrate, sampleRate, false, false, false) 
    } catch (_: Exception) {}
    
    if (!aReady) {
        try { aReady = rtmpCamera.prepareAudio(audioBitrate, 16000, false, false, false) } catch (_: Exception) {}
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

    val fillScale = maxOf(sourceW / targetW, sourceH / targetH)
    val xOffset = (sourceW - targetW * fillScale) / 2f
    val yOffset = (sourceH - targetH * fillScale) / 2f

    val save = canvasFor(bitmap).save()
    val canvas = canvasFor(bitmap)
    
    canvas.scale(1f / fillScale, 1f / fillScale)
    canvas.translate(-xOffset, -yOffset)
    
    overlayContainer.draw(canvas)
    canvas.restoreToCount(save)
}

internal fun MainActivity.canvasFor(bitmap: Bitmap): Canvas {
    return if (bitmap === bitmapA) canvasA!! else canvasB!!
}

// SMART RENDER ENGINE: Shifted to Background Thread to save CPU & Audio Buffer[cite: 3]
internal fun MainActivity.updateSnapshot(delay: Long = 100) {
    if (!rtmpCamera.isOnPreview) return
    if (pendingRefresh) { refreshQueued = true; return }
    pendingRefresh = true
    
    val smartDelay = if (isBackgrounded) maxOf(delay, 1000L) else delay

    // Executing the heavy bitmap creation on a completely separate background thread
    backgroundOverlayHandler.postDelayed({
        try {
            val w = streamWidth.coerceAtLeast(1)
            val h = streamHeight.coerceAtLeast(1)
            
            if (isBackgrounded) {
                // UI measurements must briefly ping the main thread
                runOnUiThread {
                    try {
                        val cw = if (overlayContainer.width > 0) overlayContainer.width else w
                        val ch = if (overlayContainer.height > 0) overlayContainer.height else h
                        overlayContainer.measure(
                            View.MeasureSpec.makeMeasureSpec(cw, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(ch, View.MeasureSpec.EXACTLY)
                        )
                        overlayContainer.layout(0, 0, cw, ch)
                    } catch (e: Exception) {}
                }
            } else if (overlayContainer.width == 0 || overlayContainer.height == 0) {
                pendingRefresh = false
                return@postDelayed 
            }

            useBufferA = !useBufferA
            val targetBitmap = if (useBufferA) {
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
            
            targetBitmap.eraseColor(Color.TRANSPARENT)
            drawOverlayToStreamBitmap(targetBitmap) // Heavy drawing happens off the Main Thread!
            imageFilterRender.setImage(targetBitmap)
        } catch (e: Exception) { 
            e.printStackTrace() 
        } finally {
            pendingRefresh = false
            
            var needsContinuousLoop = false
            try {
                for (i in 0 until overlayContainer.childCount) {
                    val tag = overlayContainer.getChildAt(i)?.tag
                    if (tag == "WEB_OVERLAY" || tag == "LOWER_THIRD") {
                        needsContinuousLoop = true
                        break
                    }
                }
            } catch (e: Exception) {}

            if (refreshQueued) {
                refreshQueued = false
                updateSnapshot(40) 
            } else if (needsContinuousLoop && !isBackgrounded) {
                updateSnapshot(40)
            }
        }
    }, smartDelay)
}

internal fun MainActivity.applyCameraLayout(rect: FloatArray) { 
    cameraLayoutFilter.setRect(rect[0], rect[1], rect[2], rect[3])
    cameraLayoutFilter.setBackgroundColor(0.07f, 0.07f, 0.07f) 
}
