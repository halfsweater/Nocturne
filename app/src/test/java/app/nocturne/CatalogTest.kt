package app.nocturne

import app.nocturne.data.Catalog
import app.nocturne.data.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CatalogTest {

    private val catalog = Catalog.parse(File("src/main/assets/instruments.json").readText())

    @Test
    fun allRequestedInstrumentsArePresent() {
        val names = catalog.instruments.map { it.name }
        listOf(
            "Yamaha-Inspired Grand", "Steinway-Inspired Concert Grand", "Bösendorfer-Inspired Grand",
            "Fazioli-Inspired Concert Grand", "Warm Grand", "Bright Grand", "Soft Piano", "Upright Piano",
            "Electric Piano", "Rhodes-Style Electric Piano", "Wurlitzer-Style Electric Piano", "Digital Stage Piano",
            "Church Organ", "Harpsichord", "Soft Ambient Piano", "Cinematic Piano",
        ).forEach { assertTrue("missing $it", it in names) }
    }

    @Test
    fun coreInstrumentsAreBundledForOfflinePlay() {
        val core = catalog.instruments.filter { it.category != Category.Extra }
        assertEquals(16, core.size)
        core.forEach { inst -> inst.packs.forEach { assertTrue("${inst.id}: $it must be bundled", catalog.pack(it).bundled) } }
        catalog.packs.filter { it.bundled }.forEach {
            assertTrue("asset ${it.asset} missing", File("src/main/assets/${it.asset}").exists())
        }
    }

    @Test
    fun noTwoInstrumentsAreIdentical() {
        val signatures = catalog.instruments.map { it.layers to it.voicing }
        assertEquals(signatures.size, signatures.toSet().size)
    }

    @Test
    fun downloadablePacksArePinned() {
        catalog.packs.filter { !it.bundled }.forEach {
            assertTrue(it.url!!.startsWith("https://"))
            assertEquals(64, it.sha256!!.length)
        }
    }
}
