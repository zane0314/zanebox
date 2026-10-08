package com.zane.zanebox

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.config.smartPolicyKeys
import com.zane.zanebox.data.*
import com.zane.zanebox.ui.AppViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SmartDragTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun seed(extra:Int=0):AppViewModel {
        val vm=ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val settings=mapOf("appLanguage" to "zh-CN","selectedNodeId" to "901","serviceMode" to "vpn","smart.speed.target" to "direct","smart.youtube.target" to "proxy","smartCustom.ai.packages" to "com.zane.probe","perAppEnabled" to "true","perAppMode" to "include","perAppPackages" to "com.zane.probe")+(0 until extra).associate{"smartCustom.custom_$it.name" to "自定义组 $it"}
        compose.runOnIdle{vm.edit(autoApply=false){AppData(nodes=listOf(Node(901,901,"验收出口","""{"type":"socks","server":"10.0.2.2","server_port":19081}""")),groups=listOf(Group(901,"验收分组")),settings=settings)}}
        compose.waitUntil(15000){!vm.busy.value && vm.data.value.selectedNodeId==901L}
        compose.onNodeWithTag("tab_1").performClick()
        return vm
    }
    private fun scroll(tag:String)=compose.onNodeWithTag("page_list").performScrollToNode(hasTestTag(tag))
    private fun drag(from:String,to:String,cancel:Boolean=false) {
        scroll(to)
        val source=compose.onNodeWithTag(from).fetchSemanticsNode().layoutInfo.coordinates
        val target=compose.onNodeWithTag(to).fetchSemanticsNode().layoutInfo.coordinates
        val a=source.localToScreen(Offset(60f,source.size.height/2f))
        val b=target.localToScreen(Offset(60f,target.size.height/2f))
        inject(a,b,cancel)
    }
    private var beforeRelease:(()->Unit)?=null
    private fun inject(a:Offset,b:Offset,cancel:Boolean=false,edgeHold:Int=0) {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val down=SystemClock.uptimeMillis()
        fun send(action:Int,x:Float,y:Float) {
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.source=InputDevice.SOURCE_TOUCHSCREEN
            try {assertTrue(automation.injectInputEvent(event,true))}finally{event.recycle()}
        }
        send(MotionEvent.ACTION_DOWN,a.x,a.y);compose.mainClock.advanceTimeBy(800);SystemClock.sleep(120)
        for(i in 1..24){send(MotionEvent.ACTION_MOVE,a.x,a.y+(b.y-a.y)*i/24);compose.mainClock.advanceTimeByFrame();SystemClock.sleep(20)}
        repeat(edgeHold){send(MotionEvent.ACTION_MOVE,b.x,b.y);compose.mainClock.advanceTimeBy(32);SystemClock.sleep(20)}
        beforeRelease?.invoke();beforeRelease=null
        send(if(cancel)MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP,b.x,b.y)
    }
    @Test fun dragMovesBothDirectionsCancelRestoresAndRecreationKeepsOrder() {
        val vm=seed();val original=vm.store.snapshot()
        beforeRelease={assertEquals("拖动中不得提前保存",original.setting("smartPolicyOrder"),vm.store.snapshot().setting("smartPolicyOrder"))}
        drag("smart_speed","smart_telegram")
        compose.waitUntil(15000){smartPolicyKeys(vm.data.value).take(3)==listOf("youtube","telegram","speed")}
        compose.onNodeWithText("移动策略").assertDoesNotExist()
        val saved=vm.store.snapshot();assertEquals(original.nodes,saved.nodes);assertEquals(original.groups,saved.groups)
        assertEquals(original.settings,saved.settings-"smartPolicyOrder")
        drag("smart_speed","smart_youtube",cancel=true);compose.waitForIdle();assertEquals(smartPolicyKeys(saved),smartPolicyKeys(vm.store.snapshot()))
        drag("smart_speed","smart_youtube")
        compose.waitUntil(15000){smartPolicyKeys(vm.data.value).first()=="speed"}
        val expected=smartPolicyKeys(vm.store.snapshot())
        compose.runOnUiThread{compose.activity.recreate()}
        compose.waitUntil(15000){smartPolicyKeys(vm.store.snapshot())==expected && !vm.busy.value}
        if(compose.onAllNodesWithTag("tab_1").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithTag("tab_1").performClick()
        scroll("smart_speed")
        compose.onNodeWithTag("smart_speed").performClick()
        scroll("smart_apps_speed");compose.onNodeWithTag("smart_apps_speed").performClick()
        compose.onNodeWithTag("apps_save").assertIsDisplayed();compose.onNodeWithTag("page_back").performClick()
        scroll("smart_target_speed");compose.onNodeWithTag("smart_target_speed").performClick();compose.onNodeWithText("取消").performClick()
    }
    @Test fun customGroupCanDragAcrossScreensUsingEdgeScroll() {
        val vm=seed(18);scroll("smart_custom_0")
        val from=compose.onNodeWithTag("smart_custom_0").fetchSemanticsNode().layoutInfo.coordinates
        val list=compose.onNodeWithTag("page_list").fetchSemanticsNode().layoutInfo.coordinates
        val a=from.localToScreen(Offset(60f,from.size.height/2f))
        val b=list.localToScreen(Offset(100f,list.size.height-20f))
        inject(a,b,edgeHold=220)
        compose.waitUntil(15000){smartPolicyKeys(vm.data.value).indexOf("custom_0")>smartPolicyKeys(vm.data.value).indexOf("custom_5")}
        assertEquals("自定义组 0",vm.store.snapshot().setting("smartCustom.custom_0.name"))
        assertEquals("com.zane.probe",vm.store.snapshot().setting("smartCustom.ai.packages"))
    }
}
