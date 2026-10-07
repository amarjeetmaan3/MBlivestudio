package com.mblivestudio

import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * LIVE DIAGNOSTICS (stays on screen while you are live, so you can read it or screenshot it).
 * Shows whether the streaming library has to THROW AWAY audio/video frames because the network cannot keep up
 * (that is what makes "tak tak" ticks), plus the target bitrate versus what is really being sent.
 * Counters are read by name, so this compiles with any library version; a missing counter just shows "?".
 */
internal object StreamStats {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile var lastBitrateBps = 0L          // fed by onNewBitrate (bits actually sent in the last second)

    private var hud: TextView? = null
    private var startedAt = 0L
    private var lastDroppedAudio = 0L
    private var lastDroppedVideo = 0L
    private val events = ArrayDeque<String>()
    private var lastSentAudio = -1L
    private var lastSentVideo = -1L
    private var lastSampleAt = 0L

    private fun call(target: Any?, name: String): Any? {
        if (target == null) return null
        return try {
            val m = target.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() } ?: return null
            m.invoke(target)
        } catch (e: Exception) { null }
    }

    private fun num(cam: Any, name: String): Long? {
        val direct = call(cam, name)
        val v = direct ?: call(call(cam, "getStreamClient"), name)
        return (v as? Number)?.toLong()
    }

    private fun q(v: Long?): String = v?.toString() ?: "?"

    /** number of audio frames the sender has sent so far (null if the library does not expose it) */
    fun sentAudio(cam: Any): Long? = num(cam, "getSentAudioFrames")

    private fun ensureHud(activity: MainActivity): TextView {
        val existing = hud
        if (existing != null && existing.parent != null) return existing
        val density = activity.resources.displayMetrics.density
        val tv = TextView(activity).apply {
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
        }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (64 * density).toInt()
        }
        activity.addContentView(tv, lp)
        hud = tv
        return tv
    }

    private fun removeHud() {
        val tv = hud ?: return
        (tv.parent as? ViewGroup)?.removeView(tv)
        hud = null
    }

    fun start(activity: MainActivity) {
        if (running) return
        running = true
        startedAt = SystemClock.elapsedRealtime()
        lastDroppedAudio = 0L
        lastDroppedVideo = 0L
        events.clear()
        lastSentAudio = -1L
        lastSentVideo = -1L
        lastSampleAt = 0L
        handler.post(object : Runnable {
            override fun run() {
                if (!running) { removeHud(); return }
                if (activity.generatedRtmpUrl == null) { running = false; removeHud(); return }   // stream was stopped
                try {
                    val cam: Any = activity.rtmpCamera
                    val da = num(cam, "getDroppedAudioFrames")
                    val dv = num(cam, "getDroppedVideoFrames")
                    val sa = num(cam, "getSentAudioFrames")
                    val sv = num(cam, "getSentVideoFrames")
                    val items = num(cam, "getItemsInCache")
                    val cache = num(cam, "getCacheSize")

                    val deltaA = (da ?: 0L) - lastDroppedAudio
                    val deltaV = (dv ?: 0L) - lastDroppedVideo
                    lastDroppedAudio = da ?: lastDroppedAudio
                    lastDroppedVideo = dv ?: lastDroppedVideo
                    if (deltaA > 0 || deltaV > 0) {
                        val s = (SystemClock.elapsedRealtime() - startedAt) / 1000
                        events.addFirst(String.format("%02d:%02d  audio +%d  video +%d", s / 60, s % 60, deltaA, deltaV))
                        while (events.size > 4) events.removeLast()
                    }

                    val target = activity.streamBitrate / 1_000_000.0
                    val sent = lastBitrateBps / 1_000_000.0
                    val sb = StringBuilder()
                    sb.append(String.format("BITRATE target %.1f | sent %.1f Mbps\n", target, sent))
                    sb.append("DROPPED audio ${q(da)} | video ${q(dv)}\n")
                    sb.append("SENT    audio ${q(sa)} | video ${q(sv)}")
                    // real frame rate over the last 2 seconds (audio should be ~47/s, video should match your fps setting)
                    val nowMs = SystemClock.elapsedRealtime()
                    if (sa != null && sv != null && lastSentAudio >= 0 && nowMs > lastSampleAt) {
                        val dt = (nowMs - lastSampleAt) / 1000.0
                        sb.append(String.format("\nFPS     video %.1f | audio %.1f", (sv - lastSentVideo) / dt, (sa - lastSentAudio) / dt))
                    }
                    if (sa != null && sv != null) { lastSentAudio = sa; lastSentVideo = sv; lastSampleAt = nowMs }
                    if (items != null && cache != null) sb.append("\nQUEUE   $items / $cache")
                    sb.append("\n").append(AudioEngine.statusLine())
                    if (events.isNotEmpty()) {
                        sb.append("\nLAST DROPS (time since live):")
                        for (e in events) sb.append("\n  ").append(e)
                    }
                    ensureHud(activity).text = sb.toString()
                } catch (e: Exception) { }
                handler.postDelayed(this, 2000)
            }
        })
    }

    fun stop() { running = false }
}
