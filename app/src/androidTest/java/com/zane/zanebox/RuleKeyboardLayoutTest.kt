package com.zane.zanebox

import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.zane.zanebox.data.*
import com.zane.zanebox.ui.AppViewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/** Measures the real Dialog window; screenshot evidence is captured by the adb runner. */
class RuleKeyboardLayoutTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val vm get()=ViewModelProvider(compose.activity)[AppViewModel::class.java]

    private fun seed() {
        val rules=(1..80).joinToString("\n") { "DOMAIN-SUFFIX,keyboard-$it.example.test" }
        compose.runOnIdle { vm.edit { AppData(
            nodes=listOf(Node(801,801,"键盘布局节点","""{"type":"socks","server":"127.0.0.1","server_port":9}""")),
            groups=listOf(Group(801,"键盘布局组")),
            settings=mapOf("appLanguage" to "zh-CN","serviceMode" to "proxy","selectedNodeId" to "801","browseGroupId" to "801","smartRules.speed" to rules,"smart.speed.target" to "direct","statsEnabled" to "false")
        ) } }
        compose.waitUntil(15000){!vm.busy.value && vm.data.value.setting("smartRules.speed").lines().size==80}
        compose.runOnIdle{vm.message.value=""}
        compose.mainClock.advanceTimeBy(10000)
        compose.waitForIdle()
    }

    private fun dialogRootView():View {
        val layoutInfo=compose.onNodeWithTag("subpage_list").fetchSemanticsNode().layoutInfo
        val owner=layoutInfo.javaClass.methods.firstOrNull { it.name=="getOwner\$ui_release" }?.invoke(layoutInfo)
        return owner as? View ?: error("subpage_list 未找到实际 Dialog ComposeView owner")
    }

    private data class KeyboardMeasure(val imeTop:Float,val listBottom:Float,val imeVisible:Boolean,val imeBottom:Int,val details:String)

    private fun imeTopAndListBottom():KeyboardMeasure {
        val root=dialogRootView()
        val insets=root.rootWindowInsets ?: error("Dialog rootWindowInsets 不可用")
        val ime=insets.getInsets(WindowInsets.Type.ime())
        val decor=root.rootView
        val location=IntArray(2).also(decor::getLocationOnScreen)
        val imeTop=compose.activity.windowManager.maximumWindowMetrics.bounds.bottom-ime.bottom
        val list=compose.onNodeWithTag("subpage_list").fetchSemanticsNode()
        val ownerScreen=IntArray(2).also(root::getLocationOnScreen)
        val ownerWindow=IntArray(2).also(root::getLocationInWindow)
        val bottom=list.boundsInWindow.bottom+ownerScreen[1]-ownerWindow[1]
        val details="decorHeight=${decor.height} decorY=${location[1]} ownerHeight=${root.height} ownerScreenY=${ownerScreen[1]} ownerWindowY=${ownerWindow[1]} nav=${insets.getInsets(WindowInsets.Type.navigationBars()).bottom} boundsWindow=${list.boundsInWindow} boundsRoot=${list.boundsInRoot}"
        return KeyboardMeasure(imeTop.toFloat(),bottom,insets.isVisible(WindowInsets.Type.ime()),ime.bottom,details)
    }

    @Test fun ruleEditorListReachesTheKeyboardTopWithoutLargeBottomGap() {
        seed()
        compose.onNodeWithTag("tab_1").performClick()
        compose.onNodeWithTag("smart_speed").performClick()
        compose.onNodeWithText("规则来源").performClick()
        if(compose.onAllNodesWithTag("rule_source_raw").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithTag("rule_source_raw").performClick()
        compose.onNodeWithTag("rule_source_rules").performScrollTo().performClick()
        compose.waitUntil(10000) {
            runCatching { imeTopAndListBottom() }.getOrNull()?.let { it.imeVisible && it.imeBottom>0 }==true
        }
        SystemClock.sleep(500)
        val measure=imeTopAndListBottom()
        assertTrue("Dialog rootWindowInsets 未报告可见 IME：$measure",measure.imeVisible && measure.imeBottom>0)
        val imeTop=measure.imeTop;val listBottom=measure.listBottom
        val tolerance=32f*compose.activity.resources.displayMetrics.density
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(2,android.os.Bundle().apply{putString("stream","KEYBOARD_SCREENSHOT_READY $measure\n")})
        SystemClock.sleep(1500)
        assertTrue("Dialog 列表底部与 IME 顶部间距过大：imeTop=$imeTop listBottom=$listBottom gap=${imeTop-listBottom}px",abs(imeTop-listBottom)<=tolerance)
    }
}
