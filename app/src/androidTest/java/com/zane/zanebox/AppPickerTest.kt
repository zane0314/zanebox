package com.zane.zanebox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.config.*
import com.zane.zanebox.data.AppData
import com.zane.zanebox.ui.AppsEditor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AppPickerTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val ten=(0..9).map{"com.google.android.linksfixture.p$it"}.toSet()
    private val local=setOf("com.example.linkslocal.one","com.example.linkslocal.two")
    private fun scroll(tag:String)=compose.onNodeWithTag("subpage_list").performScrollToNode(hasTestTag(tag))
    private fun click(tag:String){if(tag!="apps_save")scroll(tag);compose.onNodeWithTag(tag).performClick()}
    private fun clipboard(text:String)=compose.runOnIdle { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("fixture",text)) }
    private fun loaded(){scroll("apps_auto");compose.waitUntil(15000){!compose.onNodeWithTag("apps_auto").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)};scroll("apps_system");compose.onNodeWithTag("apps_system").performClick()}

    @Test fun whitelistTenAutoPasteSaveAndBlacklistExclusionShareTheSameScope() {
        var scope by mutableStateOf(AppData(settings=mapOf("perAppEnabled" to "true","perAppMode" to "include","perAppPackages" to ten.joinToString("\n"))))
        var saved:Set<String>?=null
        compose.setContent{MaterialTheme{AppsEditor(emptySet(),onDismiss={},scopeData=scope,save={saved=it})}}
        loaded();click("apps_auto")
        scroll("apps_selection_count");compose.onNodeWithText("已选择 10 个应用").assertIsDisplayed()
        click("apps_copy")
        compose.runOnIdle{assertEquals(ten,(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString().lines().toSet())}
        click("apps_clear");clipboard((ten+local+"invalid!").joinToString("\n"));click("apps_import")
        scroll("apps_note");compose.onNodeWithTag("apps_note").assertTextContains("跳过 3",substring=true)
        click("apps_save");compose.runOnIdle{assertEquals(ten,saved)}
        compose.runOnIdle{scope=scope.copy(settings=scope.settings+("perAppMode" to "exclude"))}
        scroll("apps_search");compose.onNodeWithTag("apps_search").performTextReplacement("com.google.android.linksfixture")
        compose.onNodeWithTag("apps_empty").assertExists()
        compose.onNodeWithTag("apps_search").performTextReplacement("com.example.linkslocal")
        click("apps_select_visible");click("apps_copy")
        compose.runOnIdle{assertEquals(ten+local,(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString().lines().toSet())}
        click("apps_save");compose.runOnIdle{assertEquals(ten+local,saved)}
        scroll("apps_selection_count");compose.onNodeWithText("已选择 2 个应用").assertIsDisplayed()
        compose.runOnIdle{scope=scope.copy(settings=scope.settings+("perAppEnabled" to "false"))}
        scroll("apps_selection_count");compose.onNodeWithText("已选择 12 个应用").assertIsDisplayed()
    }

    @Test fun manualSelectionMovesIntoAlphabeticalSlotAndKeepsPasteSelection() {
        var saved:Set<String>?=null
        val z="com.google.android.linksfixture.p9"
        val a="com.google.android.linksfixture.p0"
        compose.setContent{MaterialTheme{AppsEditor(setOf(z),onDismiss={},save={saved=it})}}
        loaded();scroll("apps_search");compose.onNodeWithTag("apps_search").performTextInput("com.google.android.linksfixture")
        click("app_$a");compose.waitForIdle()
        scroll("app_$a");val top=compose.onNodeWithTag("app_$a").getUnclippedBoundsInRoot().top
        val below=compose.onNodeWithTag("app_$z").getUnclippedBoundsInRoot().top
        assertTrue("已选仍需按名称序移动",top<below)
        repeat(4){compose.onNodeWithTag("app_$a").performClick();compose.waitForIdle();scroll("app_$a")}
        clipboard("com.example.linkslocal.one\ncom.example.missing");click("apps_import");click("apps_save")
        compose.runOnIdle{assertEquals(setOf(a,z,"com.example.linkslocal.one"),saved)}
    }
}
