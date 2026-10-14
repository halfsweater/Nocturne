package app.nocturne.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TouchResponse { Dynamic, Fixed }

data class SoundSettings(
    val volume: Float = 0.8f,
    val brightness: Float = 0f,
    val reverb: Float = 0.5f,
    val sustain: Float = 0.5f,
    val concertHall: Boolean = false,
    val studio: Boolean = false,
    val soft: Boolean = false,
)

data class KeyboardSettings(
    /** Index (0..51) of the left-most visible white key on the 88-key keyboard. */
    val firstWhite: Float = 23f,                // C4 region
    val visibleWhitePortrait: Int = 10,
    val visibleWhiteLandscape: Int = 17,
    val touchResponse: TouchResponse = TouchResponse.Dynamic,
    val fixedVelocity: Int = 96,
    val showLabels: Boolean = true,
)

/** Small SharedPreferences-backed store; everything stays on the device. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("nocturne", Context.MODE_PRIVATE)

    private val _sound = MutableStateFlow(readSound())
    val sound: StateFlow<SoundSettings> = _sound.asStateFlow()

    private val _keyboard = MutableStateFlow(readKeyboard())
    val keyboard: StateFlow<KeyboardSettings> = _keyboard.asStateFlow()

    private val _favorites = MutableStateFlow(prefs.getStringSet("favorites", setOf("yamaha_grand", "steinway_concert"))!!.toSet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    var selectedInstrument: String
        get() = prefs.getString("instrument", "yamaha_grand")!!
        set(value) = prefs.edit().putString("instrument", value).apply()

    private fun readSound() = SoundSettings(
        volume = prefs.getFloat("volume", 0.8f),
        brightness = prefs.getFloat("brightness", 0f),
        reverb = prefs.getFloat("reverb", 0.5f),
        sustain = prefs.getFloat("sustain", 0.5f),
        concertHall = prefs.getBoolean("hall", false),
        studio = prefs.getBoolean("studio", false),
        soft = prefs.getBoolean("soft", false),
    )

    private fun readKeyboard() = KeyboardSettings(
        firstWhite = prefs.getFloat("firstWhite", 23f),
        visibleWhitePortrait = prefs.getInt("visiblePortrait", 10),
        visibleWhiteLandscape = prefs.getInt("visibleLandscape", 17),
        touchResponse = TouchResponse.entries.getOrElse(prefs.getInt("touch", 0)) { TouchResponse.Dynamic },
        fixedVelocity = prefs.getInt("fixedVelocity", 96),
        showLabels = prefs.getBoolean("labels", true),
    )

    fun updateSound(f: (SoundSettings) -> SoundSettings) {
        val s = f(_sound.value)
        _sound.value = s
        prefs.edit()
            .putFloat("volume", s.volume).putFloat("brightness", s.brightness).putFloat("reverb", s.reverb)
            .putFloat("sustain", s.sustain).putBoolean("hall", s.concertHall).putBoolean("studio", s.studio)
            .putBoolean("soft", s.soft).apply()
    }

    fun updateKeyboard(f: (KeyboardSettings) -> KeyboardSettings) {
        val k = f(_keyboard.value)
        _keyboard.value = k
        prefs.edit()
            .putFloat("firstWhite", k.firstWhite).putInt("visiblePortrait", k.visibleWhitePortrait)
            .putInt("visibleLandscape", k.visibleWhiteLandscape).putInt("touch", k.touchResponse.ordinal)
            .putInt("fixedVelocity", k.fixedVelocity).putBoolean("labels", k.showLabels).apply()
    }

    fun toggleFavorite(id: String) {
        val next = if (id in _favorites.value) _favorites.value - id else _favorites.value + id
        _favorites.value = next
        prefs.edit().putStringSet("favorites", next).apply()
    }
}
