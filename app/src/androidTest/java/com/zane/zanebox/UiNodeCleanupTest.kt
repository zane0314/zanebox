package com.zane.zanebox

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.zane.zanebox.data.*
import com.zane.zanebox.ui.AppViewModel
import com.zane.zanebox.ui.browseGroupId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UiNodeCleanupTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Test fun everyUiDeletionEntryCleansRanksAndLostSelectionsAndKeepsLiveSettings() {
        val vm=ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val actions=listOf<(AppViewModel)->Unit>(
            {it.deleteNode(1)}, {it.deleteNodes(setOf(1,2))}, {it.clearGroup(1)}, {it.deleteGroup(1)})
        actions.forEachIndexed { index,remove ->
            compose.runOnIdle {vm.edit(autoApply=false) {AppData(
                nodes=(1L..3L).map {Node(it,if(it==3L)2 else 1,"node $it","""{"type":"socks","server":"127.0.0.1","server_port":9}""")},
                groups=listOf(Group(1,"delete"),Group(2,"keep")),
                merges=listOf(MergeGroup(7,"members",nodeIds=listOf(1,2,3),selectedId=1)),
                settings=mapOf("appLanguage" to "zh-CN","selectedNodeId" to "1","selectedGroupId" to "1",
                    "browseGroupId" to "1","sort_ping_1" to "10","sort_ping_2" to "20","sort_ping_3" to "30",
                    "sort_ping_999" to "old","groupSelection.merge-7" to "node-1","groupSelectionDelay.merge-7" to "10",
                    "groupSelection.group-2" to "node-3","groupSelectionDelay.group-2" to "30","custom.keep" to "retained"))}}
            compose.waitUntil(10000) {!vm.busy.value && vm.data.value.nodes.size==3}
            compose.runOnIdle {remove(vm)}
            compose.waitUntil(10000) {!vm.busy.value && vm.data.value.nodes.none {it.id==1L}}
            val data=vm.store.snapshot()
            assertFalse("entry $index",data.settings.containsKey("sort_ping_1"))
            assertFalse(data.settings.containsKey("sort_ping_999"))
            assertFalse(data.settings.containsKey("groupSelection.merge-7"))
            assertFalse(data.settings.containsKey("groupSelectionDelay.merge-7"))
            assertEquals("node-3",data.setting("groupSelection.group-2"))
            assertEquals("30",data.setting("sort_ping_3"))
            assertEquals("retained",data.setting("custom.keep"))
            assertTrue(data.nodes.any {it.id==data.selectedNodeId})
            if(index>0)assertFalse(data.settings.containsKey("sort_ping_2"))
            if(index==3)assertEquals(0L,data.browseGroupId)
        }
    }
}
