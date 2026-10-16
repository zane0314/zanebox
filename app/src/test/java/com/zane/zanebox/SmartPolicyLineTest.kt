package com.zane.zanebox

import com.zane.zanebox.data.*
import com.zane.zanebox.ui.smartLatencyLabel
import com.zane.zanebox.ui.smartLowestNode
import org.junit.Assert.*
import org.junit.Test

class SmartPolicyLineTest {
    private fun node(id:Long,group:Long,ping:Int=-1,status:Int=0,name:String="节点 $id")=Node(id,group,name,"""{"type":"socks","server":"127.0.0.1","server_port":${10000+id}}""",ping=ping,status=status)
    private val base=AppData(
        nodes=listOf(node(1,1,ping=90,status=3,name="香港03"),node(2,1,ping=85,status=3,name="香港04"),node(3,2),node(4,3,ping=1,status=3,name="停用组最快")),
        groups=listOf(Group(1,"亚洲优选","https://example.test/a"),Group(2,"订阅 B","https://example.test/b"),Group(3,"停用","https://example.test/c",enabled=false)),
        merges=listOf(MergeGroup(10,"手动",nodeIds=listOf(1,2),mode="selector")),
        settings=mapOf("selectedNodeId" to "1","smart.youtube.target" to "group:1","smart.ai.target" to "auto","smart.x.target" to "node:2","smart.tiktok.target" to "merge:10"))

    @Test fun showsTheLowestPingNodeNotTheSelectedOneAndMatchesTheGroupValue() {
        // Node 1 is the selected node and the one in a stale marker, but node 2 is the lowest and is what the group value "85 ms" is from.
        val marked=base.copy(settings=base.settings+("groupSelection.group-1" to "node-1")+("groupSelection.merge-10" to "node-1"))
        for((value,label) in listOf("group:1" to "85 ms","merge:10" to "85 ms")) {
            assertEquals(value,"香港04",smartLowestNode(marked,value,emptySet()))
            assertEquals(value,label,smartLatencyLabel(marked,value,emptySet()))
        }
        assertEquals("香港04",smartLowestNode(base,"group:1",emptySet()))
    }

    @Test fun autoTargetUsesSubscriptionNodesOnlyAndSkipsDisabledGroups() {
        val data=base.copy(nodes=base.nodes.map { if(it.id==3L)it.copy(ping=60,status=3,name="订阅B最快") else it })
        // "auto" covers every enabled subscription group; the disabled group's 1 ms node is never chosen.
        assertEquals("订阅B最快",smartLowestNode(data,"auto",emptySet()))
        assertEquals("60 ms",smartLatencyLabel(data,"auto",emptySet()))
    }

    @Test fun failedAndUnmeasuredNodesAreNeverTheLowest() {
        val data=base.copy(nodes=base.nodes.map { when(it.id) { 2L->it.copy(ping=1,status=1,name="失败但低");1L->it.copy(ping=-2);else->it } }.plus(node(5,1,ping=300,status=3,name="香港05")))
        assertEquals("香港05",smartLowestNode(data,"group:1",emptySet()))
        assertEquals("300 ms",smartLatencyLabel(data,"group:1",emptySet()))
    }

    @Test fun equalLatencyFollowsMemberOrder() {
        val tied=base.copy(nodes=base.nodes.map { if(it.id==1L)it.copy(ping=85) else it })
        assertEquals("香港03",smartLowestNode(tied,"group:1",emptySet()))
        val reordered=tied.copy(nodes=listOf(tied.nodes[1],tied.nodes[0])+tied.nodes.drop(2))
        assertEquals("香港04",smartLowestNode(reordered,"group:1",emptySet()))
    }

    @Test fun noResultTestingOrNonGroupTargetsShowNoNode() {
        val unmeasured=base.copy(nodes=base.nodes.map { it.copy(ping=-1,status=0) })
        assertNull(smartLowestNode(unmeasured,"group:1",emptySet()))
        assertEquals("未测试",smartLatencyLabel(unmeasured,"group:1",emptySet()))
        val failed=base.copy(nodes=base.nodes.map { it.copy(ping=-1,status=1) })
        assertNull(smartLowestNode(failed,"group:1",emptySet()))
        assertEquals("失败",smartLatencyLabel(failed,"group:1",emptySet()))
        assertNull("a member is being tested",smartLowestNode(base,"group:1",setOf(1L)))
        assertEquals("测试中",smartLatencyLabel(base,"group:1",setOf(1L)))
        // Single node, direct, off, block, the home proxy and a missing group repeat no node and invent no group minimum.
        for(single in listOf("node:2","direct","off","block","proxy","group:99"))assertNull(single,smartLowestNode(base,single,emptySet()))
        assertEquals("85 ms",smartLatencyLabel(base,"node:2",emptySet()))
        assertNull(smartLatencyLabel(base,"direct",emptySet()))
    }

    @Test fun nodeNameCarriesNoPrefixOfAnyKindOffline() {
        // Same value connected or not: it only depends on the stored tests, never on selection markers or runtime state.
        val name=smartLowestNode(base,"group:1",emptySet())!!
        assertEquals("香港04",name)
        for(prefix in listOf("上次","已选","当前","最低"))assertFalse(prefix,name.contains(prefix))
        // The latency value is bare too: a group shows exactly the number, never a "组内最低" label.
        for(target in listOf("group:1","merge:10","auto","node:2")) {
            val label=smartLatencyLabel(base.copy(nodes=base.nodes.map { if(it.id==3L)it.copy(ping=60,status=3) else it }),target,emptySet())!!
            assertTrue(label,Regex("\\d+ ms").matches(label))
            assertFalse(label,label.contains("组内最低") || label.contains("最低"))
        }
    }
}
