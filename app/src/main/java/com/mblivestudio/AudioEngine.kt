package com.mblivestudio

import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.widget.Toast
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.tanh

/**
 * MBLiveStudio AUDIO ENGINE (beta)
 *
 * Why: the library's own microphone loop hands the encoder irregular chunks with timestamps taken "whenever it
 * happened to run". On some devices that shows up as periodic ticks ("tak tak") even though the microphone itself is clean.
 *
 * What it does:
 *   1. Captures with Android's AudioRecord on its OWN thread running at "urgent audio" priority, with a big (>= 250 ms) buffer.
 *   2. Cuts the audio into exact AAC frames (1024 samples) and stamps every frame from the SAMPLE COUNT
 *      (smooth, drift-corrected against the system clock) instead of "now".
 *   3. Cleans it a little: 80 Hz high-pass (removes rumble) and a soft limiter (no harsh clipping).
 *   4. Hands the PCM to the library's AAC encoder, so encoding, muxing and RTMP stay exactly as before.
 *
 * Safety: everything that touches the library is done by NAME (reflection). If anything is missing the engine stays off
 * and the library's normal microphone keeps working. A watchdog hands audio back to the library automatically if
 * no audio arrives at the sender, or if it arrives doubled.
 */
internal object AudioEngine {

    /** Set by CameraPipeline once the library audio is prepared. 0 = unknown, engine stays off. */
    @Volatile var preparedRate = 0
    @Volatile var enabled = false
    @Volatile var muted = false

    @Volatile private var running = false
    @Volatile private var status = "off"
    @Volatile private var fed = 0L
    @Volatile private var maxGapMs = 0L
    @Volatile private var driftMs = 0L

    private var thread: Thread? = null
    private var target: Any? = null            // object that has inputPCMData(Frame)
    private var inputMethod: Method? = null
    private var frameCtor: Constructor<*>? = null
    private var micManager: Any? = null
    private var micField: Field? = null        // the library mic's "where do I send PCM" field
    private var micFieldOriginal: Any? = null

    fun statusLine(): String =
        if (!enabled) "AUDIO ENGINE: off"
        else if (running) "AUDIO ENGINE: $status | fed $fed | max gap $maxGapMs ms | drift $driftMs ms"
        else "AUDIO ENGINE: $status"

    // ------------------------------------------------------------------ start / stop

    @Synchronized
    fun start(activity: MainActivity) {
        if (!enabled) { status = "off"; return }
        if (running) return                      // already running (e.g. after a reconnect); library mic stays diverted
        val rate = preparedRate
        if (rate <= 0) { status = "off (unsupported audio format)"; return }
        if (activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "off (no mic permission)"; return
        }

        val cam: Any = activity.rtmpCamera

        // 1) who accepts PCM? the camera object itself, or its audio encoder
        var holder: Any = cam
        var im = holder.javaClass.methods.firstOrNull { it.name == "inputPCMData" && it.parameterTypes.size == 1 }
        if (im == null) {
            val enc = findFieldValue(cam, "audioEncoder")
            if (enc != null) {
                holder = enc
                im = enc.javaClass.methods.firstOrNull { it.name == "inputPCMData" && it.parameterTypes.size == 1 }
            }
        }
        if (im == null) { status = "off (library has no inputPCMData)"; return }

        val fc: Constructor<*>? = try {
            im.parameterTypes[0].getConstructor(
                ByteArray::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!
            )
        } catch (e: Exception) { null }
        if (fc == null) { status = "off (unknown Frame class)"; return }

        // 2) divert the library microphone's output to nowhere (it keeps running; we can undo this at any time)
        val mm = findFieldValue(cam, "microphoneManager")
        if (mm == null) { status = "off (library mic not reachable)"; return }
        val sink = mm.javaClass.declaredFields.firstOrNull { it.type.isInterface && it.type.name.endsWith("GetMicrophoneData") }
        if (sink == null) { status = "off (library mic output not found)"; return }
        try {
            sink.isAccessible = true
            val original = sink.get(mm)
            val noop = Proxy.newProxyInstance(sink.type.classLoader, arrayOf(sink.type), InvocationHandler { _, _, _ -> null })
            sink.set(mm, noop)
            micManager = mm; micField = sink; micFieldOriginal = original
        } catch (e: Exception) { status = "off (cannot divert library mic)"; return }

        target = holder
        inputMethod = im
        frameCtor = fc
        muted = activity.isAudioMuted
        fed = 0L; maxGapMs = 0L; driftMs = 0L
        running = true
        status = "starting"
        thread = Thread({ loop(activity, cam, rate) }, "MBAudioEngine").also { it.start() }
    }

    @Synchronized
    fun stop() {
        running = false
        try { thread?.join(500) } catch (e: Exception) {}
        thread = null
        restoreLibraryMic()
        if (enabled) status = "stopped"
    }

    private fun restoreLibraryMic() {
        try {
            val f = micField
            val mm = micManager
            if (f != null && mm != null) f.set(mm, micFieldOriginal)
        } catch (e: Exception) { }
        micField = null; micManager = null; micFieldOriginal = null
    }

