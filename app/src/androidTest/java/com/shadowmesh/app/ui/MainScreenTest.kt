package com.shadowmesh.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.shadowmesh.app.MainScreen
import com.shadowmesh.app.VPNManagerViewModel
import com.shadowmesh.app.VPNUiState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import uniffi.shadowmesh.*

class MainScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun mainScreen_displaysDisconnectedStatus() {
        val mockViewModel = mockk<VPNManagerViewModel>(relaxed = true)
        val uiState = VPNUiState(status = ConnectionStatus.DISCONNECTED)
        every { mockViewModel.uiState } returns MutableStateFlow(uiState)

        composeTestRule.setContent {
            MainScreen(viewModel = mockViewModel)
        }

        // Status shows "IDLE" in the new terminal UI when disconnected
        composeTestRule.onNodeWithText("IDLE", ignoreCase = true).assertExists()
    }

    @Test
    fun mainScreen_displaysConnectedStatus() {
        val mockViewModel = mockk<VPNManagerViewModel>(relaxed = true)
        val uiState = VPNUiState(status = ConnectionStatus.CONNECTED)
        every { mockViewModel.uiState } returns MutableStateFlow(uiState)

        composeTestRule.setContent {
            MainScreen(viewModel = mockViewModel)
        }

        composeTestRule.onNodeWithText("CONNECTED", ignoreCase = true).assertExists()
    }
}
