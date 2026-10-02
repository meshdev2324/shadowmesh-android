package com.shadowmesh.ui_kit.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

class GradientCircularProgressIndicatorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun gradientCircularProgressIndicator_isDisplayed() {
        composeTestRule.setContent {
            GradientCircularProgressIndicator(
                modifier = Modifier.testTag("gradient_spinner"),
                colors = listOf(Color.Blue, Color.Cyan),
                strokeWidth = 4.dp
            )
        }

        composeTestRule.onNodeWithTag("gradient_spinner").assertIsDisplayed()
    }
}