    private fun fail(activity: MainActivity, why: String) {
        status = "FALLBACK ($why)"
        running = false
        restoreLibraryMic()
        activity.runOnUiThread { Toast.makeText(activity, "Audio engine handed back to the library: $why", Toast.LENGTH_LONG).show() }
    }

    private fun findFieldValue(obj: Any, name: String): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(obj)
            } catch (e: NoSuchFieldException) {
            } catch (e: Exception) { return null }
            c = c.superclass
        }
        return null
    }

    // ------------------------------------------------------------------ capture thread

    private fun loop(activity: MainActivity, cam: Any, rate: Int) {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (e: Exception) {}

        val bluetooth = activity.isBluetoothMicActive
        val source = if (bluetooth) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { fail(activity, "rate $rate not supported"); return }
        val bufBytes = maxOf(minBuf * 4, rate / 4 * 2)              // at least 250 ms of audio

        var rec: AudioRecord? = null
        try {
            val r = AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes)
            rec = r
            if (r.state != AudioRecord.STATE_INITIALIZED) { fail(activity, "microphone not available"); return }

            val frameSamples = 1024                                  // one AAC frame
            val shorts = ShortArray(frameSamples)
            val bytes = ByteArray(frameSamples * 2)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            var totalSamples = 0L
            var baseUs = System.nanoTime() / 1000
            var firstErrUs = Long.MIN_VALUE
            var chunkCount = 0L
            var lastReadEnd = 0L
            var hpIn = 0f
            var hpOut = 0f
            val gain = 1.0f
            val startedAt = SystemClock.elapsedRealtime()
            var sentAtCheck1 = -1L
            var checked = false

            r.startRecording()
            status = "active"

            while (running) {
                var got = 0
                while (got < frameSamples && running) {
                    val n = r.read(shorts, got, frameSamples - got)
                    if (n < 0) { fail(activity, "microphone read error $n"); return }
                    got += n
                }
                if (!running) break
                val now = SystemClock.elapsedRealtime()
                if (lastReadEnd != 0L) { val gap = now - lastReadEnd; if (gap > maxGapMs) maxGapMs = gap }
                lastReadEnd = now

                // ---- clean-up: 80 Hz high-pass + soft limiter ----
                if (muted) {
                    java.util.Arrays.fill(shorts, 0.toShort())
                } else {
                    for (i in 0 until frameSamples) {
                        val x = shorts[i].toFloat()
                        val y = 0.99f * (hpOut + x - hpIn)
                        hpIn = x
                        hpOut = y
                        var s = y * gain / 32768f
                        val a = abs(s)
                        if (a > 0.8f) {
                            val limited = 0.8f + 0.2f * tanh((a - 0.8f) / 0.2f)
                            s = if (s < 0f) -limited else limited
                        }
                        shorts[i] = (s * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                    }
                }

                // ---- timestamp from the SAMPLE COUNT, slowly steered to the system clock ----
                val tsUs = baseUs + totalSamples * 1_000_000L / rate
                totalSamples += frameSamples
                chunkCount++
                val nowUs = System.nanoTime() / 1000
                val errUs = nowUs - (baseUs + totalSamples * 1_000_000L / rate)
                if (firstErrUs == Long.MIN_VALUE) firstErrUs = errUs
                val driftUs = errUs - firstErrUs
                driftMs = driftUs / 1000
                if (chunkCount % 47L == 0L && abs(driftUs) > 15_000L) baseUs += driftUs / 8   // ~1x per second, gentle

                // ---- hand one exact AAC frame to the encoder ----
                bb.clear()
                bb.asShortBuffer().put(shorts)
                val copy = bytes.copyOf()
                try {
                    val frame = frameCtor!!.newInstance(copy, 0, copy.size, tsUs)
                    inputMethod!!.invoke(target, frame)
                    fed++
                } catch (e: Exception) {
                    fail(activity, "encoder rejected audio")
                    return
                }

                // ---- watchdog: is audio really reaching the sender, and exactly once? ----
                val sinceStart = now - startedAt
                if (!checked) {
                    if (sinceStart in 3000L..3400L && sentAtCheck1 < 0) {
                        sentAtCheck1 = StreamStats.sentAudio(cam) ?: -2L
                    } else if (sinceStart >= 7000L && sentAtCheck1 >= 0) {
                        val s2 = StreamStats.sentAudio(cam)
                        if (s2 != null) {
                            val seconds = (sinceStart - 3000L) / 1000.0
                            val perSec = (s2 - sentAtCheck1) / seconds
                            val expected = rate / 1024.0
                            if (perSec < expected * 0.5) { fail(activity, "no audio reached the sender"); return }
                            if (perSec > expected * 1.6) { fail(activity, "library mic still active (audio doubled)"); return }
                        }
                        checked = true
                    } else if (sinceStart >= 7000L) {
                        checked = true      // counters not available: skip the watchdog
                    }
                }
            }
        } catch (e: Exception) {
            fail(activity, "error: ${e.message}")
        } finally {
            try { rec?.stop() } catch (e: Exception) {}
            try { rec?.release() } catch (e: Exception) {}
        }
    }
}
