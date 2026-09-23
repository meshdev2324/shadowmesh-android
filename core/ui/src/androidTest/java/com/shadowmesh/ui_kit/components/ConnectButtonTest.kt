package com.shadowmesh.ui_kit.components

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import uniffi.shadowmesh.ConnectionStatus

class ConnectButtonTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun connectButton_isClickable() {
        var clicked = false
        composeTestRule.setContent {
            ConnectButton(
                status = ConnectionStatus.DISCONNECTED,
                onClick = { clicked = true }
            )
        }

        composeTestRule.onNodeWithContentDescription("Connect/Disconnect").performClick()
        assert(clicked)
    }

    @Test
    fun connectButton_showsCorrectContent() {
        composeTestRule.setContent {
            ConnectButton(
                status = ConnectionStatus.CONNECTED,
                onClick = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Connect/Disconnect").assertExists()
    }
}
