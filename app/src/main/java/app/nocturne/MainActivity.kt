package app.nocturne

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.nocturne.ui.AboutScreen
import app.nocturne.ui.LibraryScreen
import app.nocturne.ui.MainScreen
import app.nocturne.ui.PianoViewModel
import app.nocturne.ui.theme.Nocturne
import app.nocturne.ui.theme.NocturneTheme

private enum class Screen { Piano, Library, About }

class MainActivity : ComponentActivity() {

    private val vm: PianoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            NocturneTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.Piano) }
                Box(
                    Modifier.fillMaxSize().background(Nocturne.Charcoal)
                        .windowInsetsPadding(WindowInsets.displayCutout)
                ) {
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                        label = "screen",
                    ) { s ->
                        when (s) {
                            Screen.Piano -> MainScreen(vm, onOpenLibrary = { screen = Screen.Library })
                            Screen.Library -> LibraryScreen(vm, onBack = { screen = Screen.Piano }, onAbout = { screen = Screen.About })
                            Screen.About -> AboutScreen(vm, onBack = { screen = Screen.Library })
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
        vm.onBackground()
        super.onStop()
    }
}
