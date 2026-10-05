package com.mblivestudio

import android.os.Process
import java.io.File

/**
 * Starved audio threads cause "tak tak" ticks and crackle: if the CPU is busy with camera, GL or encoder work,
 * the thread that reads the microphone can be delayed and a few milliseconds of sound are lost.
 * This raises the priority of the audio capture and audio encoder threads (found by name) to "urgent audio".
 * Returns how many threads were raised (0 means no audio thread was found).
 */
internal object AudioBoost {
    fun apply(): Int {
        var count = 0
        try {
            val tasks = File("/proc/self/task").listFiles() ?: return 0
            for (t in tasks) {
                val tid = t.name.toIntOrNull() ?: continue
                val name = try { File(t, "comm").readText().trim().lowercase() } catch (e: Exception) { continue }
                if (name.contains("microphone") || name.contains("audio")) {
                    try {
                        Process.setThreadPriority(tid, Process.THREAD_PRIORITY_URGENT_AUDIO)
                        count++
                    } catch (e: Exception) { }
                }
            }
        } catch (e: Exception) { }
        return count
    }
}
