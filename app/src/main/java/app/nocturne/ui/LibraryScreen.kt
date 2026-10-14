package app.nocturne.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nocturne.data.Category
import app.nocturne.data.Instrument
import app.nocturne.data.PackStatus
import app.nocturne.data.SoundPack
import app.nocturne.ui.theme.Nocturne
import kotlin.math.roundToInt

private enum class Filter(val title: String) { All("All"), Favorites("Favorites"), Acoustic("Acoustic"), Electric("Electric & Keys"), Packs("Sound packs") }

@Composable
fun LibraryScreen(vm: PianoViewModel, onBack: () -> Unit, onAbout: () -> Unit) {
    BackHandler(onBack = onBack)
    val current by vm.current.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val packStatus by vm.packStatus.collectAsState()
    var filter by rememberSaveable { mutableStateOf(Filter.All) }

    val instruments = vm.catalog.instruments.filter {
        when (filter) {
            Filter.All -> true
            Filter.Favorites -> it.id in favorites
            Filter.Acoustic -> it.category == Category.Acoustic
            Filter.Electric -> it.category == Category.Electric
            Filter.Packs -> it.category == Category.Extra
        }
    }
    val grouped = instruments.groupBy { it.category }

    Column(Modifier.fillMaxSize().background(Nocturne.Charcoal)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconCircleButton(NIcons.Back, "Back to piano", onBack, size = 40.dp)
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Overline("Nocturne")
                Text("Sound Library", style = MaterialTheme.typography.headlineSmall, color = Nocturne.Ivory)
            }
            IconCircleButton(NIcons.Info, "Licences and credits", onAbout, size = 40.dp)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Filter.entries.forEach { f -> ToggleChip(f.title, filter == f, { filter = f }) }
        }
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(330.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (instruments.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        if (filter == Filter.Favorites) "Tap the heart on any sound to keep it here." else "Nothing here yet.",
                        style = MaterialTheme.typography.bodyMedium, color = Nocturne.SilverDim,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            for ((category, list) in grouped) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "h-${category.key}") {
                    Overline(category.title, Modifier.padding(top = 14.dp, bottom = 2.dp), color = Nocturne.Silver)
                }
                items(list, key = { it.id }) { inst ->
                    val statuses = inst.packs.map { packStatus[it] ?: PackStatus.NotDownloaded }
                    InstrumentCard(
                        instrument = inst,
                        selected = inst.id == current.instrument.id,
                        favorite = inst.id in favorites,
                        status = combined(statuses),
                        downloadBytes = inst.packs.sumOf { vm.catalog.pack(it).downloadBytes },
                        onSelect = { vm.select(inst.id); onBack() },
                        onPreview = { vm.preview(inst.id) },
                        onFavorite = { vm.toggleFavorite(inst.id) },
                        onDownload = { inst.packs.forEach { vm.downloadPack(it) } },
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "packs") {
                Overline("Sound packs on this device", Modifier.padding(top = 22.dp, bottom = 2.dp), color = Nocturne.Silver)
            }
            items(vm.catalog.packs, key = { "pack-${it.id}" }) { pack ->
                PackCard(
                    pack = pack,
                    status = packStatus[pack.id] ?: PackStatus.NotDownloaded,
                    inUse = pack.id in current.instrument.packs,
                    onDownload = { vm.downloadPack(pack.id) },
                    onRemove = { vm.removePack(pack.id) },
                )
            }
        }
    }
}

private fun combined(statuses: List<PackStatus>): PackStatus =
    statuses.firstOrNull { it is PackStatus.Failed }
        ?: statuses.firstOrNull { it is PackStatus.Downloading }
        ?: statuses.firstOrNull { it is PackStatus.Preparing }
        ?: statuses.firstOrNull { it is PackStatus.NotDownloaded }
        ?: statuses.firstOrNull { it is PackStatus.Bundled }
        ?: statuses.first()

