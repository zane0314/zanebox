package com.zane.zanebox

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.zane.zanebox.data.*
import com.zane.zanebox.ui.AppViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SelectedNodeDelayUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val vm get()=ViewModelProvider(compose.activity)[AppViewModel::class.java]
    private fun general() {
        if(compose.onAllNodesWithTag("tab_2").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithTag("tab_2").performClick()
        if(compose.onAllNodesWithTag("settings_general").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithTag("settings_general").performClick()
        compose.onNodeWithTag("subpage_list").performScrollToNode(hasTestTag("setting_autoTestSelectedNode"))
    }
    private fun home() {
        compose.onNodeWithTag("page_back").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("main_back").performClick()
    }
    @Test fun switchPersistsAndAutomaticResultNeverMovesTheRow() {
        val source=AppData(nodes=listOf(
            Node(8101,8101,"steady A","""{"type":"socks","server":"10.0.2.2","server_port":19081}""",ping=100,order=0,status=3),
            Node(8102,8101,"measured B","""{"type":"socks","server":"10.0.2.2","server_port":19092}""",ping=2000,order=1,status=3)),groups=listOf(Group(8101,"delay UI")),
            settings=mapOf("appLanguage" to "zh-CN","serviceMode" to "proxy","dnsRemote" to "local","selectedNodeId" to "8101","selectedGroupId" to "8101","browseGroupId" to "8101","sort_group_8101" to "true","testUrl" to "http://203.0.113.9:19093/measure","rulesUpdateInterval" to "off"))
        compose.runOnIdle {vm.edit(autoApply=false){source}}
        compose.waitUntil(15000){!vm.busy.value && vm.data.value.nodes.size==2}
        general()
        compose.onNodeWithTag("setting_autoTestSelectedNode").assertIsDisplayed().performClick()
        compose.waitUntil(10000){!vm.busy.value && !vm.data.value.bool("autoTestSelectedNode")}
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000){!vm.busy.value && !vm.data.value.bool("autoTestSelectedNode")}
        general();home()
        val initial=vm.service.testProgress.value.batchId
        compose.onNodeWithTag("node_8102").performClick()
        compose.waitUntil(10000){vm.data.value.selectedNodeId==8102L}
        Thread.sleep(1200)
        assertEquals(initial,vm.service.testProgress.value.batchId)
        assertEquals(2000,vm.data.value.nodes.last().ping)
        compose.onNodeWithTag("node_8101").performClick()
        compose.waitUntil(10000){vm.data.value.selectedNodeId==8101L}
        general()
        compose.onNodeWithTag("setting_autoTestSelectedNode").performClick()
        compose.waitUntil(10000){!vm.busy.value && vm.data.value.bool("autoTestSelectedNode")}
        home()
        val base=vm.service.testProgress.value.batchId
        val y=compose.onNodeWithTag("node_8102").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("node_8102").performClick()
        compose.waitUntil(15000){vm.service.testProgress.value.batchId>base && !vm.service.testProgress.value.running && vm.data.value.nodes.last().ping in 1..300}
        compose.onNodeWithTag("node_latency_8102").assertTextContains("ms",substring=true)
        assertEquals(y,compose.onNodeWithTag("node_8102").fetchSemanticsNode().boundsInRoot.top,1f)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000){!vm.busy.value && vm.data.value.nodes.size==2}
        assertTrue(vm.data.value.bool("autoTestSelectedNode"))
        assertTrue(compose.onNodeWithTag("node_8101").fetchSemanticsNode().boundsInRoot.top<compose.onNodeWithTag("node_8102").fetchSemanticsNode().boundsInRoot.top)
        // Explicitly reapplying latency order must release the saved rank, even though the mode is already true.
        compose.runOnIdle {vm.setting("sort_group_8101","true")}
        compose.waitUntil(10000){!vm.busy.value && vm.data.value.settings.keys.none {it.startsWith("sort_ping_")}}
        assertTrue(compose.onNodeWithTag("node_8102").fetchSemanticsNode().boundsInRoot.top<compose.onNodeWithTag("node_8101").fetchSemanticsNode().boundsInRoot.top)
    }
}
