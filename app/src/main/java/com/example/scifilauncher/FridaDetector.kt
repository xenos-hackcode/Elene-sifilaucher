package com.example.scifilauncher

/** JNI bridge to layered native Frida detection (app/src/main/cpp/frida_detect_jni.cpp) - a
 * /proc/self/maps scan, a probe of Frida's default port (27042), a /proc/self/task thread-name
 * scan, a running-process cmdline scan, and a coarse timing heuristic. Deliberately native
 * rather than Kotlin, since Frida hooks the Java/ART layer far more easily than it hooks native
 * code touching raw /proc files and sockets directly. No single layer here is meant to work
 * alone - a determined attacker with Frida's own anti-detection scripts can still defeat
 * individual checks once they know what to look for - layering raises the real cost of
 * bypassing all of them at once, which is the actual, achievable goal. */
object FridaDetector {
    init {
        System.loadLibrary("fridadetect")
    }

    private external fun nativeScan(): Int

    data class ScanResult(
        val mapsSignature: Boolean,
        val portOpen: Boolean,
        val threadNames: Boolean,
        val processSignature: Boolean,
        val timingAnomaly: Boolean
    ) {
        /** How many independent layers actually flagged something - the real severity signal,
         * since any single layer alone is a weak/noisy signal (especially the timing one). */
        val signalCount: Int
            get() = listOf(mapsSignature, portOpen, threadNames, processSignature, timingAnomaly)
                .count { it }
    }

    fun scan(): ScanResult {
        val bits = runCatching { nativeScan() }.getOrDefault(0)
        return ScanResult(
            mapsSignature = bits and 1 != 0,
            portOpen = bits and 2 != 0,
            threadNames = bits and 4 != 0,
            processSignature = bits and 8 != 0,
            timingAnomaly = bits and 16 != 0
        )
    }
}
