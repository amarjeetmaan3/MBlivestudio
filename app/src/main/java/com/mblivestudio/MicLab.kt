package com.mblivestudio

import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * MIC LAB - finds out where the "tak tak" comes from.
 * It records with Android's own AudioRecord (NO streaming library involved) for every combination of
 * microphone source and sample rate, and measures:
 *  - lost audio (ms)      : audio the device failed to deliver
 *  - max gap (ms)         : the longest delay between two reads (a late mic thread)
 *  - ticks / dropouts     : sudden spikes and runs of digital silence in the recording
 * Tap a result row to listen to that recording.
 */
internal object MicLab {

    private class Case(val label: String, val source: Int, val rate: Int)
    private class Result(
        val c: Case, val ok: Boolean, val lossMs: Long, val maxGapMs: Long,
        val ticks: Int, val dropouts: Int, val peakPercent: Int, val pcm: ShortArray?, val note: String
    )

    @Volatile private var running = false
    @Volatile private var dialogOpen = false
    private var currentTrack: AudioTrack? = null

    fun show(activity: MainActivity) {
        if (activity.rtmpCamera.isStreaming) {
            Toast.makeText(activity, "Stop the stream first, then open Mic Lab", Toast.LENGTH_LONG).show()
            return
        }
        if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(activity, "Microphone permission is needed", Toast.LENGTH_LONG).show()
            return
        }
        val density = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * density).toInt()

        val am = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val info = TextView(activity).apply {
            text = "${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} | native rate " +
                    "${am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)} Hz | unprocessed mic: " +
                    "${am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)}"
            textSize = 12f
            setTextColor(Color.DKGRAY)
        }
        val status = TextView(activity).apply {
            text = "Be in a quiet room. Press Start and keep talking normally (count 1 to 20) for about 45 seconds."
            textSize = 14f
            setPadding(0, px(8), 0, px(8))
        }
        val results = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val start = Button(activity).apply { text = "Start test" }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(8), px(16), px(8))
            addView(info); addView(start); addView(status); addView(results)
        }
        val scroll = ScrollView(activity).apply { addView(root) }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Mic Lab")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnDismissListener {
            dialogOpen = false
            try { currentTrack?.stop(); currentTrack?.release() } catch (e: Exception) {}
            currentTrack = null
        }
        dialogOpen = true
        dialog.show()

        start.setOnClickListener {
            if (running) return@setOnClickListener
            running = true
            results.removeAllViews()
            val sources = listOf(
                "Default" to MediaRecorder.AudioSource.DEFAULT,
                "Mic" to MediaRecorder.AudioSource.MIC,
                "Camcorder" to MediaRecorder.AudioSource.CAMCORDER,
                "Voice recognition" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
                "Unprocessed" to MediaRecorder.AudioSource.UNPROCESSED
            )
            val cases = ArrayList<Case>()
            for (rate in intArrayOf(48000, 44100)) {
                for ((name, src) in sources) cases.add(Case("$name @ ${rate / 1000.0} kHz", src, rate))
            }
            Thread {
                for ((i, c) in cases.withIndex()) {
                    if (!dialogOpen) break
                    activity.runOnUiThread { status.text = "Testing ${i + 1}/${cases.size}: ${c.label} ... keep talking" }
                    val r = record(c, 4)
                    activity.runOnUiThread { if (dialogOpen) results.addView(row(activity, r)) }
                }
                running = false
                activity.runOnUiThread {
                    status.text = "Done. Tap a row to listen. Then send me a screenshot of this list."
                }
            }.start()
        }
    }

    private fun row(activity: MainActivity, r: Result): TextView {
        val density = activity.resources.displayMetrics.density
        val clean = r.ok && r.lossMs <= 80 && r.maxGapMs <= 60 && r.dropouts == 0
        return TextView(activity).apply {
            text = if (!r.ok) "X  ${r.c.label}\n    ${r.note}"
            else "\u25B6  ${r.c.label}\n    lost ${r.lossMs} ms | max gap ${r.maxGapMs} ms | ticks ${r.ticks} | dropouts ${r.dropouts} | peak ${r.peakPercent}%"
            textSize = 13f
            setTextColor(if (!r.ok) Color.GRAY else if (clean) Color.parseColor("#2E7D32") else Color.parseColor("#E65100"))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
            if (r.ok) setOnClickListener { play(activity, r) }
        }
    }

    private fun record(c: Case, seconds: Int): Result {
        val minBuf = AudioRecord.getMinBufferSize(c.rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return Result(c, false, 0, 0, 0, 0, 0, null, "rate not supported")
        val bufBytes = maxOf(minBuf * 2, c.rate / 5 * 2)
        var rec: AudioRecord? = null
        try {
            rec = try {
                AudioRecord(c.source, c.rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes)
            } catch (e: Exception) { null }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                return Result(c, false, 0, 0, 0, 0, 0, null, "not available on this device")
            }
            val total = c.rate * seconds
            val pcm = ShortArray(total)
            val chunk = ShortArray(c.rate / 50)   // 20 ms per read
            var got = 0
            var lastEnd = 0L
            var maxGap = 0L
            rec.startRecording()
            val t0 = SystemClock.elapsedRealtime()
            while (got < total && dialogOpen && SystemClock.elapsedRealtime() - t0 < seconds * 1000L + 3000L) {
                val n = rec.read(chunk, 0, chunk.size)
                val now = SystemClock.elapsedRealtime()
                if (n < 0) break
                if (n > 0) {
                    if (lastEnd != 0L) { val gap = now - lastEnd; if (gap > maxGap) maxGap = gap }
                    lastEnd = now
                    val take = minOf(n, total - got)
                    System.arraycopy(chunk, 0, pcm, got, take)
                    got += take
                }
            }
            val elapsed = SystemClock.elapsedRealtime() - t0
            try { rec.stop() } catch (e: Exception) {}
            if (got < total / 2) return Result(c, false, 0, 0, 0, 0, 0, null, "no audio delivered")

            val lossMs = maxOf(0L, elapsed - seconds * 1000L - 120L)   // 120 ms start-up tolerance
            var ticks = 0
            var dropouts = 0
            var peak = 0
            var meanDiff = 0.0
            var lastTick = -100000
            var zeroRun = 0
            val from = minOf(got, c.rate / 3)           // skip the first 0.3 s (start-up)
            for (i in from + 1 until got) {
                val a = pcm[i].toInt()
                val b = pcm[i - 1].toInt()
                val d = abs(a - b)
                if (abs(a) > peak) peak = abs(a)
                if (a == 0) {
                    zeroRun++
                } else {
                    if (zeroRun >= 16) dropouts++
                    zeroRun = 0
                }
                if (d > 1200 && d > 10 * meanDiff + 400 && i - lastTick > 480) { ticks++; lastTick = i }
                meanDiff += (d - meanDiff) * 0.01
            }
            return Result(c, true, lossMs, maxGap, ticks, dropouts, peak * 100 / 32767, pcm.copyOf(got), "")
        } catch (e: Exception) {
            return Result(c, false, 0, 0, 0, 0, 0, null, "error: ${e.message}")
        } finally {
            try { rec?.release() } catch (e: Exception) {}
        }
    }

    private fun play(activity: MainActivity, r: Result) {
        val pcm = r.pcm ?: return
        try {
            try { currentTrack?.stop(); currentTrack?.release() } catch (e: Exception) {}
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(r.c.rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            currentTrack = track
            Toast.makeText(activity, "Playing: ${r.c.label}", Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({
                try { if (currentTrack === track) { track.stop(); track.release(); currentTrack = null } } catch (e: Exception) {}
            }, pcm.size * 1000L / r.c.rate + 400L)
        } catch (e: Exception) {
            Toast.makeText(activity, "Could not play: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
