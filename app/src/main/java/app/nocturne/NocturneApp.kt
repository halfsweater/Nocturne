package app.nocturne

import android.app.Application
import app.nocturne.data.Catalog
import app.nocturne.data.Settings
import app.nocturne.data.SoundPackManager

class NocturneApp : Application() {
    lateinit var catalog: Catalog
        private set
    lateinit var packs: SoundPackManager
        private set
    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        catalog = Catalog.parse(assets.open("instruments.json").bufferedReader().use { it.readText() })
        settings = Settings(this)
        packs = SoundPackManager(this, catalog)
    }
}
