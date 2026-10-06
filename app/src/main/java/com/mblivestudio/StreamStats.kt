package com.mblivestudio

import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * LIVE DIAGNOSTICS: shows when the streaming library throws away audio/video frames because the network
 * cannot keep up (that makes "tak tak" ticks). Counters are read by name, so this compiles with any library version;
 * if a counter does not exist it is simply skipped.
 */
internal object StreamStats {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var lastDroppedAudio = 0L
    private var lastDroppedVideo = 0L
    private var ticks = 0
    private var warnedMissing = false

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

    fun start(activity: MainActivity) {
        if (running) return
        running = true
        lastDroppedAudio = 0L
        lastDroppedVideo = 0L
        ticks = 0
        warnedMissing = false
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!running) return
                if (activity.generatedRtmpUrl == null) { running = false; return }   // stream was stopped
                try {
                    val cam: Any = activity.rtmpCamera
                    val da = num(cam, "getDroppedAudioFrames")
                    val dv = num(cam, "getDroppedVideoFrames")
                    val sa = num(cam, "getSentAudioFrames")
                    val sv = num(cam, "getSentVideoFrames")
                    ticks++
                    if (da == null && dv == null) {
                        if (!warnedMissing) {
                            warnedMissing = true
                            Toast.makeText(activity, "Stats: this library version does not expose frame counters", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        val deltaA = (da ?: 0L) - lastDroppedAudio
                        val deltaV = (dv ?: 0L) - lastDroppedVideo
                        lastDroppedAudio = da ?: lastDroppedAudio
                        lastDroppedVideo = dv ?: lastDroppedVideo
                        if (deltaA > 0 || deltaV > 0) {
                            Toast.makeText(
                                activity,
                                "NETWORK DROP: audio +$deltaA (total ${da ?: 0}) | video +$deltaV (total ${dv ?: 0})",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else if (ticks % 6 == 0) {
                            // every ~30 s a calm confirmation that the counters work
                            Toast.makeText(
                                activity,
                                "Stats OK: dropped audio ${da ?: 0}, video ${dv ?: 0} | sent audio ${sa ?: 0}, video ${sv ?: 0}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: Exception) { }
                handler.postDelayed(this, 5000)
            }
        }, 5000)
    }

    fun stop() { running = false }
}
