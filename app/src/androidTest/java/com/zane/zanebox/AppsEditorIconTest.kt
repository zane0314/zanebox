package com.zane.zanebox

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.zane.zanebox.ui.AppsEditor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppsEditorIconTest {
    @get:Rule val compose=createComposeRule()

    @Test fun ownApplicationIconLoadsAndSelectionStillSaves() {
        var saved:Set<String>?=null
        compose.setContent {
            MaterialTheme { AppsEditor(emptySet(),onDismiss={},save={saved=it}) }
        }
        compose.onNodeWithTag("apps_search").performTextInput("com.zane.zanebox")
        compose.waitUntil(10000) { compose.onAllNodesWithTag("app_com.zane.zanebox").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("subpage_list").performScrollToNode(hasTestTag("app_com.zane.zanebox"))
        try {
            compose.waitUntil(10000) { compose.onAllNodesWithTag("app_icon_loaded_com.zane.zanebox",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure:Throwable) {
            compose.onRoot(useUnmergedTree=true).printToLog("AppsEditorIconTest")
            throw failure
        }
        compose.onNodeWithTag("app_icon_loaded_com.zane.zanebox",useUnmergedTree=true).assertIsDisplayed()
        compose.onNodeWithTag("app_com.zane.zanebox").performClick()
        compose.onNodeWithTag("subpage_list").performScrollToIndex(0)
        compose.waitUntil(5000) { compose.onNodeWithTag("apps_save").isDisplayed() }
        compose.onNodeWithTag("apps_save").performClick()
        compose.runOnIdle { assertEquals(setOf("com.zane.zanebox"),saved) }
    }
}
