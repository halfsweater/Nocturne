package app.nocturne.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.nocturne.audio.NativeEngine
import app.nocturne.data.Category
import app.nocturne.data.PackStatus
import app.nocturne.ui.keyboard.Piano
import app.nocturne.ui.keyboard.PianoKeyboardView
import app.nocturne.ui.theme.Nocturne
import kotlin.math.roundToInt

@Composable
fun MainScreen(vm: PianoViewModel, onOpenLibrary: () -> Unit) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val state by vm.current.collectAsState()
    val sound by vm.sound.collectAsState()
    val keyboard by vm.keyboard.collectAsState()
    val sustain by vm.sustain.collectAsState()
    val packStatus by vm.packStatus.collectAsState()
    val audioReady by vm.audioReady.collectAsState()
    var panelOpen by remember { androidx.compose.runtime.mutableStateOf(false) }

    val visible = if (landscape) keyboard.visibleWhiteLandscape else keyboard.visibleWhitePortrait
    var firstWhite by remember { mutableFloatStateOf(keyboard.firstWhite) }
    val maxFirst = (Piano.WHITE_COUNT - visible).toFloat()
    var keyboardView by remember { androidx.compose.runtime.mutableStateOf<PianoKeyboardView?>(null) }

    fun seek(to: Float, animate: Boolean) {
        val t = to.coerceIn(0f, maxFirst)
        keyboardView?.setFirstWhite(t, animate)
        if (!animate) firstWhite = t
        vm.updateKeyboard { it.copy(firstWhite = t) }
    }

    val preparing = state.instrument.packs.mapNotNull { packStatus[it] }.firstNotNullOfOrNull {
        when (it) {
            is PackStatus.Preparing -> "Preparing samples" to it.progress
            is PackStatus.Downloading -> "Downloading" to it.progress
            else -> null
        }
    }

    Column(Modifier.fillMaxSize().background(Nocturne.Charcoal)) {
        // ---------------------------------------------------------- header / sound panel
        AnimatedContent(
            targetState = panelOpen,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
            label = "header",
            modifier = Modifier.fillMaxWidth().weight(if (panelOpen) (if (landscape) 0.62f else 0.5f) else (if (landscape) 0.26f else 0.4f)),
        ) { open ->
            if (open) {
                SoundPanel(vm, sound, keyboard, landscape, onClose = { panelOpen = false })
            } else {
                Header(
                    name = state.instrument.name,
                    category = state.instrument.category,
                    character = state.instrument.character,
                    loading = state.loading,
                    progress = preparing,
                    error = state.error ?: if (audioReady == false) "Audio output unavailable" else null,
                    landscape = landscape,
                    onPrev = { vm.step(-1) },
                    onNext = { vm.step(1) },
                    onLibrary = onOpenLibrary,
                    onSound = { panelOpen = true },
                )
            }
        }

        // ---------------------------------------------------------- control strip
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).height(if (landscape) 46.dp else 52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCircleButton(NIcons.Minus, "Octave down", { seek(firstWhite - 7f, true) }, size = 38.dp)
            Column(Modifier.width(74.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Overline("Octave")
                val lo = Piano.WHITE_NOTES[firstWhite.roundToInt().coerceIn(0, Piano.WHITE_COUNT - 1)]
                val hi = Piano.WHITE_NOTES[(firstWhite.roundToInt() + visible - 1).coerceIn(0, Piano.WHITE_COUNT - 1)]
                Text("${Piano.name(lo)}–${Piano.name(hi)}", style = MaterialTheme.typography.bodySmall, color = Nocturne.Ivory)
            }
            IconCircleButton(NIcons.Plus, "Octave up", { seek(firstWhite + 7f, true) }, size = 38.dp)
            SmallSpacer(12.dp)
            KeyboardMiniMap(
                firstWhite = firstWhite,
                visibleWhite = visible,
                onSeek = ::seek,
                modifier = Modifier.weight(1f).height(28.dp),
            )
            SmallSpacer(12.dp)
            if (landscape || !panelOpen) {
                LabeledSlider(
                    label = "Vol",
                    value = sound.volume,
                    onValueChange = { v -> vm.updateSound { it.copy(volume = v) } },
                    valueText = "${(sound.volume * 100).roundToInt()}",
                    modifier = Modifier.width(if (landscape) 150.dp else 104.dp),
                )
                SmallSpacer(12.dp)
            }
            PedalButton(sustain, vm::setSustain, Modifier.size(width = 78.dp, height = if (landscape) 46.dp else 52.dp))
        }

        // ---------------------------------------------------------- keyboard
        AndroidView(
            factory = { ctx ->
                PianoKeyboardView(ctx).apply {
                    listener = object : PianoKeyboardView.Listener {
                        override fun onNoteOn(note: Int, velocity: Int) = NativeEngine.noteOn(note, velocity)
                        override fun onNoteOff(note: Int) = NativeEngine.noteOff(note)
                    }
                    velocityTransform = vm::shapeVelocity
                    onWindowChanged = { firstWhite = it }
                    keyboardView = this
                }
            },
            update = { v ->
                v.showLabels = keyboard.showLabels
                if (v.visibleWhite != visible) {
                    v.visibleWhite = visible
                    v.setFirstWhite(firstWhite.coerceIn(0f, maxFirst), animate = false)
                }
            },
            modifier = Modifier.fillMaxWidth().weight(if (panelOpen) (if (landscape) 0.38f else 0.5f) else (if (landscape) 0.74f else 0.6f)),
        )
    }

    LaunchedEffect(visible) { firstWhite = firstWhite.coerceIn(0f, maxFirst) }
    DisposableEffect(Unit) {
        onDispose { keyboardView = null }
    }
}

