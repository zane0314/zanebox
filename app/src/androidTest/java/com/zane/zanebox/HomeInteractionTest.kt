package com.zane.zanebox

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.data.*
import com.zane.zanebox.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HomeInteractionTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val vm get()=ViewModelProvider(compose.activity)[AppViewModel::class.java]
    private fun seed(browse:Long=0) {
        compose.runOnIdle { vm.edit { AppData(
            nodes=(1L..9L).map { Node(it,(it-1)/3+1,"节点 $it","""{"type":"socks","server":"127.0.0.1","server_port":9}""",order=((it-1)%3).toInt()) },
            groups=(1L..3L).map { Group(it,"分组 $it",order=it.toInt(),enabled=it!=3L) },
            rules=listOf(RouteRule(1,"前置一",prioritize=true,order=0),RouteRule(2,"前置二",prioritize=true,order=1),RouteRule(3,"后置一",order=0)),
            settings=mapOf("appLanguage" to "zh-CN","selectedNodeId" to "1","selectedGroupId" to "1","browseGroupId" to browse.toString())) } }
        compose.waitUntil(15000){!vm.busy.value && vm.data.value.nodes.size==9}
        compose.waitUntil(7000){compose.onAllNodesWithText("已保存，请应用修改使配置生效").fetchSemanticsNodes().isEmpty()}
    }
    @Test fun swipeChangesBrowseGroupAndKeepsProxySelection() {
        seed()
        compose.onNodeWithTag("page_list").performTouchInput { swipeLeft() }
        compose.waitUntil(7000){vm.data.value.browseGroupId==1L}
        compose.onNodeWithTag("node_1").assertIsDisplayed()
        compose.onNodeWithTag("page_list").performTouchInput { swipeRight() }
        compose.waitUntil(7000){vm.data.value.browseGroupId==0L}
        compose.onNodeWithTag("group_2").performClick()
        compose.waitUntil(7000){vm.data.value.browseGroupId==2L}
        compose.onNodeWithTag("page_list").performTouchInput { swipeLeft() }
        compose.waitUntil(7000){vm.data.value.browseGroupId==3L}
        compose.onNodeWithTag("page_list").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(3L,vm.data.value.browseGroupId)
        assertEquals(1L,vm.data.value.selectedNodeId)
        assertEquals(1L,vm.store.snapshot().selectedNodeId)
    }
    private fun drag(from:String,to:String) {
        val source=compose.onNodeWithTag(from).fetchSemanticsNode().layoutInfo.coordinates
        val target=compose.onNodeWithTag(to).fetchSemanticsNode().layoutInfo.coordinates
        val a=source.localToScreen(Offset(source.size.width/2f,source.size.height/2f))
        val b=target.localToScreen(Offset(target.size.width/2f,target.size.height/2f))
        println("drag $from=$a -> $to=$b")
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val down=SystemClock.uptimeMillis()
        fun send(action:Int,x:Float,y:Float) {
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
            event.source=InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event,true)) } finally {event.recycle()}
        }
        send(MotionEvent.ACTION_DOWN,a.x,a.y);compose.mainClock.advanceTimeBy(800);SystemClock.sleep(100)
        for(i in 1..20) {send(MotionEvent.ACTION_MOVE,a.x,a.y+(b.y-a.y)*i/20);compose.mainClock.advanceTimeByFrame();SystemClock.sleep(20)}
        send(MotionEvent.ACTION_UP,a.x,b.y)
    }
    @Test fun longPressDragsNodesWithoutSelectingOrChangingProxy() {
        seed(1)
        drag("node_1","node_3")
        compose.waitUntil(10000){vm.data.value.nodes.filter{it.groupId==1L}.sortedBy{it.order}.map{it.id}==listOf(2L,3L,1L)}
        compose.onNodeWithTag("home_selection_cancel").assertDoesNotExist()
        assertEquals(1L,vm.data.value.selectedNodeId)
        assertEquals(listOf(4L,5L,6L),vm.data.value.nodes.filter{it.groupId==2L}.sortedBy{it.order}.map{it.id})
        compose.runOnUiThread{compose.activity.recreate()}
        compose.waitUntil(10000){vm.store.snapshot().nodes.filter{it.groupId==1L}.sortedBy{it.order}.map{it.id}==listOf(2L,3L,1L)}
    }
    @Test fun longPressDragsGroupsRulesAndExpandedNodesInTheirOwnScope() {
        seed()
        compose.onNodeWithTag("tab_2").performClick();compose.onNodeWithTag("settings_groups").performClick()
        drag("manage_group_1","manage_group_2")
        compose.waitUntil(10000){vm.data.value.groups.sortedBy{it.order}.map{it.id}==listOf(2L,1L,3L)}
        compose.onNodeWithTag("manage_group_nodes_2").performClick()
        compose.onNodeWithTag("subpage_list").performScrollToNode(hasTestTag("manage_node_6"))
        drag("manage_node_4","manage_node_6")
        compose.waitUntil(10000){vm.data.value.nodes.filter{it.groupId==2L}.sortedBy{it.order}.map{it.id}==listOf(5L,6L,4L)}
        compose.onNodeWithTag("page_back").performClick();compose.onNodeWithTag("main_back").performClick()
        compose.onNodeWithTag("tab_1").performClick();compose.onNodeWithTag("subpage_list").assertDoesNotExist()
        compose.onNodeWithTag("smart_rules").performClick()
        drag("rule_1","rule_2")
        compose.waitUntil(10000){vm.data.value.rules.filter{it.prioritize}.sortedBy{it.order}.map{it.id}==listOf(2L,1L)}
        assertEquals(0,vm.data.value.rules.single{it.id==3L}.order)
        assertEquals(1L,vm.data.value.selectedNodeId)
    }

    @Test fun longPressAtViewportEdgeAutoScrollsWithoutChangingSelectionOrOtherGroups() {
        val homeNodes=(1L..24L).map { id->Node(id,1,"边缘拖动节点 $id","""{"type":"socks","server":"127.0.0.1","server_port":9}""",order=(id-1).toInt()) }
        val otherNodes=(101L..103L).map { id->Node(id,2,"其它组节点 $id","""{"type":"socks","server":"127.0.0.1","server_port":9}""",order=(id-101).toInt()) }
        val otherOrder=otherNodes.map{it.id}
        compose.runOnIdle { vm.edit { AppData(
            nodes=homeNodes+otherNodes,
            groups=listOf(Group(1,"边缘拖动组",order=1),Group(2,"其它组",order=2)),
            settings=mapOf("appLanguage" to "zh-CN","selectedNodeId" to "1","selectedGroupId" to "1","browseGroupId" to "1","showBottomBar" to "true")
        ) } }
        compose.waitUntil(15000){!vm.busy.value && vm.data.value.nodes.size==27 && vm.data.value.browseGroupId==1L}
        compose.onNodeWithTag("node_1").assertIsDisplayed()
        val initialOrder=homeNodes.associate{it.id to it.order}
        val initialVisibleIds=homeNodes.map{it.id}.filter{compose.onAllNodesWithTag("node_$it").fetchSemanticsNodes().isNotEmpty()}
        val initialMaxVisibleOrder=initialVisibleIds.maxOf{initialOrder.getValue(it)}
        val list=compose.onNodeWithTag("page_list").fetchSemanticsNode().layoutInfo.coordinates
        val source=compose.onNodeWithTag("node_1").fetchSemanticsNode().layoutInfo.coordinates
        val a=source.localToScreen(Offset(source.size.width/2f,source.size.height/2f))
        val density=compose.activity.resources.displayMetrics.density
        val b=list.localToScreen(Offset(20f*density,list.size.height-10f))
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val down=SystemClock.uptimeMillis()
        fun send(action:Int,x:Float,y:Float) {
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
            event.source=InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event,true)) } finally {event.recycle()}
        }
        send(MotionEvent.ACTION_DOWN,a.x,a.y);compose.mainClock.advanceTimeBy(800);SystemClock.sleep(100)
        for(i in 1..24) {
            val fraction=i/24f
            send(MotionEvent.ACTION_MOVE,a.x+(b.x-a.x)*fraction,a.y+(b.y-a.y)*fraction)
            compose.mainClock.advanceTimeByFrame();SystemClock.sleep(20)
        }
        repeat(75) {
            send(MotionEvent.ACTION_MOVE,b.x,b.y)
            compose.mainClock.advanceTimeByFrame();SystemClock.sleep(16)
        }
        send(MotionEvent.ACTION_UP,b.x,b.y);compose.mainClock.advanceTimeByFrame()
        compose.waitUntil(15000) {
            val ordered=vm.data.value.nodes.filter{it.groupId==1L}.sortedBy{it.order}.map{it.id}
            val visiblePastInitial=homeNodes.any { node->
                initialOrder.getValue(node.id)>initialMaxVisibleOrder && compose.onAllNodesWithTag("node_${node.id}").fetchSemanticsNodes().isNotEmpty()
            }
            ordered.indexOf(1L)>initialMaxVisibleOrder && visiblePastInitial
        }
        val ordered=vm.data.value.nodes.filter{it.groupId==1L}.sortedBy{it.order}.map{it.id}
        assertTrue("首节点应移动到初始可见节点之后：$ordered / max=$initialMaxVisibleOrder",ordered.indexOf(1L)>initialMaxVisibleOrder)
        assertTrue("当前视口末尾应出现初始视口外节点",homeNodes.any { node->
            initialOrder.getValue(node.id)>initialMaxVisibleOrder && compose.onAllNodesWithTag("node_${node.id}").fetchSemanticsNodes().isNotEmpty()
        })
        assertEquals(1L,vm.data.value.selectedNodeId)
        assertEquals(1L,vm.store.snapshot().selectedNodeId)
        assertEquals(otherOrder,vm.data.value.nodes.filter{it.groupId==2L}.sortedBy{it.order}.map{it.id})
    }
}
