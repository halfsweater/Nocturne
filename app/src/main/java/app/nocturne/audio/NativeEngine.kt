package app.nocturne.audio

import android.content.res.AssetManager

/**
 * Thin Kotlin facade over the native engine (FluidSynth voices + Oboe output).
 *
 * Note events are pushed into a lock-free queue that the audio callback drains,
 * so [noteOn]/[noteOff] are cheap and safe to call straight from touch handling.
 * [loadInstrument] and [transcode] block on file I/O and must run off the main thread.
 */
object NativeEngine {

    fun interface ProgressListener {
        fun onProgress(fraction: Float)
    }

    data class Stats(
        val sampleRate: Int,
        val framesPerBurst: Int,
        val bufferFrames: Int,
        val xruns: Int,
        val latencyMs: Float,
        val activeVoices: Int,
        val peak: Float,
        val running: Boolean,
    )

    /** Order of the voicing array passed to native code (must match NativeBridge.cpp). */
    val VOICING_KEYS = listOf(
        "filterCents", "filterQ", "decayTimecents", "releaseTimecents", "reverbSend", "chorusSend",
        "roomSize", "damping", "reverbWidth", "reverbLevel", "chorusDepthMs", "chorusSpeedHz", "chorusLevel",
        "chorusVoices", "lowShelfDb", "presenceDb", "highShelfDb", "stereoWidth", "outputGainDb",
    )

    val VOICING_DEFAULTS = mapOf(
        "filterCents" to 0f, "filterQ" to 0f, "decayTimecents" to 0f, "releaseTimecents" to 0f,
        "reverbSend" to 0.25f, "chorusSend" to 0f, "roomSize" to 0.55f, "damping" to 0.35f,
        "reverbWidth" to 0.8f, "reverbLevel" to 0.75f, "chorusDepthMs" to 4f, "chorusSpeedHz" to 0.35f,
        "chorusLevel" to 1.2f, "chorusVoices" to 3f, "lowShelfDb" to 0f, "presenceDb" to 0f,
        "highShelfDb" to 0f, "stereoWidth" to 1f, "outputGainDb" to 0f,
    )

    @Volatile
    var sampleRate: Int = 0
        private set

    /** False if the native library could not be loaded (unsupported ABI, tests on the JVM). */
    val loaded: Boolean = runCatching { System.loadLibrary("nocturne") }.isSuccess

    @Volatile
    private var ready = false

    /** Opens the audio stream and creates the synth pool. Returns false if audio is unavailable. */
    @Synchronized
    fun init(): Boolean {
        if (!loaded) return false
        if (sampleRate > 0) return true
        sampleRate = nativeInit()
        ready = sampleRate > 0
        return ready
    }

    fun start() { if (ready) nativeStart() }
    fun stop() { if (ready) nativeStop() }
    fun noteOn(key: Int, velocity: Int) { if (ready) nativeNoteOn(key, velocity) }
    fun noteOff(key: Int) { if (ready) nativeNoteOff(key) }
    fun sustain(down: Boolean) { if (ready) nativeSustain(down) }
    fun allNotesOff() { if (ready) nativeAllNotesOff() }

    fun setParams(
        volume: Float, brightness: Float, reverb: Float, sustain: Float,
        concertHall: Boolean, studio: Boolean, soft: Boolean,
    ) { if (ready) nativeSetParams(volume, brightness, reverb, sustain, concertHall, studio, soft) }

    /**
     * @param files distinct SF2 paths
     * @param layers per layer (fileIndex, bank, program, transpose, velocityOffset, gainDb, velocityScale)
     * @return null on success, otherwise an error message
     */
    fun loadInstrument(files: List<String>, layers: List<NativeLayer>, voicing: Map<String, Float>): String? {
        if (!ready) return "Audio engine unavailable"
        val ints = IntArray(layers.size * 5)
        val floats = FloatArray(layers.size * 2)
        layers.forEachIndexed { i, l ->
            ints[i * 5] = l.fileIndex
            ints[i * 5 + 1] = l.bank
            ints[i * 5 + 2] = l.program
            ints[i * 5 + 3] = l.transpose
            ints[i * 5 + 4] = l.velocityOffset
            floats[i * 2] = l.gainDb
            floats[i * 2 + 1] = l.velocityScale
        }
        val v = FloatArray(VOICING_KEYS.size) { voicing[VOICING_KEYS[it]] ?: VOICING_DEFAULTS.getValue(VOICING_KEYS[it]) }
        return nativeLoadInstrument(files.toTypedArray(), ints, floats, v)
    }

    fun transcodeAsset(assets: AssetManager, assetPath: String, outPath: String, listener: ProgressListener?): String? =
        if (loaded) nativeTranscode(assets, assetPath, null, outPath, listener) else "Audio engine unavailable"

    fun transcodeFile(inPath: String, outPath: String, listener: ProgressListener?): String? =
        if (loaded) nativeTranscode(null, null, inPath, outPath, listener) else "Audio engine unavailable"

    fun stats(): Stats {
        val s = if (loaded) nativeStats() else FloatArray(8)
        return Stats(s[0].toInt(), s[1].toInt(), s[2].toInt(), s[3].toInt(), s[4], s[5].toInt(), s[6], s[7] > 0f)
    }

    private external fun nativeInit(): Int
    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeNoteOn(key: Int, velocity: Int)
    private external fun nativeNoteOff(key: Int)
    private external fun nativeSustain(down: Boolean)
    private external fun nativeAllNotesOff()
    private external fun nativeSetParams(
        volume: Float, brightness: Float, reverb: Float, sustain: Float,
        hall: Boolean, studio: Boolean, soft: Boolean,
    )
    private external fun nativeLoadInstrument(
        files: Array<String>, layerInts: IntArray, layerFloats: FloatArray, voicing: FloatArray,
    ): String?
    private external fun nativeTranscode(
        assets: AssetManager?, assetPath: String?, inPath: String?, outPath: String, listener: ProgressListener?,
    ): String?
    private external fun nativeStats(): FloatArray
}

data class NativeLayer(
    val fileIndex: Int,
    val bank: Int,
    val program: Int,
    val transpose: Int,
    val velocityOffset: Int,
    val gainDb: Float,
    val velocityScale: Float,
)
