package com.zane.zanebox

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.zane.zanebox.ui.AppViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TargetLatencyUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val vm get()=ViewModelProvider(compose.activity)[AppViewModel::class.java]
    private fun saved()=compose.waitUntil(15000){!vm.busy.value && vm.data.value.nodes.any{it.id==6101L}}
    private fun measured(previousBatch:Long,ids:Set<Long>) {
        compose.waitUntil(20000) {vm.service.testProgress.value.batchId>previousBatch && !vm.service.testProgress.value.running && vm.service.testResults.value.keys==ids}
        compose.waitUntil(10000){ids.all { id->vm.data.value.nodes.first{it.id==id}.ping==vm.service.testResults.value[id] }}
    }
    @Test fun policyAndMergeLatencyClicksMeasureTheirMembersAndKeepTargetButtons() {
        compose.runOnIdle{vm.edit{TargetLatencyFixture.data("proxy")}}
        saved()
        compose.onNodeWithTag("tab_1").performClick()
        compose.onNodeWithTag("smart_latency_youtube").performScrollTo().assertTextContains("5 ms",substring=true)
        val first=vm.service.testProgress.value.batchId
        compose.onNodeWithTag("smart_latency_youtube").performClick()
        measured(first,setOf(6101L,6102L))
        assertEquals("group:6101",vm.store.snapshot().setting("smart.youtube.target"))
        assertEquals(6102L,vm.store.snapshot().selectedNodeId)
        compose.onNodeWithTag("smart_target_youtube").performClick()
        compose.onNodeWithText("分流目标").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("smart_merges").performScrollTo().performClick()
        compose.onNodeWithTag("merge_latency_6105").assertTextContains("ms",substring=true)
        val second=vm.service.testProgress.value.batchId
        compose.onNodeWithTag("merge_latency_6105").performClick()
        measured(second,setOf(6101L,6102L))
        val winner=vm.service.testResults.value.entries.filter{it.value>0}.sortedWith(compareBy<Map.Entry<Long,Int>>{it.value}.thenBy{it.key}).first().key
        assertEquals(winner,vm.store.snapshot().merges.first{it.id==6105L}.selectedId)
        // The small delay action must not open the editor for its parent row.
        compose.onNodeWithText("编辑节点汇总组").assertDoesNotExist()
        compose.onNodeWithTag("merge_6105").performClick()
        compose.onNodeWithText("编辑节点汇总组").assertIsDisplayed()
    }

    @Test fun allSkinsAtLargeFontShowMeasuredUnknownAndFailedTargets() {
        val base=TargetLatencyFixture.data("proxy")
        compose.runOnIdle {vm.edit {base.copy(settings=base.settings+mapOf("fontScale" to "2.0","smart.youtube.target" to "node:6102"))}}
        saved();compose.onNodeWithTag("tab_1").performClick()
        for(skin in listOf("prism","linear","claude","supabase","miro")) {
            compose.runOnIdle{vm.setting("uiSkin",skin)}
            compose.waitUntil(15000){!vm.busy.value && vm.data.value.setting("uiSkin")==skin}
            compose.onNodeWithTag("smart_latency_youtube").performScrollTo().assertIsDisplayed().assertTextContains("5 ms",substring=true)
            compose.onNodeWithTag("smart_target_youtube").assertIsDisplayed()
        }
        compose.runOnIdle{vm.edit{it.copy(nodes=it.nodes.map{n->if(n.id==6102L)n.copy(ping=-1,status=0)else n})}}
        saved()
        compose.onNodeWithTag("smart_latency_youtube").performScrollTo().assertTextContains("未测试",substring=true)
        compose.runOnIdle{vm.edit{it.copy(nodes=it.nodes.map{n->if(n.id==6102L)n.copy(ping=-1,status=1)else n})}}
        saved()
        compose.onNodeWithTag("smart_latency_youtube").performScrollTo().assertTextContains("失败",substring=true)
    }
}
