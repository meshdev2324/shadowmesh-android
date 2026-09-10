package com.shadowmesh.ui_kit.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import uniffi.shadowmesh.ConnectionStatus
import uniffi.shadowmesh.VpnNode

/**
 * UI tests for ShadowMesh components.
 * SOP 02/04: Verify tactile interactions and premium visuals.
 */
class ShadowMeshComponentsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testNodes = listOf(
        VpnNode(
            id = "1", 
            name = "San Francisco", 
            region = "US", 
            country = "USA", 
            endpoint = "1.1.1.1:51820", 
            publicKey = "pubkey1", 
            load = 20u, 
            latency = 40u, 
            isOnline = true
        ),
        VpnNode(
            id = "2", 
            name = "London", 
            region = "GB", 
            country = "United Kingdom", 
            endpoint = "1.1.1.2:51820", 
            publicKey = "pubkey2", 
            load = 50u, 
            latency = 120u, 
            isOnline = true
        )
    )

    @Test
    fun nodeSelectionSheet_displaysNodesAndHandlesSelection() {
        var selectedNode: VpnNode? = null
        
        composeTestRule.setContent {
            NodeSelectionSheet(
                nodes = testNodes,
                selectedNode = null,
                onNodeSelected = { selectedNode = it },
                onDismissRequest = {},
                themeColor = Color.Blue
            )
        }

        // Verify nodes are displayed
        composeTestRule.onNodeWithText("San Francisco").assertExists()
        composeTestRule.onNodeWithText("London").assertExists()
        
        // Select a node
        composeTestRule.onNodeWithText("San Francisco").performClick()
        
        assert(selectedNode?.id == "1")
    }

    @Test
    fun slideToConnect_handlesSwipeInteraction() {
        var swipeCompleted = false
        
        composeTestRule.setContent {
            SlideToConnect(
                status = ConnectionStatus.DISCONNECTED,
                isConnecting = false,
                themeColor = Color.Blue,
                onSwipeComplete = { swipeCompleted = true }
            )
        }

        // Verify initial state
        composeTestRule.onNodeWithText("SLIDE TO PROTECT").assertExists()
        
        // Perform swipe
        composeTestRule.onNodeWithText("SLIDE TO PROTECT").performTouchInput {
            swipeRight()
        }
        
        composeTestRule.waitForIdle()
    }

    @Test
    fun liquidStatusOrb_displaysCorrectTextForStates() {
        composeTestRule.setContent {
            LiquidStatusOrb(
                status = ConnectionStatus.CONNECTED,
                latency = 45.0,
                isConnecting = false
            )
        }

        composeTestRule.onNodeWithText("YOU ARE PROTECTED").assertExists()
        composeTestRule.onNodeWithText("45MS MESH").assertExists()

        composeTestRule.setContent {
            LiquidStatusOrb(
                status = ConnectionStatus.DISCONNECTED,
                latency = 0.0,
                isConnecting = true
            )
        }

        composeTestRule.onNodeWithText("SHIELD").assertExists()
        composeTestRule.onNodeWithText("READY").assertExists()
    }

    @Test
    fun resilienceIndicator_displaysCorrectText() {
        composeTestRule.setContent {
            ResilienceIndicator()
        }

        composeTestRule.onNodeWithText("RESILIENCE ACTIVE").assertExists()
    }
}
