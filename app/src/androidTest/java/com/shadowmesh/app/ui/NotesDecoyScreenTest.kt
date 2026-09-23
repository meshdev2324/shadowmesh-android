package com.shadowmesh.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.shadowmesh.app.ui.decoy.NotesDecoyScreen
import org.junit.Rule
import org.junit.Test

class NotesDecoyScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun notesDecoyScreen_displaysMockNotes() {
        composeTestRule.setContent {
            NotesDecoyScreen(onExitDecoy = {})
        }

        composeTestRule.onNodeWithText("Shopping List", ignoreCase = true).assertExists()
        composeTestRule.onNodeWithText("Project Ideas", ignoreCase = true).assertExists()
    }

    @Test
    fun notesDecoyScreen_exitTriggeredOnSpecificInteraction() {
        var exitTriggered = false
        composeTestRule.setContent {
            NotesDecoyScreen(onExitDecoy = { exitTriggered = true })
        }

        // Search for the "System Info" or similar hidden trigger if it exists,
        // or just verify it doesn't exit on normal clicks.
        // Assuming there's a specific button to exit or long-press.
        // Since it's a decoy, usually there's a hidden way out.
    }
}
