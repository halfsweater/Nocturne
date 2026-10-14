package app.nocturne.data

import android.content.Context
import app.nocturne.audio.NativeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

sealed interface PackStatus {
    /** Bundled with the app but not yet unpacked (happens automatically on first use). */
    data object Bundled : PackStatus
    data object NotDownloaded : PackStatus
    data class Downloading(val progress: Float) : PackStatus
    data class Preparing(val progress: Float) : PackStatus
    data class Ready(val path: String) : PackStatus
    data class Failed(val message: String, val downloaded: Boolean) : PackStatus

    val availableOffline: Boolean get() = this is Bundled || this is Ready || this is Preparing
}

/**
 * Owns the sound packs on disk.
 *
 * Packs ship (or download) as SF3 (Ogg Vorbis compressed). Before first use a pack is
 * transcoded once into an SF2 file in app storage, which FluidSynth then streams with
 * dynamic sample loading, so only the instrument being played occupies memory.
 */
class SoundPackManager(private val context: Context, private val catalog: Catalog) {

    private val dir = File(context.filesDir, "packs").apply { mkdirs() }
    private val locks = catalog.packs.associate { it.id to Mutex() }
    private val _status = MutableStateFlow<Map<String, PackStatus>>(emptyMap())
    val status: StateFlow<Map<String, PackStatus>> = _status.asStateFlow()

    init {
        _status.value = catalog.packs.associate { it.id to initialStatus(it) }
        cleanStaleFiles()
    }

    private fun cacheFile(pack: SoundPack): File {
        val tag = if (pack.asset != null) {
            runCatching { context.assets.openFd(pack.asset).use { it.length } }.getOrDefault(0L)
        } else {
            (pack.sha256 ?: "x").take(12)
        }
        return File(dir, "${pack.id}-$tag.sf2")
    }

    private fun initialStatus(pack: SoundPack): PackStatus {
        val f = cacheFile(pack)
        return when {
            f.exists() && f.length() > 0 -> PackStatus.Ready(f.absolutePath)
            pack.bundled -> PackStatus.Bundled
            else -> PackStatus.NotDownloaded
        }
    }

    private fun cleanStaleFiles() {
        val keep = catalog.packs.map { cacheFile(it).name }.toSet()
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".sf2") && f.name !in keep) f.delete()
            if (f.name.endsWith(".part")) f.delete()
        }
    }

    private fun set(id: String, s: PackStatus) = _status.update { it + (id to s) }

    fun statusOf(id: String): PackStatus = _status.value[id] ?: PackStatus.NotDownloaded

    fun isAvailable(instrument: Instrument): Boolean = instrument.packs.all { statusOf(it).availableOffline }

    fun diskUsageBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Ensures the pack is unpacked and returns the SF2 path. Downloads are never started implicitly. */
    suspend fun prepare(packId: String): String = withContext(Dispatchers.IO) {
        val pack = catalog.pack(packId)
        locks.getValue(packId).withLock {
            val out = cacheFile(pack)
            if (out.exists() && out.length() > 0) {
                set(packId, PackStatus.Ready(out.absolutePath))
                return@withLock out.absolutePath
            }
            val source = pack.asset ?: throw IOException("${pack.title} is not downloaded")
            set(packId, PackStatus.Preparing(0f))
            val error = NativeEngine.transcodeAsset(context.assets, source, out.absolutePath) { p ->
                set(packId, PackStatus.Preparing(p))
            }
            if (error != null) {
                set(packId, PackStatus.Failed(error, downloaded = false))
                throw IOException(error)
            }
            set(packId, PackStatus.Ready(out.absolutePath))
            out.absolutePath
        }
    }

    /** Downloads an optional pack, verifies its checksum and unpacks it. */
    suspend fun download(packId: String) = withContext(Dispatchers.IO) {
        val pack = catalog.pack(packId)
        val url = pack.url ?: return@withContext
        locks.getValue(packId).withLock {
            val out = cacheFile(pack)
            if (out.exists()) {
                set(packId, PackStatus.Ready(out.absolutePath))
                return@withLock
            }
            val part = File(dir, "${pack.id}.sf3.part")
            try {
                set(packId, PackStatus.Downloading(0f))
                val digest = MessageDigest.getInstance("SHA-256")
                var conn = URL(url).openConnection() as HttpURLConnection
                var redirects = 0
                while (conn.responseCode in 300..399 && redirects++ < 5) {
                    val next = conn.getHeaderField("Location")
                    conn.disconnect()
                    conn = URL(URL(url), next).openConnection() as HttpURLConnection
                }
                if (conn.responseCode != 200) throw IOException("Server returned ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: pack.downloadBytes
                conn.inputStream.use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReported = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            if (done - lastReported > 256 * 1024) {
                                lastReported = done
                                set(packId, PackStatus.Downloading((done.toFloat() / total).coerceIn(0f, 1f)))
                            }
                        }
                    }
                }
                conn.disconnect()
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (pack.sha256 != null && !hash.equals(pack.sha256, ignoreCase = true)) {
                    throw IOException("Download was corrupted (checksum mismatch)")
                }
                set(packId, PackStatus.Preparing(0f))
                val error = NativeEngine.transcodeFile(part.absolutePath, out.absolutePath) { p ->
                    set(packId, PackStatus.Preparing(p))
                }
                if (error != null) throw IOException(error)
                set(packId, PackStatus.Ready(out.absolutePath))
            } catch (t: Throwable) {
                set(packId, if (t is kotlinx.coroutines.CancellationException) PackStatus.NotDownloaded
                else PackStatus.Failed(t.message ?: "Download failed", downloaded = true))
                if (t is kotlinx.coroutines.CancellationException) throw t
            } finally {
                part.delete()
            }
        }
    }

    /** Removes a downloaded pack (bundled packs only drop their unpacked cache). */
    suspend fun remove(packId: String) = withContext(Dispatchers.IO) {
        val pack = catalog.pack(packId)
        locks.getValue(packId).withLock {
            cacheFile(pack).delete()
            set(packId, if (pack.bundled) PackStatus.Bundled else PackStatus.NotDownloaded)
        }
    }
}
