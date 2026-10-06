package com.zane.zanebox

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.zane.zanebox.data.*
import com.zane.zanebox.ui.AppViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NodeManagementTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun deletingAHopWarnsAndCleansRecursiveChainsAndTheirReferences() {
        val vm=ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnIdle {vm.edit {AppData(nodes=listOf(Node(1,1,"hop","""{"type":"socks","server":"127.0.0.1","server_port":9}"""),Node(2,1,"保留","""{"type":"socks","server":"127.0.0.1","server_port":9}"""),Node(3,2,"链一","""{"type":"chain","node_ids":[1]}"""),Node(4,2,"链二","""{"type":"chain","node_ids":[3]}""")),groups=listOf(Group(1,"hop组",frontProxy=3),Group(2,"链组")),rules=listOf(RouteRule(1,"chain",ipCidrs="203.0.113.9/32",outbound="node:4")),merges=listOf(MergeGroup(1,"汇总",nodeIds=listOf(1,2,3,4),selectedId=4)),settings=mapOf("appLanguage" to "zh-CN","selectedNodeId" to "4","selectedGroupId" to "2","smart.youtube.target" to "node:4","nodeRegion.4" to "jp"))}}
        compose.waitUntil(10000){!vm.busy.value && vm.data.value.nodes.size==4}
        compose.onNodeWithTag("home_node_delete_1").performClick()
        compose.onNodeWithText("删除 1 个节点？同时删除依赖它们的 2 个代理链。").assertIsDisplayed()
        compose.onNodeWithText("确认").performClick()
        compose.waitUntil(10000){!vm.busy.value && vm.data.value.nodes.size==1}
        val data=vm.data.value;assertEquals(2L,data.selectedNodeId);assertEquals(0L,data.groups.first().frontProxy);assertEquals("off",data.setting("smart.youtube.target"));assertFalse(data.settings.containsKey("nodeRegion.4"));assertEquals("proxy",data.rules.single().outbound);assertEquals(listOf(2L),data.merges.single().nodeIds)
        com.zane.zanebox.config.ConfigBuilder.build(data)
    }
    @Test fun homeAndSettingsDeleteSingleOrSelectedNodesAndCancelKeepsData() {
        val vm=ViewModelProvider(compose.activity)[AppViewModel::class.java]
        fun seed() {
            compose.runOnIdle { vm.edit { AppData(nodes=(1L..4L).map { Node(it,1,"节点 $it","""{"type":"socks","server":"127.0.0.1","server_port":9}""") },groups=listOf(Group(1,"管理组")),rules=listOf(RouteRule(1,"引用",outbound="node:1")),merges=listOf(MergeGroup(1,"汇总",nodeIds=listOf(1,2,3,4))),settings=mapOf("selectedNodeId" to "1","appLanguage" to "zh-CN")) } }
            compose.waitUntil(15000){!vm.busy.value && vm.data.value.nodes.size==4}
            compose.waitUntil(7000){compose.onAllNodesWithText("已保存，请应用修改使配置生效").fetchSemanticsNodes().isEmpty()}
        }
        fun deleted(count:Int) {compose.waitUntil(10000){!vm.busy.value && vm.data.value.nodes.size==count}}
        seed()
        compose.onNodeWithTag("home_node_delete_4").performClick();compose.onNodeWithText("取消").performClick();assertEquals(4,vm.data.value.nodes.size)
        compose.onNodeWithTag("home_node_delete_4").performClick();compose.onNodeWithText("确认").performClick();deleted(3)
        compose.onNodeWithTag("home_multiselect").performClick()
        for(id in listOf(1,2))compose.onNodeWithTag("home_node_check_$id").performClick()
        compose.onNodeWithTag("home_delete_selected").performClick();compose.onNodeWithText("取消").performClick();assertEquals(3,vm.data.value.nodes.size)
        compose.onNodeWithTag("home_delete_selected").performClick();compose.onNodeWithText("确认").performClick();deleted(1)
        assertEquals(3L,vm.data.value.selectedNodeId);assertEquals("proxy",vm.data.value.rules.single().outbound);assertEquals(listOf(3L),vm.data.value.merges.single().nodeIds)
        seed()
        compose.onNodeWithTag("tab_2").performClick();compose.onNodeWithTag("settings_groups").performClick()
        compose.onNodeWithTag("manage_group_nodes_1").performClick()
        compose.onNodeWithTag("manage_node_delete_4").performClick();compose.onNodeWithText("确认").performClick();deleted(3)
        compose.onNodeWithTag("manage_1_multiselect").performClick()
        for(id in listOf(1,2))compose.onNodeWithTag("manage_node_check_$id").performClick()
        compose.onNodeWithTag("manage_1_delete_selected").performClick();compose.onNodeWithText("取消").performClick();assertEquals(3,vm.data.value.nodes.size)
        compose.onNodeWithTag("manage_1_delete_selected").performClick();compose.onNodeWithText("确认").performClick();deleted(1)
        assertEquals(listOf(3L),vm.data.value.nodes.map { it.id });assertEquals(1,vm.data.value.groups.size)
    }
}
