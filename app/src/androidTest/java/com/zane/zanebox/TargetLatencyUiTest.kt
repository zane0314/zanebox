package com.zane.zanebox

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.zane.zanebox.ui.AppViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TargetLatencyUiTest {
    private var largeSystemFont=false
    @get:Rule(order=0) val systemFont=org.junit.rules.TestRule { base,description ->
        object:org.junit.runners.model.Statement() {
            override fun evaluate() {
                largeSystemFont=description.methodName.contains("LargeFont")
                if(!largeSystemFont) {base.evaluate();return}
                val ins=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                fun shell(command:String)=ins.uiAutomation.executeShellCommand(command).use {
                    java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText().trim()
                }
                fun awaitSystemScale(expected:Float) {
                    fun readScale():Float {
                        val config=shell("dumpsys activity processes")
                        return Regex("""mGlobalConfiguration:\s*\{([0-9.]+)\s""").find(config)?.groupValues?.get(1)?.toFloatOrNull()
                            ?: error("ActivityManager 未返回全局字体配置")
                    }
                    val until=android.os.SystemClock.uptimeMillis()+5000
                    var actual=readScale()
                    while(kotlin.math.abs(actual-expected)>=0.01f && android.os.SystemClock.uptimeMillis()<until) {
                        Thread.sleep(50);actual=readScale()
                    }
                    assertEquals("ActivityManager global font scale",expected,actual,0.01f)
                }
                val original=shell("settings get system font_scale")
                val originalScale=original.toFloatOrNull() ?: 1f
                // Drain the previous restore before writing 2.0; Settings writes precede the asynchronous system configuration.
                awaitSystemScale(originalScale)
                try {
                    shell("settings put system font_scale 2.0")
                    awaitSystemScale(2f)
                    base.evaluate()
                } finally {
                    shell(if(original=="null")"settings delete system font_scale" else "settings put system font_scale $original")
                    awaitSystemScale(originalScale)
                }
            }
        }
    }
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    @org.junit.Before fun requireAppliedSystemFont() {
        if(!largeSystemFont)return
        // The outer rule sets the OS value; the inner rule launches Activity before this checks its applied configuration.
        compose.waitUntil(5000) {kotlin.math.abs(compose.activity.resources.configuration.fontScale-2f)<0.01f}
        assertEquals("Activity applied system font scale",2f,compose.activity.resources.configuration.fontScale,0.01f)
        val text=compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),useUnmergedTree=true).onFirst()
        assertEquals("Compose applied system font scale",2f,layout(text).layoutInput.density.fontScale,0.01f)
    }
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
        scrollTo("smart_latency_youtube").assertTextContains("5 ms",substring=true)
        val first=vm.service.testProgress.value.batchId
        compose.onNodeWithTag("smart_latency_youtube").performClick()
        measured(first,setOf(6101L,6102L))
        assertEquals("group:6101",vm.store.snapshot().setting("smart.youtube.target"))
        assertEquals(6102L,vm.store.snapshot().selectedNodeId)
        compose.onNodeWithTag("smart_target_youtube").performClick()
        compose.onNodeWithText("分流目标").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        scrollTo("smart_merges").performClick()
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
        compose.runOnIdle {vm.edit {base.copy(settings=base.settings+mapOf("smart.youtube.target" to "node:6102"))}}
        saved();compose.onNodeWithTag("tab_1").performClick()
        for(skin in listOf("prism","linear","claude","supabase","miro")) {
            compose.runOnIdle{vm.setting("uiSkin",skin)}
            compose.waitUntil(15000){!vm.busy.value && vm.data.value.setting("uiSkin")==skin}
            scrollTo("smart_latency_youtube").assertIsDisplayed().assertTextContains("5 ms",substring=true)
            compose.onNodeWithTag("smart_target_youtube").assertIsDisplayed()
        }
        compose.runOnIdle{vm.edit{it.copy(nodes=it.nodes.map{n->if(n.id==6102L)n.copy(ping=-1,status=0)else n})}}
        saved()
        scrollTo("smart_latency_youtube").assertTextContains("未测试",substring=true)
        compose.runOnIdle{vm.edit{it.copy(nodes=it.nodes.map{n->if(n.id==6102L)n.copy(ping=-1,status=1)else n})}}
        saved()
        scrollTo("smart_latency_youtube").assertTextContains("失败",substring=true)
    }

    // The node line sits inside the clickable row, whose descendants merge into it; address it in the unmerged tree.
    private fun bounds(tag:String)=compose.onNodeWithTag(tag,useUnmergedTree=tag.startsWith("smart_node_")).fetchSemanticsNode().boundsInRoot
    private fun layout(node:SemanticsNodeInteraction):TextLayoutResult {
        val results=mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }
    private fun scrollTo(tag:String):SemanticsNodeInteraction {compose.onNodeWithTag("page_list").performScrollToNode(hasTestTag(tag));return compose.onNodeWithTag(tag)}
    private fun detail(label:String,l:TextLayoutResult)="$label size=${l.size} constraints=${l.layoutInput.constraints} fontSize=${l.layoutInput.style.fontSize} " +
        "density=${l.layoutInput.density.density} fontScale=${l.layoutInput.density.fontScale} lines=${l.lineCount} overflowWidth=${l.didOverflowWidth} " +
        "overflowHeight=${l.didOverflowHeight} visualOverflow=${l.hasVisualOverflow} paragraphWidth=${l.multiParagraph.width} " +
        "lineRight=${l.getLineRight(0)} intrinsicWidth=${l.multiParagraph.intrinsics.maxIntrinsicWidth} text='${l.layoutInput.text}'"
    private fun policyData(extra:Map<String,String> = emptyMap(),nodes:(List<com.zane.zanebox.data.Node>)->List<com.zane.zanebox.data.Node> = {it}):com.zane.zanebox.data.AppData {
        val base=TargetLatencyFixture.data("proxy")
        // The stored group choice is the SLOWER member A (900ms) and the user-selected node is B; the line must still name the group's lowest member, which is B only because it is fastest.
        return base.copy(nodes=nodes(base.nodes),settings=base.settings+("groupSelection.group-6101" to "node-6101")+("selectedNodeId" to "${TargetLatencyFixture.A}")+extra)
    }

    @Test fun twoLineRowShowsFourBareValuesWithoutAnyPrefix() {
        compose.runOnIdle{vm.edit{policyData(mapOf("smart.x.target" to "node:6102","smart.speed.target" to "direct"))}}
        saved();compose.onNodeWithTag("tab_1").performClick()
        scrollTo("smart_card_youtube")
        // Four bare values: app name / 5 ms on the upper line, lowest node / group name on the lower line.
        compose.onNodeWithTag("smart_latency_youtube").assertTextEquals("5 ms")
        // Same node as the 5 ms above (B), shown bare: neither the stored choice A nor the selected node A, and no prefix.
        compose.onNodeWithTag("smart_node_youtube",useUnmergedTree=true).assertTextEquals("出口 B")
        compose.onNodeWithTag("smart_target_youtube").assertTextEquals("延迟验收订阅")
        val card=compose.onNodeWithTag("smart_card_youtube")
        for(prefix in listOf("组内最低","目标：","上次","已选","当前"))
            assertEquals("card must not contain the prefix '$prefix'",0,compose.onAllNodes(hasText(prefix,substring=true) and hasAnyAncestor(hasTestTag("smart_card_youtube")),useUnmergedTree=true).fetchSemanticsNodes().size)
        assertEquals("group name shown once",1,compose.onAllNodes(hasText("延迟验收订阅",substring=true) and hasAnyAncestor(hasTestTag("smart_card_youtube"))).fetchSemanticsNodes().size)
        val latency=bounds("smart_latency_youtube");val node=bounds("smart_node_youtube");val target=bounds("smart_target_youtube");val outer=card.fetchSemanticsNode().boundsInRoot
        assertTrue("latency on the upper line, right side",latency.bottom<=target.top+1 && latency.left>outer.left+outer.width/3)
        assertTrue("node left, target right on the lower line",node.right<=target.left+1 && node.left<target.left)
        assertTrue("target right-aligned with latency",kotlin.math.abs(latency.right-target.right)<=24)

        // Single node, direct and off targets never repeat a node or invent a group minimum.
        scrollTo("smart_latency_x")
        compose.onNodeWithTag("smart_node_x",useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithTag("smart_latency_x").assertTextEquals("5 ms")
        compose.onAllNodes(hasText("组内最低",substring=true) and hasAnyAncestor(hasTestTag("smart_card_x")),useUnmergedTree=true).assertCountEquals(0)
        compose.onNodeWithTag("smart_target_x").assertTextEquals("出口 B")
        scrollTo("smart_target_speed")
        compose.onNodeWithTag("smart_node_speed",useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithTag("smart_latency_speed").assertDoesNotExist()
        compose.onNodeWithTag("smart_target_speed").assertTextEquals("直连")
    }

    @Test fun targetAndLatencyClicksDoNotExpandButTheBlankAreaDoes() {
        compose.runOnIdle{vm.edit{policyData()}}
        saved();compose.onNodeWithTag("tab_1").performClick()
        scrollTo("smart_target_youtube")
        compose.onNodeWithTag("smart_target_youtube").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("smart_apps_youtube").assertDoesNotExist()
        val batch=vm.service.testProgress.value.batchId
        compose.onNodeWithTag("smart_latency_youtube").performClick()
        compose.waitUntil(20000){vm.service.testProgress.value.batchId>batch}
        compose.onNodeWithTag("smart_apps_youtube").assertDoesNotExist()
        measured(batch,setOf(6101L,6102L))
        compose.onNodeWithTag("smart_youtube").performTouchInput { click(androidx.compose.ui.geometry.Offset(8f,center.y)) }
        scrollTo("smart_apps_youtube")
        compose.onNodeWithTag("smart_apps_youtube").assertIsDisplayed()
        scrollTo("smart_current_youtube").assertIsDisplayed()
    }

    @Test fun allSkinsAtLargeFontKeepLongNamesEllipsizedAndMaxLatencyComplete() {
        val longNode="香港专线超长节点名称用于验证省略显示不会遮挡数值或按钮 0123456789 0123456789 0123456789"
        val longGroup="亚洲优选超长分组名称用于验证目标按钮省略 0123456789 0123456789"
        compose.runOnIdle {vm.edit {policyData(mapOf("groupSelection.group-6101" to "node-6101"),nodes={ list->list.map{n->if(n.id==6101L)n.copy(name=longNode,ping=65535,status=3) else if(n.id==6102L)n.copy(ping=65535,status=3) else n}}).let{d->d.copy(groups=d.groups.map{g->if(g.id==6101L)g.copy(name=longGroup) else g})}}}
        saved();compose.onNodeWithTag("tab_1").performClick()
        for(skin in listOf("prism","linear","claude","supabase","miro")) {
            compose.runOnIdle{vm.setting("uiSkin",skin)}
            compose.waitUntil(15000){!vm.busy.value && vm.data.value.setting("uiSkin")==skin}
            scrollTo("smart_card_youtube")
            compose.onNodeWithTag("smart_latency_youtube").assertIsDisplayed()
            val value=compose.onNode(hasText("65535 ms") and hasAnyAncestor(hasTestTag("smart_latency_youtube")),useUnmergedTree=true)
            value.assertIsDisplayed()
            val valueLayout=layout(value)
            println(detail("LATENCY_LAYOUT_$skin",valueLayout))
            assertTrue("requires actual system font scale 2.0: "+detail(skin,valueLayout),valueLayout.layoutInput.density.fontScale>=1.9f)
            val latency=bounds("smart_latency_youtube");val node=bounds("smart_node_youtube");val target=bounds("smart_target_youtube");val outer=bounds("smart_card_youtube")
            val geometry="latency=$latency node=$node target=$target card=$outer"
            assertFalse("$skin: value clipped; ${detail("value",valueLayout)}; $geometry",valueLayout.didOverflowWidth || valueLayout.didOverflowHeight)
            assertEquals("$skin: value never splits; ${detail("value",valueLayout)}; $geometry",1,valueLayout.lineCount)
            val nodeLayout=layout(compose.onNodeWithTag("smart_node_youtube",useUnmergedTree=true))
            assertEquals("$skin: long node name ellipsized on one line; ${detail("node",nodeLayout)}; $geometry",1,nodeLayout.lineCount)
            assertTrue("$skin: latency inside card; $geometry",latency.right<=outer.right+1 && latency.left>=outer.left)
            assertTrue("$skin: target inside card; $geometry",target.right<=outer.right+1)
            assertTrue("$skin: lower line does not overlap; $geometry",node.right<=target.left+1 && latency.bottom<=target.top+1)
        }
        // Expanded details show the full values: not ellipsized, clipped or truncated at 2.0 font scale.
        compose.onNodeWithTag("smart_youtube").performTouchInput { click(androidx.compose.ui.geometry.Offset(8f,center.y)) }
        scrollTo("smart_current_youtube")
        for((tag,full) in listOf("smart_current_youtube" to longNode,"smart_goal_youtube" to longGroup)) {
            val text=compose.onNode(hasText(full,substring=true) and hasAnyAncestor(hasTestTag(tag)),useUnmergedTree=true)
            text.assertIsDisplayed()
            val l=layout(text)
            assertFalse(detail(tag,l),l.didOverflowWidth || l.didOverflowHeight || l.hasVisualOverflow)
            assertTrue(detail(tag,l),l.layoutInput.text.text.contains(full))
        }
        compose.onAllNodes(hasText(longGroup,substring=true) and hasAnyAncestor(hasTestTag("smart_card_youtube"))).assertCountEquals(2)
    }
}
