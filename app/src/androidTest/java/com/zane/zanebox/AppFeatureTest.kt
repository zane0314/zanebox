package com.zane.zanebox

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.zane.zanebox.data.*
import com.zane.zanebox.ui.AppViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AppFeatureTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val ten=(0..9).map{"com.google.android.linksfixture.p$it"}.toSet()
    private fun scroll(tag:String)=compose.onNodeWithTag(if(compose.onAllNodesWithTag("subpage_list").fetchSemanticsNodes().isNotEmpty())"subpage_list" else "page_list").performScrollToNode(hasTestTag(tag))
    private fun seed():AppViewModel {
        val vm=ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnIdle{vm.edit(autoApply=false){AppData(nodes=listOf(Node(901,901,"测试出口","""{"type":"socks","server":"10.0.2.2","server_port":19081}""")),groups=listOf(Group(901,"应用选择验收")),settings=mapOf("appLanguage" to "zh-CN","selectedNodeId" to "901","perAppEnabled" to "true","perAppMode" to "include","perAppPackages" to ten.joinToString("\n"),"smartCustom.ai.packages" to "com.google.android.linksfixture.p0\ncom.example.linkslocal.one","smartRules.ai" to "","smart.ai.target" to "node:901"))}}
        compose.waitUntil(15000){!vm.busy.value && vm.data.value.selectedNodeId==901L}
        return vm
    }
    @Test fun actualPagesSaveIndependentListsAndReopenHistoricalSelection() {
        val vm=seed()
        assertTrue(compose.onNodeWithTag("tab_1").getUnclippedBoundsInRoot().left<compose.onNodeWithTag("connect_toggle").getUnclippedBoundsInRoot().left)
        assertTrue(compose.onNodeWithTag("tab_2").getUnclippedBoundsInRoot().left>compose.onNodeWithTag("connect_toggle").getUnclippedBoundsInRoot().left)
        compose.onNodeWithTag("tab_1").performClick()
        scroll("smart_ai");compose.onNodeWithTag("smart_ai").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick){it()}
        scroll("smart_apps_ai");compose.onNodeWithTag("smart_apps_ai").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick){it()}
        scroll("apps_auto");compose.waitUntil(15000){!compose.onNodeWithTag("apps_auto").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)}
        compose.onNodeWithTag("apps_auto").performClick();compose.onNodeWithTag("apps_save").performClick()
        compose.waitUntil(15000){!vm.busy.value && vm.data.value.setting("smartCustom.ai.packages").lines().toSet()==ten+"com.example.linkslocal.one"}
        assertEquals(ten,vm.data.value.setting("perAppPackages").lines().toSet())
        compose.onNodeWithTag("smart_apps_ai").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick){it()};scroll("apps_selection_count");compose.onNodeWithText("已选择 10 个应用").assertIsDisplayed()
        scroll("apps_inactive");compose.onNodeWithTag("apps_inactive").assertTextContains("1 个历史选择",substring=true)
        compose.onNodeWithTag("page_back").performClick();compose.onNodeWithTag("main_back").performClick()
        compose.onNodeWithTag("tab_2").performClick();compose.onNodeWithTag("settings_general").performClick();scroll("setting_perAppEnabled");compose.onNodeWithTag("setting_perAppEnabled").performClick()
        compose.onNodeWithTag("apps_mode_exclude").performClick();compose.waitUntil(10000){vm.data.value.setting("perAppMode")=="exclude"}
        compose.onNodeWithTag("page_back").performClick();compose.onNodeWithTag("page_back").performClick();compose.onNodeWithTag("main_back").performClick();compose.onNodeWithTag("tab_1").performClick()
        scroll("smart_ai");compose.onNodeWithTag("smart_ai").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick){it()}
        scroll("smart_apps_ai");compose.onNodeWithTag("smart_apps_ai").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick){it()};scroll("apps_selection_count");compose.onNodeWithText("已选择 1 个应用").assertIsDisplayed()
        assertEquals(ten+"com.example.linkslocal.one",vm.store.snapshot().setting("smartCustom.ai.packages").lines().toSet())
        assertEquals(ten,vm.store.snapshot().setting("perAppPackages").lines().toSet())
    }
}
