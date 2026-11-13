package com.example

import android.Manifest
import android.app.Application
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AssistantMode
import com.example.ui.MainViewModel
import com.example.ui.VoiceScreen
import com.example.ui.components.ChatInputBar
import com.example.ui.theme.JarvisTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KeyboardAndVoiceUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `AndroidManifest configures MainActivity with adjustResize soft input mode`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val pkgInfo = app.packageManager.getPackageInfo(
            app.packageName,
            android.content.pm.PackageManager.GET_ACTIVITIES
        )
        val mainActivityInfo: ActivityInfo? = pkgInfo.activities?.firstOrNull {
            it.name.endsWith("MainActivity")
        }
        assertTrue("MainActivity must be declared in AndroidManifest", mainActivityInfo != null)
        val adjustMask = mainActivityInfo!!.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE, adjustMask)
    }

    @Test
    fun `ChatInputBar clears input and invokes send callback cleanly on button and IME send`() {
        val sentMessages = mutableListOf<String>()

        composeRule.setContent {
            JarvisTheme(darkTheme = false) {
                ChatInputBar(
                    isListening = false,
                    onStartVoice = {},
                    onStopVoice = {},
                    onOpenLiveVoiceMode = {},
                    onSendMessage = { msg -> sentMessages.add(msg) },
                    isGenerating = false
                )
            }
        }

        composeRule.waitForIdle()
        // Empty input displays Live Voice Mode waveform button
        composeRule.onNodeWithTag("live_voice_mode_button").assertIsDisplayed()

        // Type text -> send button appears; clicking send submits & clears text back to waveform state
        composeRule.onNodeWithTag("chat_input_text_field").performTextInput("Namaste Jarvis")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("send_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("Namaste Jarvis"), sentMessages)
        composeRule.onNodeWithTag("live_voice_mode_button").assertIsDisplayed()

        // Pressing IME Send also submits & resets input
        composeRule.onNodeWithTag("chat_input_text_field").performTextInput("Kya haal hai")
        composeRule.onNodeWithTag("chat_input_text_field").performImeAction()
        composeRule.waitForIdle()

        assertEquals(listOf("Namaste Jarvis", "Kya haal hai"), sentMessages)
        composeRule.onNodeWithTag("live_voice_mode_button").assertIsDisplayed()
    }

    @Test
    fun `VoiceScreen shows honest Offline Mode notice with Auto switch and clean close`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)

        val viewModel = MainViewModel(app)
        viewModel.setAssistantMode(AssistantMode.OFFLINE)
        var navigatedBackToChat = false

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            JarvisTheme(darkTheme = false) {
                VoiceScreen(
                    viewModel = viewModel,
                    onNavigateToChat = { navigatedBackToChat = true }
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(200)

        // Never shows fake "Gemini offline" text; shows honest Offline Mode status & switch pill
        composeRule.onNodeWithTag("live_connection_badge").assertIsDisplayed()
        composeRule.onNodeWithText("Gemini Live • Offline Mode").assertIsDisplayed()
        composeRule.onNodeWithTag("switch_to_auto_live_button").assertIsDisplayed()

        // Mic mute button toggles cleanly
        assertEquals(false, viewModel.geminiLiveEngine.isMicMuted.value)
        composeRule.onNodeWithTag("voice_mic_mute_button").performClick()
        composeRule.mainClock.advanceTimeBy(100)
        assertEquals(true, viewModel.geminiLiveEngine.isMicMuted.value)

        // Black X button closes Live Voice and returns to chat
        composeRule.onNodeWithTag("voice_chatgpt_close_x_button").performClick()
        composeRule.mainClock.advanceTimeBy(100)
        assertTrue(navigatedBackToChat)
        assertNotEquals(
            com.example.service.LiveConnectionState.SPEAKING,
            viewModel.geminiLiveEngine.connectionState.value
        )
    }
}
