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

    // PHASE 1 FIX: Hardware-Synced Audio Setup (Anti-Crackling)
    var aReady = false
    val isBluetooth = detectedMicRoute == MicRoute.BLUETOOTH
    val useSystemFilters = detectedMicRoute == MicRoute.PHONE
    
    if (isBluetooth) {
        // Bluetooth (SCO) hardware limit is typically 8kHz/16kHz. 
        try { aReady = rtmpCamera.prepareAudio(128 * 1024, 16000, false, false, false) } catch (_: Exception) {}
    } else {
        // Phone/Wired Mic: Native hardware rate on modern Android is 48000 Hz.
        try { aReady = rtmpCamera.prepareAudio(192 * 1024, 48000, false, useSystemFilters, useSystemFilters) } catch (_: Exception) {}
        
        // Safe Fallback
        if (!aReady) {
            try { aReady = rtmpCamera.prepareAudio(192 * 1024, 44100, false, useSystemFilters, useSystemFilters) } catch (_: Exception) {}
        }
    }

    // Ultimate fallback for older devices
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

// PHASE 2 FIX: Smart Render Engine (Fixes Video Pause & Overlay Stutter)
internal fun MainActivity.updateSnapshot(delay: Long = 100) {
    if (!rtmpCamera.isOnPreview) return
    if (pendingRefresh) { refreshQueued = true; return }
    pendingRefresh = true
    
    val smartDelay = if (isBackgrounded) maxOf(delay, 1000L) else delay

    overlayHandler.postDelayed({
        try {
            val w = streamWidth.coerceAtLeast(1)
            val h = streamHeight.coerceAtLeast(1)
            
            if (isBackgrounded) {
                val cw = if (overlayContainer.width > 0) overlayContainer.width else w
                val ch = if (overlayContainer.height > 0) overlayContainer.height else h
                
                overlayContainer.measure(
                    View.MeasureSpec.makeMeasureSpec(cw, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(ch, View.MeasureSpec.EXACTLY)
                )
                overlayContainer.layout(0, 0, cw, ch)
                overlayContainer.invalidate() 
            } else if (overlayContainer.width == 0 || overlayContainer.height == 0) {
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
            drawOverlayToStreamBitmap(targetBitmap)
            imageFilterRender.setImage(targetBitmap)
        } catch (e: Exception) { 
            e.printStackTrace() 
        } finally {
            pendingRefresh = false
            
            // Auto-detect dynamic overlays
            var needsContinuousLoop = false
            for (i in 0 until overlayContainer.childCount) {
                val tag = overlayContainer.getChildAt(i).tag
                if (tag == "WEB_OVERLAY" || tag == "LOWER_THIRD") {
                    needsContinuousLoop = true
                    break
                }
            }

            // The 40ms CPU Breather (Max 25 FPS constraint)
            // Ensures the Camera Encoder never starves and video never pauses on YouTube.
            if (refreshQueued) {
                refreshQueued = false
                updateSnapshot(40) 
            } else if (needsContinuousLoop && !isBackgrounded) {
                updateSnapshot(40)
            }
        }
    }, smartDelay)
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
