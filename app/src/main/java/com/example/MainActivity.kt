package com.example

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.example.service.configureAppCheck
import com.example.ui.AppScreenMode
import com.example.ui.ChatScreen
import com.example.ui.MainViewModel
import com.example.ui.VoiceScreen
import com.example.ui.theme.JarvisTheme

class MainActivity : FragmentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mandatory App Check initialization with launch intent before any UI or Firebase operations
        configureAppCheck(this, intent)

        enableEdgeToEdge()

        setContent {
            val userDarkModePreference by viewModel.isDarkMode.collectAsState()
            val effectiveDarkTheme = userDarkModePreference ?: isSystemInDarkTheme()
            val screenMode by viewModel.currentScreenMode.collectAsState()

            JarvisTheme(darkTheme = effectiveDarkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Crossfade(
                        targetState = screenMode,
                        label = "screen_mode_crossfade"
                    ) { mode ->
                        when (mode) {
                            AppScreenMode.VOICE -> {
                                VoiceScreen(
                                    viewModel = viewModel,
                                    onNavigateToChat = {
                                        viewModel.setScreenMode(AppScreenMode.CHAT)
                                    }
                                )
                            }
                            AppScreenMode.CHAT -> {
                                ChatScreen(
                                    viewModel = viewModel,
                                    activity = this@MainActivity,
                                    onNavigateToVoice = {
                                        viewModel.setScreenMode(AppScreenMode.VOICE)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
