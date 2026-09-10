package com.shadowmesh.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shadowmesh.app.ui.theme.ShadowMeshTheme
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.shadowmesh.ConnectionStatus

@RunWith(AndroidJUnit4::class)
class MainScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun mainScreen_showsStatus() {
        val viewModel = mockk<VPNManagerViewModel>(relaxed = true)
        val uiState = VPNUiState(status = ConnectionStatus.DISCONNECTED)

        // Mock state flow
        // Note: Real state flow mocking is complex in instrumented tests,
        // usually we use a fake or a real viewmodel with mocked repository

        composeTestRule.setContent {
            ShadowMeshTheme {
                MainScreen(viewModel = viewModel)
            }
        }

        composeTestRule.onNodeWithText("SHADOW MESH").assertExists()
        composeTestRule.onNodeWithText("CONNECT TO MESH", ignoreCase = true).assertExists()
    }

    @Test
    fun clickingNetwork_showsStatusScreen() {
        val viewModel = mockk<VPNManagerViewModel>(relaxed = true)

        composeTestRule.setContent {
            ShadowMeshTheme {
                MainScreen(viewModel = viewModel)
            }
        }

        composeTestRule.onNodeWithText("NETWORK").performClick()
        composeTestRule.onNodeWithText("ENTRY NODE").assertExists()
    }
}
