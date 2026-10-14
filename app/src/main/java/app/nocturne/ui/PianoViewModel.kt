package app.nocturne.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.nocturne.BuildConfig
import app.nocturne.NocturneApp
import app.nocturne.audio.NativeEngine
import app.nocturne.audio.NativeLayer
import app.nocturne.data.Instrument
import app.nocturne.data.KeyboardSettings
import app.nocturne.data.PackStatus
import app.nocturne.data.SoundSettings
import app.nocturne.data.TouchResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class InstrumentState(
    val instrument: Instrument,
    val loading: Boolean = false,
    val error: String? = null,
)

class PianoViewModel(app: Application) : AndroidViewModel(app) {

    private val nocturne = app as NocturneApp
    val catalog = nocturne.catalog
    val packs = nocturne.packs
    private val settings = nocturne.settings

    val sound: StateFlow<SoundSettings> = settings.sound
    val keyboard: StateFlow<KeyboardSettings> = settings.keyboard
    val favorites: StateFlow<Set<String>> = settings.favorites
    val packStatus: StateFlow<Map<String, PackStatus>> = packs.status

    private val _current = MutableStateFlow(
        InstrumentState(catalog.instrument(settings.selectedInstrument) ?: catalog.instruments.first())
    )
    val current: StateFlow<InstrumentState> = _current.asStateFlow()

    private val _sustain = MutableStateFlow(false)
    val sustain: StateFlow<Boolean> = _sustain.asStateFlow()

    private val _audioReady = MutableStateFlow<Boolean?>(null)
    val audioReady: StateFlow<Boolean?> = _audioReady.asStateFlow()

    private var loadJob: Job? = null
    private var previewJob: Job? = null

    init {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { NativeEngine.init() }
            _audioReady.value = ok
            if (!ok) return@launch
            pushSound(settings.sound.value)
            if (BuildConfig.DEBUG) launch {
                // Debug builds log engine health while sound is playing (used for device testing).
                while (true) {
                    val st = NativeEngine.stats()
                    if (st.activeVoices > 0) android.util.Log.i("NocturneStats", st.toString())
                    delay(500)
                }
            }
            val start = _current.value.instrument.takeIf { packs.isAvailable(it) } ?: catalog.instruments.first()
            select(start.id)
            loadJob?.join()
            // Unpack favourites in the background so switching to them is instant later.
            for (id in settings.favorites.value) {
                catalog.instrument(id)?.packs?.forEach { p ->
                    if (packs.statusOf(p) is PackStatus.Bundled) runCatching { packs.prepare(p) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ instruments

    fun select(id: String, then: (() -> Unit)? = null) {
        val inst = catalog.instrument(id) ?: return
        loadJob?.cancel()
        _current.value = InstrumentState(inst, loading = true)
        settings.selectedInstrument = id
        loadJob = viewModelScope.launch {
            try {
                for (p in inst.packs) {
                    val st = packs.statusOf(p)
                    if (!st.availableOffline) packs.download(p)
                }
                val files = mutableListOf<String>()
                val layers = inst.layers.map { l ->
                    val path = packs.prepare(l.pack)
                    if (path !in files) files += path
                    NativeLayer(files.indexOf(path), l.bank, l.program, l.transpose, l.velocityOffset, l.gainDb, l.velocityScale)
                }
                val error = withContext(Dispatchers.IO) { NativeEngine.loadInstrument(files, layers, inst.voicing) }
                _current.value = InstrumentState(inst, loading = false, error = error)
                if (error == null) then?.invoke()
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                _current.value = InstrumentState(inst, loading = false, error = t.message ?: "Could not load")
            }
        }
    }

    /** Instruments that can be played right now without a download. */
    fun playable(): List<Instrument> = catalog.instruments.filter { packs.isAvailable(it) }

    fun step(delta: Int) {
        val list = playable()
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.id == _current.value.instrument.id }
        val next = list[((if (idx < 0) 0 else idx) + delta + list.size) % list.size]
        select(next.id)
    }

    fun preview(id: String) {
        previewJob?.cancel()
        select(id) {
            previewJob = viewModelScope.launch {
                val phrase = listOf(60, 64, 67, 72, 76)
                phrase.forEachIndexed { i, n ->
                    NativeEngine.noteOn(n, 70 + i * 6)
                    delay(140)
                }
                delay(260)
                listOf(48, 55, 64, 67, 72).forEach { NativeEngine.noteOn(it, 84) }
                delay(1300)
                (phrase + listOf(48, 55, 64, 67, 72)).distinct().forEach { NativeEngine.noteOff(it) }
            }
        }
    }

    fun toggleFavorite(id: String) = settings.toggleFavorite(id)

    fun downloadPack(id: String) {
        viewModelScope.launch { runCatching { packs.download(id) } }
    }

    fun removePack(id: String) {
        if (id in _current.value.instrument.packs) return
        viewModelScope.launch { packs.remove(id) }
    }

    // ------------------------------------------------------------------ sound & keyboard

    fun updateSound(f: (SoundSettings) -> SoundSettings) {
        settings.updateSound(f)
        pushSound(settings.sound.value)
    }

    private fun pushSound(s: SoundSettings) {
        NativeEngine.setParams(s.volume, s.brightness, s.reverb, s.sustain, s.concertHall, s.studio, s.soft)
    }

    fun updateKeyboard(f: (KeyboardSettings) -> KeyboardSettings) = settings.updateKeyboard(f)

    fun setSustain(down: Boolean) {
        if (_sustain.value == down) return
        _sustain.value = down
        NativeEngine.sustain(down)
    }

    fun shapeVelocity(raw: Int): Int {
        val k = settings.keyboard.value
        var v = if (k.touchResponse == TouchResponse.Fixed) k.fixedVelocity else raw
        if (settings.sound.value.soft) v = (v * 0.72f).toInt()
        return v.coerceIn(1, 127)
    }

    fun onBackground() {
        _sustain.update { false }
        NativeEngine.stop()
    }

    fun onForeground() {
        NativeEngine.start()
    }
}
