package com.ossm.remote.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ossm.remote.ui.components.ControlSlider
import com.ossm.remote.ui.theme.OssmRemoteTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompactControlLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun portrait_has_no_page_scroll_and_keeps_stop_and_control_visible() {
        setCompactLayout(widthDp = 360, heightDp = 640)

        assertCompactSurface()
    }

    @Test
    fun landscape_has_no_page_scroll_and_keeps_stop_and_control_visible() {
        setCompactLayout(widthDp = 640, heightDp = 360)

        assertCompactSurface()
    }

    @Test
    fun scalar_slider_semantics_target_is_at_least_56dp_high() {
        composeRule.setContent {
            OssmRemoteTheme {
                ControlSlider(
                    label = "Speed",
                    value = 0.5f,
                    onValueChange = {},
                    onValueCommit = {}
                )
            }
        }

        val bounds = composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .getUnclippedBoundsInRoot()
        assertTrue("Slider target was ${bounds.height}", bounds.height >= 56.dp)
    }

    private fun setCompactLayout(widthDp: Int, heightDp: Int) {
        composeRule.setContent {
            OssmRemoteTheme {
                CompactControlScaffold(
                    modifier = Modifier.requiredSize(widthDp.dp, heightDp.dp),
                    actionRail = {
                        Box(Modifier.testTag("test_stop"))
                    },
                    content = {
                        Box(Modifier.testTag("test_control"))
                    }
                )
            }
        }
    }

    private fun assertCompactSurface() {
        composeRule.onNodeWithTag("control_main_surface", useUnmergedTree = true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.ScrollBy))
        composeRule.onNodeWithTag("test_stop").assertIsDisplayed()
        composeRule.onNodeWithTag("test_control").assertIsDisplayed()
    }
}
