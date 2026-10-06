package com.zane.zanebox

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.zane.zanebox.data.*
import com.zane.zanebox.ui.AppViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FloatingToolbarTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun listContinuesBehindThePillAndUnpaintedShouldersPassTapsAtBothFontSizes() {
        val vm=ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val nodes=(1L..40L).map {Node(it,1,"悬浮验收节点 $it","""{"type":"socks","server":"127.0.0.1","server_port":9}""")}
        compose.runOnIdle {vm.edit {AppData(nodes=nodes,groups=listOf(Group(1,"悬浮验收")),settings=mapOf("appLanguage" to "zh-CN","browseGroupId" to "1","selectedNodeId" to "1"))}}
        compose.waitUntil(10000){!vm.busy.value && vm.data.value.nodes.size==40}
        listOf("1.0","2.0").forEach {scale->
            compose.runOnIdle {vm.setting("fontScale",scale)}
            compose.waitUntil(10000){vm.data.value.setting("fontScale")==scale}
            val toolbar=compose.onNodeWithTag("home_toolbar").fetchSemanticsNode().boundsInRoot
            val viewport=compose.onNodeWithTag("page_list").fetchSemanticsNode().boundsInRoot
            assertTrue("列表必须延伸到导航栏后面，不能在导航栏上方截断：$viewport / $toolbar",viewport.bottom>=toolbar.bottom-1f)
            compose.onNodeWithTag("page_list").performScrollToNode(hasTestTag("node_20"))
            val row=compose.onNodeWithTag("node_20").fetchSemanticsNode().boundsInRoot
            val targetY=toolbar.top+22f*compose.activity.resources.displayMetrics.density
            compose.onNodeWithTag("page_list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy){it(0f,row.center.y-targetY)}
            val shoulder=compose.onNodeWithTag("node_20").fetchSemanticsNode().boundsInRoot
            assertEquals(targetY,shoulder.center.y,3f)
            assertTrue("测试点击必须在凸起两旁",shoulder.left+8f*compose.activity.resources.displayMetrics.density<toolbar.center.x-60f*compose.activity.resources.displayMetrics.density)
            compose.onNodeWithTag("node_20").performTouchInput{click(androidx.compose.ui.geometry.Offset(8f*compose.activity.resources.displayMetrics.density,center.y))}
            compose.waitUntil(10000){vm.data.value.selectedNodeId==20L}
            compose.runOnIdle {vm.setting("selectedNodeId","1")};compose.waitUntil(10000){vm.data.value.selectedNodeId==1L}
            val ins=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val bitmap=ins.uiAutomation.takeScreenshot()!!
            java.io.File(ins.targetContext.getExternalFilesDir(null),"floating-toolbar-$scale.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            compose.onNodeWithTag("page_list").performScrollToNode(hasTestTag("node_40"))
            repeat(2){compose.onNodeWithTag("page_list").performTouchInput{swipeUp()}}
            assertTrue("最后一个节点应能滚到胶囊上方",compose.onNodeWithTag("node_40").fetchSemanticsNode().boundsInRoot.bottom<=toolbar.top)
            compose.onNodeWithTag("tab_1").performTouchInput{click()}
            compose.onNodeWithTag("smart_speed").assertIsDisplayed();compose.onNodeWithTag("main_back").performClick()
        }
    }
}
