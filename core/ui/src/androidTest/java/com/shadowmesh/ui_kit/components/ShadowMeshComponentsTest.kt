package com.shadowmesh.ui_kit.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.shadowmesh.core_vpn.domain.MeshNodeEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import uniffi.shadowmesh.ConnectionStatus

/**
 * UI tests for ShadowMesh components.
 * SOP 02/04: Verify tactile interactions and premium visuals.
 *
 * ## The node list is built on `MeshNodeEntry`, and that is the assertion
 *
 * This file used to populate `endpoint` and `publicKey` on each node. Those are
 * the connect material — where a node is, and the key that lets you reach it.
 * The anonymous directory projection withholds both on purpose (L10, F59), and
 * `MeshNodeEntry` is shaped so that it is **unable** to carry either.
 *
 * So the old test could only have been made to compile by widening the
 * projection to re-admit the credentials the projection exists to withhold.
 * That is not a fix, it is the defect re-opened at the UI layer, so the test
 * was rebuilt instead.
 *
 * What the assertions are now, which is the shape change:
 *
 *  1. **The row renders geography, not node identity.** There is no `name`
 *     field; `displayName` is derived from `region`/`country`, which is what
 *     the server published.
 *  2. **The row renders the tri-state transport word**, not a latency figure
 *     and not a route.
 *  3. **Nothing connect-shaped appears anywhere in the sheet** — a port, or a
 *     key-shaped token. That last one is the F59 pin at the rendered surface:
 *     it is the assertion that fails if someone widens `MeshNodeEntry` to make
 *     an old test compile again.
 */
class ShadowMeshComponentsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // `country` is the ISO-3166 alpha-2 code the country ladder matches on.
    // `displayName` falls back region -> country -> id, so `region` is what
    // the row prints.
    private val testNodes = listOf(
        MeshNodeEntry(id = "1", country = "US", region = "San Francisco", isOnline = true),
        MeshNodeEntry(id = "2", country = "GB", region = "London", isOnline = true),
    )

    /** The sheet under test, driven through every parameter it actually takes. */
    @Composable
    private fun NodeSheetUnderTest(onNodeSelected: (MeshNodeEntry) -> Unit = {}) {
        NodeSelectionSheet(
            nodes = testNodes,
            selectedNode = null,
            favoriteNodeIds = emptySet(),
            searchQuery = "",
            isLoading = false,
            loadError = null,
            isDiscovering = false,
            onSearchQueryChange = {},
            onToggleFavorite = {},
            onRefresh = {},
            onSelectBest = {},
            onNodeSelected = onNodeSelected,
            onDismissRequest = {},
            themeColor = Color.Blue,
        )
    }

    @Test
    fun nodeSelectionSheet_displaysNodesAndHandlesSelection() {
        var selectedNode: MeshNodeEntry? = null

        composeTestRule.setContent {
            NodeSheetUnderTest(onNodeSelected = { selectedNode = it })
        }

        // Verify nodes are displayed
        composeTestRule.onNodeWithText("San Francisco").assertExists()
        composeTestRule.onNodeWithText("London").assertExists()

        // Select a node
        composeTestRule.onNodeWithText("San Francisco").performClick()

        // JUnit assertions, not `kotlin.assert`. The previous version used
        // `assert(...)`, which is compiled out unless assertions are enabled
        // for the test JVM — so it could have passed by asserting nothing.
        assertNotNull("selecting a row must hand back the directory entry", selectedNode)
        assertEquals("1", selectedNode?.id)
    }

    @Test
    fun nodeSelectionSheet_renders_geography_and_no_connect_material() {
        composeTestRule.setContent { NodeSheetUnderTest() }

        // (1)+(2): the row is geography plus a transport word. `CHECKING` is the
        // honest pre-stage-2 value — the directory withheld the endpoint, so
        // whether this node serves REALITY is not yet known. `DIRECT` here
        // would be a confident label with no evidence behind it.
        composeTestRule
            .onNodeWithText("United States • San Francisco • CHECKING")
            .assertExists()

        // (3): the F59 pin. A port or a key-shaped token must not appear on the
        // surface at all. This is the assertion that goes red if anyone widens
        // the projection to resurrect the old fixture.
        composeTestRule
            .onAllNodes(hasText(":51820", substring = true))
            .assertCountEquals(0)
        composeTestRule
            .onAllNodes(hasText("pubkey", ignoreCase = true, substring = true))
            .assertCountEquals(0)
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

    /**
 * This test previously asserted `"45MS MESH"` on a connected orb. **That
 * assertion encoded the defect** and it is updated deliberately rather than
 * deleted, because a deleted assertion is how the next one comes back.
 *
 * The figure was the **mean of 5 sequential full HTTP round-trips to the
 * control-plane API** (`detector.rs:82-97` -> `api_client.rs:1366-1376`), frozen
 * at `detect()`. Both numbers are real and they measure different things; the
 * bug was the noun. `"45MS MESH"` reads as tunnel RTT, which is a quantity the
 * core does not expose at all, so there was no honest value to substitute —
 * only a removal.
 *
 * So the contract asserted now is two-part: the latency readout is **absent**
 * from the orb (assertDoesNotExist, so nobody re-adds a number), and the
 * status word and the merged accessibility node are **present**.
 */
@Test
    fun liquidStatusOrb_displaysCorrectTextForStates() {
        composeTestRule.setContent {
            LiquidStatusOrb(
                status = ConnectionStatus.CONNECTED,
                isConnecting = false
            )
        }

        // `useUnmergedTree = true`, and this is load-bearing.
        //
        // The orb's inner Box carries `clearAndSetSemantics`, which merges the
        // subtree *and discards* whatever the children contributed, replacing it
        // with one authored sentence. So "YOU ARE PROTECTED" and "SYNCED" exist
        // on screen but are deliberately **absent from the merged tree** — they
        // are pixels, not something a screen reader reads. Asserting them on the
        // merged tree fails with "the unmerged tree contains 1 node that
        // matches", and the fix is the flag, not the component.
        //
        // These two assertions had never been executed and were written against
        // the merged tree, so they were wrong before they were ever run. See
        // `OrbContractTest`'s header for the broader lesson.
        composeTestRule
            .onNodeWithText("YOU ARE PROTECTED", useUnmergedTree = true)
            .assertExists()

        // The readout is gone, and this is the assertion that keeps it gone.
        // Asserting its absence is the whole point: a test that only checked the
        // new string would pass unchanged if someone re-added the old one.
        composeTestRule
            .onNodeWithText("45MS MESH", useUnmergedTree = true)
            .assertDoesNotExist()
        composeTestRule
            .onNodeWithText("SYNCED", useUnmergedTree = true)
            .assertExists()

        // The orb has exactly one accessibility node, and it is peer-free:
        // it says whether traffic is routed, never where or via whom. This one
        // is a *merged*-tree assertion, because the merged tree is what a
        // screen reader actually consumes.
        composeTestRule
            .onNodeWithContentDescription("Protected. Traffic is routed through the mesh.")
            .assertExists()

        // And the converse, which is the privacy half of the same contract: the
        // visual words are not merged into the spoken node. Non-vacuous because
        // the sentence above is proven to exist first.
        composeTestRule.onNodeWithText("YOU ARE PROTECTED").assertDoesNotExist()
        composeTestRule.onNodeWithText("SYNCED").assertDoesNotExist()
    }

    /**
     * Split out from the connected-state test above rather than being a second
     * `setContent` in the same method.
     *
     * `ComposeTestRule.setContent` may be called **once** per test — a second
     * call throws `IllegalStateException: has already set content`. The original
     * test drove both states from one method and would have failed on that, so
     * this state has never actually been asserted either. Driving it from a
     * state holder instead would also work, but then a failure names the
     * transition rather than the state.
     */
    @Test
    fun liquidStatusOrb_displaysConnectingText() {
        composeTestRule.setContent {
            LiquidStatusOrb(
                status = ConnectionStatus.DISCONNECTED,
                isConnecting = true
            )
        }

        composeTestRule
            .onNodeWithText("SHIELD", useUnmergedTree = true)
            .assertExists()
        composeTestRule
            .onNodeWithText("YOU ARE PROTECTED", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun liquidStatusOrb_never_speaks_a_latency_figure() {
        // The regression guard, separate from the test above so that a failure
        // names the invariant rather than the state. Any `$…MS`-shaped text on
        // the orb is a re-introduction of a metric the orb cannot honestly show.
        composeTestRule.setContent {
            LiquidStatusOrb(
                status = ConnectionStatus.CONNECTED,
                isConnecting = false
            )
        }

        // Unmerged, or this assertion is vacuous: `clearAndSetSemantics` strips
        // the orb's entire subtree out of the merged tree, so a merged-tree
        // search for "MS" can never match anything and would pass no matter what
        // the component rendered.
        composeTestRule
            .onNodeWithText("SYNCED", useUnmergedTree = true)
            .assertExists()
        composeTestRule
            .onAllNodes(hasText("MS", substring = true), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun resilienceIndicator_displaysCorrectText() {
        composeTestRule.setContent {
            ResilienceIndicator()
        }

        composeTestRule.onNodeWithText("RESILIENCE ACTIVE").assertExists()
    }
}
