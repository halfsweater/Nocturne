package app.nocturne

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.nocturne.data.Catalog
import app.nocturne.data.PackStatus
import app.nocturne.data.SoundPackManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Downloads the optional pack from its real host and checks the pinned SHA-256.
 * Network test: runs only with NOCTURNE_NETWORK_TESTS=1. The native transcoder is not
 * available on the JVM, so the pack ends in Failed("Audio engine unavailable") *after*
 * a successful, checksum-verified download — a corrupted download would fail earlier
 * with a checksum message instead.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PackDownloadTest {

    @Test
    fun downloadsAndVerifiesOptionalPack() = runBlocking {
        assumeTrue(System.getenv("NOCTURNE_NETWORK_TESTS") == "1")
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val catalog = Catalog.parse(File("src/main/assets/instruments.json").readText())
        val packs = SoundPackManager(ctx, catalog)
        assertEquals(PackStatus.NotDownloaded, packs.statusOf("musescore"))
        packs.download("musescore")
        val status = packs.statusOf("musescore")
        assertTrue("unexpected status $status", status is PackStatus.Failed)
        assertEquals("Audio engine unavailable", (status as PackStatus.Failed).message)
    }
}
