package com.mblivestudio

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
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

    val requestedFps = streamFps
    val fallback = listOf(
        Triple(encWidth, encHeight, streamBitrate),
        Triple(1280, 720, minOf(streamBitrate, 4_000_000)),
        Triple(854, 480, 1_500_000),
        Triple(640, 480, 1_000_000)
    )
    var usedTier = 0

    for ((tier, res) in fallback.withIndex()) {
        val fpsCandidates = if (streamFps == 30) intArrayOf(30) else intArrayOf(streamFps, 30)
        for (fps in fpsCandidates) {
            try {
                // keyframe every 2 seconds (YouTube recommendation)
                if (rtmpCamera.prepareVideo(res.first, res.second, fps, res.third, 2, rotation)) {
                    streamWidth = if (isPortrait) res.second else res.first
                    streamHeight = if (isPortrait) res.first else res.second
                    streamBitrate = res.third
                    streamFps = fps
                    usedTier = tier
                    isSuccess = true
                    break
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (isSuccess) break
    }

    // Never lower quality silently: tell the user what the device actually gave.
    if (isSuccess && (usedTier > 0 || streamFps != requestedFps)) {
        Toast.makeText(this, "Quality reduced by device limit: ${if (isPortrait) streamHeight else streamWidth}x${if (isPortrait) streamWidth else streamHeight} @ ${streamFps}fps, ${streamBitrate / 1_000_000.0} Mbps", Toast.LENGTH_LONG).show()
    }

    if (!isSuccess) {
        try { isSuccess = rtmpCamera.prepareVideo() } catch (e: Exception) { e.printStackTrace() }
    }

    // AUDIO: clean sample rate + the microphone's own noise suppressor and echo canceller.
    // - 44100 Hz is natively supported by phone/tablet audio hardware (32000 had to be resampled -> crackle)
    // - 128 kbps AAC mono = YouTube's recommended audio bitrate
    // - Bluetooth (call-grade) mics only deliver 16 kHz, so keep 16 kHz / 64 kbps there
    var aReady = false
    val isBluetooth = detectedMicRoute == MicRoute.BLUETOOTH

    val sampleRate = if (isBluetooth) 16000 else 44100
    val audioBitrate = if (isBluetooth) 64 * 1024 else 128 * 1024

    // 1) best: clean rate + noise suppressor + echo canceller
    try { aReady = rtmpCamera.prepareAudio(audioBitrate, sampleRate, false, true, true) } catch (_: Exception) {}
    // 2) same rate without the hardware effects (some devices refuse them)
    if (!aReady) {
        try { aReady = rtmpCamera.prepareAudio(audioBitrate, sampleRate, false, false, false) } catch (_: Exception) {}
    }
    // 3) old safe settings
    if (!aReady) {
        try { aReady = rtmpCamera.prepareAudio(64 * 1024, 16000, false, false, false) } catch (_: Exception) {}
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

// SMART RENDER ENGINE v2
// Goals: (1) moving overlays (ticker, lower third, web graphics) move at an EVEN pace,
//        (2) the device never gets overloaded by overlay drawing - audio & video come first.
// How: frames are scheduled on a fixed clock (start-to-start, not "after the last one finished"),
//      the overlay thread runs at a slightly lower priority, and the frame rate steps down
//      automatically when drawing gets slow (30 -> 24 -> 20 -> 15 -> 10 fps) and back up when it is easy again.
private val OVERLAY_INTERVALS_MS = longArrayOf(33L, 42L, 50L, 67L, 100L)   // 30, 24, 20, 15, 10 fps
@Volatile private var overlayLevel = 0
@Volatile private var overlayCostAvgMs = 0f
@Volatile private var overlayEasyStreak = 0
@Volatile private var overlayLastWasEmpty = false

internal fun MainActivity.updateSnapshot(delay: Long = 100) {
    if (!rtmpCamera.isOnPreview) return
    if (pendingRefresh) { refreshQueued = true; return }
    pendingRefresh = true

    val smartDelay = if (isBackgrounded) maxOf(delay, 1000L) else delay

    // Executing the heavy bitmap creation on a separate, lower-priority background thread
    backgroundOverlayHandler.postDelayed({
        val frameStart = SystemClock.uptimeMillis()
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

            // Nothing on screen and the last frame was already empty -> no work at all
            val isEmpty = overlayContainer.childCount == 0
            if (!(isEmpty && overlayLastWasEmpty)) {
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
                drawOverlayToStreamBitmap(targetBitmap) // heavy drawing happens off the main thread
                imageFilterRender.setImage(targetBitmap)
                overlayLastWasEmpty = isEmpty
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            pendingRefresh = false

            // ---- adapt the frame rate to how expensive drawing is on THIS device ----
            val cost = (SystemClock.uptimeMillis() - frameStart).toFloat()
            overlayCostAvgMs = if (overlayCostAvgMs == 0f) cost else overlayCostAvgMs * 0.8f + cost * 0.2f
            val interval = OVERLAY_INTERVALS_MS[overlayLevel]
            if (overlayCostAvgMs > interval * 0.7f && overlayLevel < OVERLAY_INTERVALS_MS.size - 1) {
                overlayLevel++            // too slow -> fewer, evenly spaced frames
                overlayEasyStreak = 0
            } else if (overlayCostAvgMs < interval * 0.3f) {
                overlayEasyStreak++
                if (overlayEasyStreak >= 90 && overlayLevel > 0) { overlayLevel--; overlayEasyStreak = 0 }   // plenty of room -> smoother again
            } else {
                overlayEasyStreak = 0
            }

            // ---- do we need another frame? (moving things: web overlay, lower-third ticker) ----
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

            if (refreshQueued || (needsContinuousLoop && !isBackgrounded)) {
                refreshQueued = false
                // fixed clock: next frame starts one interval after THIS frame started
                val elapsed = SystemClock.uptimeMillis() - frameStart
                updateSnapshot(maxOf(2L, OVERLAY_INTERVALS_MS[overlayLevel] - elapsed))
            }
        }
    }, smartDelay)
}

internal fun MainActivity.applyCameraLayout(rect: FloatArray) { 
    cameraLayoutFilter.setRect(rect[0], rect[1], rect[2], rect[3])
    cameraLayoutFilter.setBackgroundColor(0.07f, 0.07f, 0.07f) 
}
