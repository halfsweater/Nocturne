package app.nocturne

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real UI on the JVM (Robolectric native graphics) and writes PNGs to
 * app/build/screenshots for visual review. The audio engine is not loaded on the JVM,
 * so the header shows the "audio unavailable" state instead of the instrument tagline.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun save(name: String) {
        rule.waitForIdle()
        val root = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp-port-xxhdpi")
    fun portrait() {
        save("main_portrait")
        rule.onNodeWithContentDescription("Sound settings").performClick()
        save("sound_panel_portrait")
    }

    @Test
    @Config(qualifiers = "w851dp-h393dp-land-xxhdpi")
    fun landscape() {
        save("main_landscape")
        rule.onNodeWithContentDescription("Sound settings").performClick()
        save("sound_panel_landscape")
    }

    @Test
    @Config(qualifiers = "w393dp-h851dp-port-xxhdpi")
    fun library() {
        rule.onNodeWithContentDescription("Sound library").performClick()
        save("library_portrait")
    }
}
