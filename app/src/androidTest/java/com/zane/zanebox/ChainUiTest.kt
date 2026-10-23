package com.zane.zanebox

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.zane.zanebox.config.*
import com.zane.zanebox.data.*
import com.zane.zanebox.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChainUiTest {
    @get:Rule val compose=createComposeRule()
    private val a=Node(1,1,"机场 A","""{"type":"socks","server":"127.0.0.1","server_port":9}""")
    private val b=Node(2,2,"台湾 B","""{"type":"socks","server":"127.0.0.1","server_port":10}""")
    private val data=AppData(nodes=listOf(a,b),groups=listOf(Group(1,"机场"),Group(2,"家宽")))

    @Test fun originalNodeCreatesIndependentChainTargets() {
        var saved:ChainTargets?=null
        var savedName=""
        compose.setContent{MaterialTheme{ChainRouteEditor(a,null,data,{},false,""){name,targets->savedName=name;saved=targets}}}
        compose.onNodeWithTag("chain_name").assertTextContains("机场 A 链式代理")
        compose.onNodeWithTag("chain_landing_proxy").performClick()
        compose.onNode(hasText("家宽") and hasAnyAncestor(isDialog())).performClick()
        compose.onNode(hasText("台湾 B") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithTag("chain_name").assertTextContains("机场 A → 台湾 B 链式代理")
        compose.onNodeWithTag("chain_name").performTextReplacement("自定义 C")
        compose.onNodeWithTag("chain_front_proxy").performClick()
        compose.onNodeWithTag("choice_none").performClick()
        compose.onNodeWithTag("chain_save").performClick()
        assertEquals("自定义 C",savedName)
        assertEquals(ChainTargets("none","node:2"),saved)
    }
    @Test fun storedReverseOrderIsShownForwardAndReorderedOnSave() {
        var saved:Node?=null
        val chain=Node(3,1,"A 到 B","""{"type":"chain","node_ids":[2,1]}""")
        compose.setContent{MaterialTheme{NodeEditor(chain,data.copy(nodes=data.nodes+chain),onDismiss={},onSave={saved=it})}}
        compose.onNodeWithTag("subpage_list").performScrollToNode(hasTestTag("chain_step_0"))
        compose.onNodeWithTag("chain_step_0").assertIsDisplayed().assert(hasAnyDescendant(hasText("1. 机场 A")))
        compose.onNodeWithTag("subpage_list").performScrollToNode(hasTestTag("chain_step_1"))
        compose.onNodeWithTag("chain_step_1").assertIsDisplayed().assert(hasAnyDescendant(hasText("2. 台湾 B")))
        compose.onNodeWithTag("subpage_list").performScrollToNode(hasTestTag("chain_step_0"))
        compose.onNode(hasContentDescription("下移") and hasAnyAncestor(hasTestTag("chain_step_0"))).assertIsDisplayed().performClick()
        compose.onNodeWithTag("chain_step_0").assert(hasAnyDescendant(hasText("1. 台湾 B")))
        compose.onNodeWithTag("subpage_list").performScrollToNode(hasTestTag("chain_step_1"))
        compose.onNodeWithTag("chain_step_1").assert(hasAnyDescendant(hasText("2. 机场 A")))
        compose.onNodeWithTag("node_save").performClick()
        val ids=org.json.JSONObject(requireNotNull(saved).outbound).getJSONArray("node_ids")
        assertEquals(1L,ids.getLong(0));assertEquals(2L,ids.getLong(1))
        assertEquals(ChainTargets("inherit","inherit"),nodeChainTargets(requireNotNull(saved)))
    }
    @Test fun newChainKeepsOriginalNodesAndDoesNotInheritGroupChain() {
        var saved:Node?=null
        val originalA=a.copy(shareLink="socks://original-a",metadata="""{"source":"A"}""")
        val originalB=b.copy(shareLink="socks://original-b",metadata="""{"source":"B"}""")
        val configured=data.copy(nodes=listOf(originalA,originalB),groups=listOf(data.groups[0].withChainTargets(ChainTargets("node:2","node:1")),data.groups[1]))
        val before=configured.nodes.toList()
        compose.setContent{MaterialTheme{NodeEditor(null,configured,initialProtocol="chain",onDismiss={},onSave={saved=it})}}
        compose.onNodeWithTag("node_name").assertTextContains("新链路")
        for(name in listOf("机场 A","台湾 B")) {
            compose.onNodeWithTag("subpage_list").performScrollToNode(hasTestTag("chain_add_step"))
            compose.onNodeWithTag("chain_add_step").performClick()
            compose.onNode(hasText(if(name=="机场 A")"机场" else "家宽") and hasAnyAncestor(isDialog())).performClick()
            compose.onNode(hasText(name) and hasAnyAncestor(isDialog())).performClick()
        }
        compose.onNodeWithTag("subpage_list").performScrollToNode(hasTestTag("node_name"))
        compose.onNodeWithTag("node_name").assertTextContains("机场 A → 台湾 B 链式代理")
        compose.onNodeWithTag("node_name").performTextReplacement("新代理 C")
        compose.onNodeWithTag("node_save").performClick()
        val result=requireNotNull(saved)
        assertEquals("新代理 C",result.name)
        assertEquals(ChainTargets("none","none"),nodeChainTargets(result))
        val ids=org.json.JSONObject(result.outbound).getJSONArray("node_ids")
        assertEquals(2,ids.length());assertEquals(2L,ids.getLong(0));assertEquals(1L,ids.getLong(1))
        assertEquals(before,configured.nodes)
        assertEquals("socks://original-a",configured.nodes[0].shareLink)
        assertEquals("socks://original-b",configured.nodes[1].shareLink)
    }
    @Test fun chainPickerListsGroupsBeforeNodesAndSupportsEntireGroup() {
        var chosen=""
        compose.setContent{MaterialTheme{ChainTargetPicker("后置代理（落地）","none",data,{}, {chosen=it})}}
        compose.onNodeWithText("机场 A").assertDoesNotExist()
        compose.onNodeWithText("台湾 B").assertDoesNotExist()
        compose.onNodeWithText("家宽").performClick()
        compose.onNodeWithText("整个分组 · 自动最低延迟").performClick()
        assertEquals("group:2",chosen)
        compose.onNodeWithText("台湾 B").performClick()
        assertEquals("node:2",chosen)
    }
    @Test fun mergeSelectsWholeGroupAndIndividualNodeThroughGroups() {
        var saved:MergeGroup?=null
        compose.setContent{MaterialTheme{MergeEditor(MergeGroup(1,"汇总"),data,{},onSave={saved=it})}}
        compose.onNodeWithTag("merge_node_1").assertDoesNotExist()
        compose.onNodeWithTag("merge_node_2").assertDoesNotExist()
        compose.onNodeWithTag("subpage_list").performScrollToNode(hasTestTag("merge_group_open_1"))
        compose.onNodeWithTag("merge_group_open_1").performClick()
        compose.onNodeWithTag("merge_node_1").performClick()
        compose.onNodeWithTag("merge_members_done").performClick()
        compose.onNodeWithTag("subpage_list").performScrollToNode(hasTestTag("merge_group_check_2"))
        compose.onNodeWithTag("merge_group_check_2").performClick()
        compose.onNodeWithTag("merge_save").performClick()
        assertEquals(listOf(1L),requireNotNull(saved).nodeIds)
        assertEquals(listOf(2L),requireNotNull(saved).groupIds)
    }
    @Test fun missingSourceShowsFailureInsteadOfShortenedPath() {
        val chain=Node(3,1,"旧引用 C","""{"type":"chain","node_ids":[99,1]}""").withChainTargets(ChainTargets())
        compose.setContent{MaterialTheme{androidx.compose.material3.Text(chainPathText(data.copy(nodes=data.nodes+chain),3))}}
        compose.onNodeWithText("链路已失效：",substring=true).assertExists()
        compose.onNodeWithText("目标网站",substring=true).assertDoesNotExist()
    }
    @Test fun automaticSelectionShowsOnlyConfirmedMember() {
        val selection=androidx.compose.runtime.mutableStateOf("""{"node-1":"chain-1-landing-2"}""")
        val configured=data.copy(nodes=listOf(a.withChainTargets(ChainTargets("none","group:2")),b))
        compose.setContent{MaterialTheme{androidx.compose.material3.Text(chainPathText(configured,1,selection.value))}}
        compose.onNodeWithText("当前已选：台湾 B",substring=true).assertExists()
        compose.runOnIdle{selection.value="""{"node-1":"chain-1-unavailable"}"""}
        compose.onNodeWithText("当前已选：",substring=true).assertDoesNotExist()
        compose.onNodeWithText("家宽 · 自动最低延迟",substring=true).assertExists()
    }

}


class ChainHomeUiTest {
    @get:Rule val compose=androidx.compose.ui.test.junit4.createAndroidComposeRule<MainActivity>()
    @Test fun prominentEntryStaysAccessibleWithLongNamesAndDoubleFont() {
        val vm=androidx.lifecycle.ViewModelProvider(compose.activity)[AppViewModel::class.java]
        val longName="机场中转节点名称很长用于验证链式入口布局与操作不会互相遮挡"
        compose.runOnIdle{vm.edit{AppData(nodes=listOf(Node(1,1,longName,"""{"type":"socks","server":"127.0.0.1","server_port":9}""")),groups=listOf(Group(1,"机场订阅分组名称很长用于布局检查")),settings=mapOf("selectedNodeId" to "1","browseGroupId" to "0","appLanguage" to "zh-CN","fontScale" to "2.0"))}}
        compose.waitUntil(10000){!vm.busy.value && vm.data.value.nodes.any{it.name==longName}}
        compose.onNodeWithTag("home_chain_summary").assertIsDisplayed()
        val entry=compose.onNodeWithTag("home_chain_summary").getUnclippedBoundsInRoot()
        val groups=compose.onNodeWithTag("home_groups").getUnclippedBoundsInRoot()
        assertTrue("链式入口不得遮挡分组",entry.bottom<=groups.top)
        compose.onNodeWithTag("node_menu").performClick()
        compose.onNodeWithText("链式代理").performClick()
        compose.onNodeWithTag("chain_create").assertIsDisplayed().assertHasClickAction().performClick()
        compose.onNodeWithText("选择分组或节点").performClick()
        compose.onNode(hasText(longName) and hasAnyAncestor(isDialog())).assertDoesNotExist()
        compose.onNode(hasText("机场订阅分组名称很长用于布局检查") and hasAnyAncestor(isDialog())).performClick()
        compose.onNode(hasText(longName) and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithTag("chain_name").assertTextContains("$longName 链式代理")
        compose.onNodeWithTag("chain_landing_proxy").performClick()
        compose.onNodeWithTag("choice_none").performClick()
        compose.onNodeWithTag("chain_save").performClick()
        compose.waitUntil(10000){!vm.busy.value && vm.data.value.nodes.size==2}
        assertEquals(longName,vm.data.value.nodes.first{it.id==1L}.name)
        assertFalse(isChainNode(vm.data.value.nodes.first{it.id==1L}))
        val created=vm.data.value.nodes.first{it.id!=1L}
        assertTrue(isChainNode(created))
        assertEquals(ChainTargets(),nodeChainTargets(created))
    }
}
