package com.vera.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI tests for VERA's main screens.
 *
 * Run on device: ./gradlew connectedAndroidTest
 *
 * Covers:
 *   - MainActivity launch without crash
 *   - Voice screen content descriptions present
 *   - Mic button is clickable
 *   - No duplicate simultaneous state labels
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class VeraUiTestSuite {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    // ── Launch ──────────────────────────────────────────────────────────────

    @Test
    fun activity_launches_without_crash() {
        // If MainActivity crashes on launch this test fails with a JUnit error.
        // Just assert the window is at least displaying something.
        composeRule.onRoot().assertExists()
    }

    // ── Navigation / screen presence ────────────────────────────────────────

    @Test
    fun vera_branding_visible_on_main_screen() {
        // VERA logo text shown on VoiceScreen
        composeRule.onNodeWithText("VERA", useUnmergedTree = true).assertExists()
    }

    // ── Mic button ──────────────────────────────────────────────────────────

    @Test
    fun mic_button_exists_and_is_enabled() {
        composeRule
            .onNodeWithContentDescription("Voice", useUnmergedTree = true)
            .assertExists()
            .assertIsEnabled()
    }

    @Test
    fun mic_button_responds_to_click_without_crash() {
        composeRule
            .onNodeWithContentDescription("Voice", useUnmergedTree = true)
            .assertExists()
            .performClick()
        // Give UI a moment to react; no crash = pass
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onRoot().assertExists()
    }

    @Test
    fun mic_button_rapid_clicks_do_not_crash() {
        val micNode = composeRule.onNodeWithContentDescription("Voice", useUnmergedTree = true)
        repeat(6) {
            micNode.performClick()
            composeRule.mainClock.advanceTimeBy(100)
        }
        composeRule.onRoot().assertExists()
    }

    // ── State labels ────────────────────────────────────────────────────────

    @Test
    fun listening_and_thinking_labels_not_shown_simultaneously() {
        val listening = composeRule.onAllNodesWithText("Listening…", useUnmergedTree = true)
        val thinking  = composeRule.onAllNodesWithText("Thinking…",  useUnmergedTree = true)
        val listeningCount = runCatching { listening.fetchSemanticsNodes().size }.getOrDefault(0)
        val thinkingCount  = runCatching { thinking.fetchSemanticsNodes().size }.getOrDefault(0)
        assert(listeningCount == 0 || thinkingCount == 0) {
            "Both 'Listening…' and 'Thinking…' visible at same time — impossible state"
        }
    }

    // ── Navigation items ────────────────────────────────────────────────────

    @Test
    fun history_nav_item_exists() {
        composeRule
            .onNodeWithContentDescription("History", useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun settings_nav_item_exists() {
        composeRule
            .onNodeWithContentDescription("Settings", useUnmergedTree = true)
            .assertExists()
    }

    // ── Avatar container ────────────────────────────────────────────────────

    @Test
    fun avatar_container_renders_without_crash() {
        // Advance clock so Compose finishes first frame and any LaunchedEffects fire
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.onRoot().assertExists()
    }
}
