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
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return

    var isSuccess = false
    val fallback = if (streamWidth >= streamHeight)
        listOf(Triple(streamWidth, streamHeight, streamBitrate), Triple(1280, 720, 3_000_000), Triple(854, 480, 1_500_000), Triple(640, 480, 1_000_000))
    else
        listOf(Triple(streamWidth, streamHeight, streamBitrate), Triple(720, 1280, 3_000_000), Triple(480, 854, 1_500_000), Triple(480, 640, 1_000_000))
    
    for (res in fallback) {
        try {
            // FIX: YouTube requires iFrame interval to be exactly 2 seconds.
            if (rtmpCamera.prepareVideo(res.first, res.second, streamFps, res.third, 2, 0)) {
                streamWidth = res.first; streamHeight = res.second; streamBitrate = res.third; isSuccess = true; break
            }
        } catch (e: Exception) {}
    }
    var aReady = false
    try {
        // FIX: YouTube rejects 16000Hz audio. It MUST be 44100Hz.
        aReady = rtmpCamera.prepareAudio(44100, true, 128 * 1024, true, false)
        if (!aReady) aReady = rtmpCamera.prepareAudio(44100, false, 128 * 1024, false, false)
    } catch (e: Exception) {}
    
    if (isSuccess && aReady) {
        applyCurrentMicrophoneDevice()
        cameraLayoutFilter.setRect(0f, 0f, 1f, 1f)
        cameraLayoutFilter.setBackgroundColor(0.07f, 0.07f, 0.07f)
        rtmpCamera.getGlInterface().setFilter(cameraLayoutFilter)
        
        imageFilterRender.setScale(100f, 100f)
        imageFilterRender.setPosition(0f, 0f)
        rtmpCamera.getGlInterface().addFilter(imageFilterRender)
        
        rtmpCamera.startPreview(openGlView)
        updateSnapshot(1000)
    } else {
        Toast.makeText(this, "CAMERA ERROR: Encoder not supported.", Toast.LENGTH_LONG).show()
    }
}

internal fun MainActivity.drawOverlayToStreamBitmap(bitmap: Bitmap) {
    val sourceW = overlayContainer.width.toFloat(); val sourceH = overlayContainer.height.toFloat()
    if (sourceW <= 0f || sourceH <= 0f) return
    val targetW = bitmap.width.toFloat(); val targetH = bitmap.height.toFloat()
    val scale = maxOf(targetW / sourceW, targetH / sourceH)
    val scaledW = sourceW * scale; val scaledH = sourceH * scale
    val dx = (targetW - scaledW) * 0.5f; val dy = (targetH - scaledH) * 0.5f

    val save = canvasFor(bitmap).save()
    val canvas = canvasFor(bitmap)
    canvas.translate(dx, dy); canvas.scale(scale, scale)
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
    try { (rtmpCamera.videoSource as? Camera2Source)?.setZoom(event, delta) } catch (e: Exception) {}
    event.recycle()
}

internal fun MainActivity.applyCameraLayout(rect: FloatArray) { 
    cameraLayoutFilter.setRect(rect[0], rect[1], rect[2], rect[3])
    cameraLayoutFilter.setBackgroundColor(0.07f, 0.07f, 0.07f) 
}