@Composable
private fun InstrumentCard(
    instrument: Instrument,
    selected: Boolean,
    favorite: Boolean,
    status: PackStatus,
    downloadBytes: Long,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
    onFavorite: () -> Unit,
    onDownload: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val playable = status.availableOffline
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Nocturne.SurfaceHigh else Nocturne.Surface)
            .border(1.dp, if (selected) Nocturne.Silver else Nocturne.Hairline, shape)
            .clickable(role = Role.Button) { if (playable) onSelect() else onDownload() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconCircleButton(
            if (playable) NIcons.Play else NIcons.Download,
            if (playable) "Preview ${instrument.name}" else "Download ${instrument.name}",
            if (playable) onPreview else onDownload,
            size = 42.dp,
            tint = Nocturne.Ivory,
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(instrument.name, style = MaterialTheme.typography.titleMedium, color = Nocturne.Ivory,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(instrument.character, style = MaterialTheme.typography.bodySmall, color = Nocturne.Silver,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(status, downloadBytes)
                Spacer(Modifier.size(8.dp))
                Text(instrument.basis, style = MaterialTheme.typography.labelSmall, color = Nocturne.SilverDim,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconCircleButton(
            if (favorite) NIcons.HeartFilled else NIcons.Heart,
            if (favorite) "Remove from favorites" else "Add to favorites",
            onFavorite,
            size = 40.dp,
            tint = if (favorite) Nocturne.Accent else Nocturne.SilverDim,
            outlined = false,
        )
    }
}

@Composable
private fun StatusBadge(status: PackStatus, downloadBytes: Long) {
    val (text, color) = when (status) {
        is PackStatus.Ready, PackStatus.Bundled -> "OFFLINE" to Nocturne.Silver
        is PackStatus.Preparing -> "PREPARING ${(status.progress * 100).roundToInt()}%" to Nocturne.Silver
        is PackStatus.Downloading -> "DOWNLOADING ${(status.progress * 100).roundToInt()}%" to Nocturne.Accent
        PackStatus.NotDownloaded -> "DOWNLOAD · ${mb(downloadBytes)}" to Nocturne.Accent
        is PackStatus.Failed -> "FAILED · TAP TO RETRY" to Nocturne.Danger
    }
    Box(
        Modifier.clip(RoundedCornerShape(4.dp)).border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

private fun mb(bytes: Long) = if (bytes <= 0) "—" else "${(bytes / 1_000_000.0).roundToInt()} MB"

@Composable
private fun PackCard(pack: SoundPack, status: PackStatus, inUse: Boolean, onDownload: () -> Unit, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Nocturne.Surface).border(1.dp, Nocturne.Hairline, shape).padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pack.title, style = MaterialTheme.typography.titleMedium, color = Nocturne.Ivory)
                Text(if (pack.bundled) "Included with the app" else "Optional download",
                    style = MaterialTheme.typography.bodySmall, color = Nocturne.Silver)
            }
            when {
                status is PackStatus.NotDownloaded || status is PackStatus.Failed ->
                    IconCircleButton(NIcons.Download, "Download ${pack.title}", onDownload, size = 40.dp, tint = Nocturne.Accent)
                status is PackStatus.Ready && !pack.bundled && !inUse ->
                    IconCircleButton(NIcons.Close, "Remove ${pack.title}", onRemove, size = 40.dp)
                status is PackStatus.Ready || status is PackStatus.Bundled ->
                    Icon(NIcons.Check, "Available offline", tint = Nocturne.Silver, modifier = Modifier.size(22.dp))
                else -> Unit
            }
        }
        Spacer(Modifier.height(6.dp))
        StatusBadge(status, pack.downloadBytes)
        Spacer(Modifier.height(6.dp))
        Text("${pack.source} — ${pack.license}", style = MaterialTheme.typography.bodySmall, color = Nocturne.SilverDim)
    }
}
