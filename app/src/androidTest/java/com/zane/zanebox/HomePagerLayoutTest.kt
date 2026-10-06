package com.zane.zanebox

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.data.*
import com.zane.zanebox.ui.AppViewModel
import com.zane.zanebox.ui.browseGroupId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomePagerLayoutTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val vm get()=ViewModelProvider(compose.activity)[AppViewModel::class.java]

    private fun seed() {
        val nodes=(1L..72L).map { id->
            val group=(id-1)/24+1
            val local=(id-1)%24+1
            Node(id,group,"分组 $group · 节点 $local","""{"type":"socks","server":"127.0.0.1","server_port":9}""",order=(local-1).toInt())
        }
        compose.runOnIdle { vm.edit { AppData(
            nodes=nodes,
            groups=(1L..3L).map { Group(it,"分组 $it",order=it.toInt()) },
            settings=mapOf("appLanguage" to "zh-CN","serviceMode" to "proxy","selectedNodeId" to "1","selectedGroupId" to "1","browseGroupId" to "1","statsEnabled" to "false")
        ) } }
        compose.waitUntil(15000){!vm.busy.value && vm.data.value.nodes.size==72 && vm.data.value.browseGroupId==1L}
    }

    private fun headerBounds():Pair<Rect,Rect> = compose.onNodeWithTag("home_header").fetchSemanticsNode().boundsInRoot to
        compose.onNodeWithTag("home_groups").fetchSemanticsNode().boundsInRoot

    private fun assertSame(before:Rect,after:Rect,label:String) {
        assertEquals("$label left",before.left,after.left,1f);assertEquals("$label top",before.top,after.top,1f)
        assertEquals("$label right",before.right,after.right,1f);assertEquals("$label bottom",before.bottom,after.bottom,1f)
    }

    private fun assertHeader() {
        compose.onNodeWithTag("home_header").assertIsDisplayed()
        compose.onNodeWithTag("home_groups").assertIsDisplayed()
        compose.onNodeWithTag("home_selected_node").assertIsDisplayed().assertTextContains("节点 1",substring=true)
        compose.onNodeWithTag("connection_state").assertDoesNotExist()
    }

    private fun horizontalDragInList(before:Pair<Rect,Rect>) {
        val page=compose.onNodeWithTag("page_list").fetchSemanticsNode().layoutInfo.coordinates
        val source=compose.onNodeWithTag("node_1").fetchSemanticsNode().layoutInfo.coordinates
        val a=Offset(page.localToScreen(Offset(page.size.width*.85f,0f)).x,source.localToScreen(Offset(0f,source.size.height/2f)).y)
        val b=Offset(a.x-page.size.width*.60f,a.y)
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val down=SystemClock.uptimeMillis()
        fun send(action:Int,x:Float,y:Float) {
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
            event.source=InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event,true)) } finally {event.recycle()}
        }
        send(MotionEvent.ACTION_DOWN,a.x,a.y);compose.mainClock.advanceTimeBy(16);SystemClock.sleep(16)
        for(i in 1..20) {
            val f=i/20f
            send(MotionEvent.ACTION_MOVE,a.x+(b.x-a.x)*f,a.y)
            compose.mainClock.advanceTimeByFrame();SystemClock.sleep(20)
            if(i==10) {
                val (header,groups)=headerBounds()
                assertSame(before.first,header,"header during drag")
                assertSame(before.second,groups,"groups during drag")
            }
        }
        send(MotionEvent.ACTION_UP,b.x,b.y);compose.mainClock.advanceTimeByFrame()
    }

    private fun assertGroupTypographyAndSpacing() {
        val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("home_selected_node",useUnmergedTree=true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){it(layouts)}
        assertEquals(1,layouts.size)
        assertEquals(13f,layouts.single().layoutInput.style.fontSize.value,.1f)
        val density=compose.activity.resources.displayMetrics.density
        val gap=(compose.onNodeWithTag("group_1").fetchSemanticsNode().boundsInRoot.top-compose.onNodeWithTag("add_nodes").fetchSemanticsNode().boundsInRoot.bottom)/density
        assertTrue("分组栏应位于右侧按钮下方 8–16dp：${gap}dp",gap in 8f..16f)
    }

    private fun visibleNodeIds():Set<Long> {
        val viewport=compose.onNodeWithTag("page_list").fetchSemanticsNode().boundsInRoot
        return (1L..24L).filter { id->compose.onAllNodesWithTag("node_$id").fetchSemanticsNodes().any {
            val b=it.boundsInRoot;b.bottom>viewport.top && b.top<viewport.bottom
        } }.toSet()
    }

    @Test fun horizontalNodeDragKeepsHeaderAndGroupsFixedUntilGroupChanges() {
        seed();compose.onNodeWithTag("node_1").assertIsDisplayed()
        val (headerBefore,groupsBefore)=headerBounds()
        horizontalDragInList(headerBefore to groupsBefore)
        compose.waitUntil(10000){vm.data.value.browseGroupId==2L}
        assertEquals(1L,vm.data.value.selectedNodeId)
        assertEquals(1L,vm.store.snapshot().selectedNodeId)
        compose.onNodeWithTag("node_25").assertIsDisplayed()
        assertHeader()
        assertGroupTypographyAndSpacing()
        assertSame(headerBefore,headerBounds().first,"header after group switch")
        assertSame(groupsBefore,headerBounds().second,"groups after group switch")
    }

    @Test fun verticalNodeScrollKeepsHeaderAndRestoresEachGroupScrollPosition() {
        seed();assertHeader()
        val (headerBefore,groupsBefore)=headerBounds()
        repeat(4){compose.onNodeWithTag("page_list").performTouchInput{swipeUp()}}
        compose.waitForIdle()
        assertHeader();assertSame(headerBefore,headerBounds().first,"header after vertical scroll");assertSame(groupsBefore,headerBounds().second,"groups after vertical scroll")
        val scrolled=visibleNodeIds();assertTrue("节点列表应发生纵向滚动：$scrolled",scrolled.any{it>=10})
        val anchor=scrolled.maxOrNull() ?: error("滚动后没有可见节点")
        compose.onNodeWithTag("group_2").performClick()
        compose.waitUntil(10000){vm.data.value.browseGroupId==2L};compose.onNodeWithTag("node_25").assertIsDisplayed()
        compose.onNodeWithTag("group_1").performClick()
        compose.waitUntil(10000){vm.data.value.browseGroupId==1L}
        assertHeader();compose.onNodeWithTag("node_$anchor").assertIsDisplayed()
        assertEquals(1L,vm.data.value.selectedNodeId);assertEquals(1L,vm.store.snapshot().selectedNodeId)
    }
}