@Composable
private fun Header(
    name: String,
    category: Category,
    character: String,
    loading: Boolean,
    progress: Pair<String, Float>?,
    error: String?,
    landscape: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onLibrary: () -> Unit,
    onSound: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = if (landscape) 6.dp else 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("NOCTURNE", style = MaterialTheme.typography.labelMedium, color = Nocturne.SilverDim)
            Spacer(Modifier.weight(1f))
            IconCircleButton(NIcons.Tune, "Sound settings", onSound, size = 38.dp)
            SmallSpacer(10.dp)
            IconCircleButton(NIcons.Library, "Sound library", onLibrary, size = 38.dp)
        }
        Row(
            Modifier.fillMaxWidth().weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconCircleButton(NIcons.Left, "Previous instrument", onPrev, size = if (landscape) 40.dp else 46.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Overline(if (category == Category.Acoustic) "Acoustic piano" else category.title)
                Spacer(Modifier.height(if (landscape) 2.dp else 6.dp))
                Text(
                    name,
                    style = if (landscape) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.displaySmall,
                    color = Nocturne.Ivory,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Box(Modifier.height(20.dp), contentAlignment = Alignment.Center) {
                    when {
                        error != null -> Text(error, style = MaterialTheme.typography.bodySmall, color = Nocturne.Danger, maxLines = 1)
                        progress != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${progress.first} · ${(progress.second * 100).roundToInt()}%",
                                style = MaterialTheme.typography.bodySmall, color = Nocturne.SilverDim)
                            LinearProgressIndicator(
                                progress = { progress.second },
                                modifier = Modifier.width(120.dp).height(2.dp),
                                color = Nocturne.Silver,
                                trackColor = Nocturne.Hairline,
                            )
                        }
                        loading -> Text("Loading…", style = MaterialTheme.typography.bodySmall, color = Nocturne.SilverDim)
                        else -> Text(character, style = MaterialTheme.typography.bodySmall, color = Nocturne.SilverDim,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            IconCircleButton(NIcons.Right, "Next instrument", onNext, size = if (landscape) 40.dp else 46.dp)
        }
    }
}

@Composable
private fun SoundPanel(
    vm: PianoViewModel,
    sound: app.nocturne.data.SoundSettings,
    keyboard: app.nocturne.data.KeyboardSettings,
    landscape: Boolean,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Nocturne.Surface).verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sound", style = MaterialTheme.typography.titleMedium, color = Nocturne.Ivory)
            Spacer(Modifier.weight(1f))
            Text("Keep playing while you adjust", style = MaterialTheme.typography.bodySmall, color = Nocturne.SilverDim)
            SmallSpacer(12.dp)
            IconCircleButton(NIcons.Close, "Close sound settings", onClose, size = 36.dp)
        }
        Spacer(Modifier.height(6.dp))
        val sliders: List<@Composable (Modifier) -> Unit> = listOf(
            { m -> LabeledSlider("Volume", sound.volume, { v -> vm.updateSound { it.copy(volume = v) } }, m,
                valueText = "${(sound.volume * 100).roundToInt()}") },
            { m -> LabeledSlider("Reverb", sound.reverb, { v -> vm.updateSound { it.copy(reverb = v) } }, m,
                valueText = "${(sound.reverb * 100).roundToInt()}") },
            { m -> LabeledSlider("Brightness", sound.brightness, { v -> vm.updateSound { it.copy(brightness = v) } }, m,
                range = -1f..1f, valueText = signed(sound.brightness)) },
            { m -> LabeledSlider("Sustain", sound.sustain, { v -> vm.updateSound { it.copy(sustain = v) } }, m,
                valueText = when { sound.sustain < 0.4f -> "Short"; sound.sustain > 0.6f -> "Long"; else -> "Natural" }) },
        )
        val perRow = if (landscape) 4 else 2
        sliders.chunked(perRow).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                row.forEach { it(Modifier.weight(1f)) }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ToggleChip("Concert hall", sound.concertHall, { vm.updateSound { it.copy(concertHall = !it.concertHall) } })
            ToggleChip("Studio sound", sound.studio, { vm.updateSound { it.copy(studio = !it.studio) } })
            ToggleChip("Soft piano", sound.soft, { vm.updateSound { it.copy(soft = !it.soft) } })
        }
        Spacer(Modifier.height(12.dp))
        Hairline()
        Spacer(Modifier.height(8.dp))
        Overline("Keyboard")
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ToggleChip("Touch dynamics", keyboard.touchResponse == app.nocturne.data.TouchResponse.Dynamic, {
                vm.updateKeyboard {
                    it.copy(touchResponse = if (it.touchResponse == app.nocturne.data.TouchResponse.Dynamic)
                        app.nocturne.data.TouchResponse.Fixed else app.nocturne.data.TouchResponse.Dynamic)
                }
            })
            ToggleChip("Note names", keyboard.showLabels, { vm.updateKeyboard { it.copy(showLabels = !it.showLabels) } })
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            val keys = if (landscape) keyboard.visibleWhiteLandscape else keyboard.visibleWhitePortrait
            LabeledSlider(
                "Visible keys", keys.toFloat(),
                { v ->
                    vm.updateKeyboard {
                        if (landscape) it.copy(visibleWhiteLandscape = v.roundToInt()) else it.copy(visibleWhitePortrait = v.roundToInt())
                    }
                },
                Modifier.weight(1f), range = 7f..28f, valueText = "$keys white keys",
            )
            if (keyboard.touchResponse == app.nocturne.data.TouchResponse.Fixed) {
                LabeledSlider("Fixed velocity", keyboard.fixedVelocity.toFloat(),
                    { v -> vm.updateKeyboard { it.copy(fixedVelocity = v.roundToInt()) } },
                    Modifier.weight(1f), range = 20f..127f, valueText = "${keyboard.fixedVelocity}")
            } else {
                Text(
                    "Strike toward the front edge of a key to play louder.",
                    style = MaterialTheme.typography.bodySmall, color = Nocturne.SilverDim,
                    modifier = Modifier.weight(1f).padding(top = 14.dp),
                )
            }
        }
    }
}

private fun signed(v: Float): String {
    val p = (v * 100).roundToInt()
    return if (p > 0) "+$p" else "$p"
}
