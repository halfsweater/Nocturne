package app.nocturne.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.nocturne.audio.NativeEngine
import app.nocturne.ui.theme.Nocturne
import kotlinx.coroutines.delay

@Composable
fun AboutScreen(vm: PianoViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val text = remember { context.assets.open("licenses.txt").bufferedReader().use { it.readText() } }
    var stats by remember { mutableStateOf<NativeEngine.Stats?>(null) }
    var peakVoices by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            stats = runCatching { NativeEngine.stats() }.getOrNull()
            stats?.let { peakVoices = maxOf(peakVoices, it.peakVoices) }
            delay(1000)
        }
    }
    Column(Modifier.fillMaxSize().background(Nocturne.Charcoal)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconCircleButton(NIcons.Back, "Back", onBack, size = 40.dp)
            Spacer(Modifier.size(14.dp))
            Text("Licences & credits", style = MaterialTheme.typography.headlineSmall, color = Nocturne.Ivory)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            stats?.let { s ->
                Overline("Audio engine")
                Spacer(Modifier.height(4.dp))
                Text(
                    "Output ${s.sampleRate} Hz · burst ${s.framesPerBurst} frames · buffer ${s.bufferFrames} frames · " +
                        "output latency ${if (s.latencyMs >= 0) "%.1f ms".format(s.latencyMs) else "n/a"} · " +
                        "underruns ${s.xruns} · voices ${s.activeVoices} (peak $peakVoices) · " +
                        "samples on device ${vm.packs.diskUsageBytes() / 1_000_000} MB",
                    style = MaterialTheme.typography.bodySmall, color = Nocturne.Silver,
                )
                Spacer(Modifier.height(16.dp))
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = Nocturne.Silver, modifier = Modifier.widthIn(max = 720.dp))
            Spacer(Modifier.height(40.dp))
        }
    }
}
