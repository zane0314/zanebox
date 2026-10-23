package com.zane.zanebox

import com.zane.zanebox.config.ConfigBuilder
import com.zane.zanebox.data.*
import com.zane.zanebox.runtime.TargetGroup
import com.zane.zanebox.runtime.TargetPlan
import com.zane.zanebox.runtime.restorableSelectorChoices
import com.zane.zanebox.runtime.savedChoiceDelay
import com.zane.zanebox.ui.targetLatencyLabel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TargetLatencyPlanTest {
    private fun node(id:Long,group:Long,ping:Int=-1,status:Int=0)=Node(id,group,"节点 $id","""{"type":"socks","server":"127.0.0.1","server_port":${10000+id}}""",ping=ping,status=status)
    private val base=AppData(
        nodes=listOf(node(1,1,ping=900,status=3),node(2,1,ping=5,status=3),node(3,2),node(4,3,ping=1,status=3)),
        groups=listOf(Group(1,"订阅 A","https://example.test/a"),Group(2,"订阅 B","https://example.test/b"),Group(3,"停用",enabled=false),
            Group(5,"固定",options="""{"groupIsSelector":false}""")),
        merges=listOf(MergeGroup(10,"手动",nodeIds=listOf(1,2,4),mode="selector"),MergeGroup(11,"自动",groupIds=listOf(1),mode="urltest")),
        settings=mapOf("selectedNodeId" to "2","smart.youtube.target" to "merge:10","smart.ai.target" to "auto"))

    @Test fun resolvesOnlyRealSelectorAndUrlTestGroups() {
        assertEquals(TargetGroup("merge-10",false,10),TargetPlan.resolveGroup(base,"merge:10",""))
        assertEquals(TargetGroup("merge-11",true,11),TargetPlan.resolveGroup(base,"merge:11",""))
        assertEquals(TargetGroup("group-1",false),TargetPlan.resolveGroup(base,"group:1",""))
        assertEquals(TargetGroup("smart-ai",true),TargetPlan.resolveGroup(base,"auto","ai"))
        assertNull(TargetPlan.resolveGroup(base,"auto",""))
        // groupIsSelector=false only limits a group to one member; it is still a selector, never URLTest.
        assertNull(TargetPlan.resolveGroup(base,"group:5",""))
        assertNull(TargetPlan.resolveGroup(base,"group:3",""))
        for(single in listOf("node:2","direct","off","block","proxy"))assertNull(single,TargetPlan.resolveGroup(base,single,"youtube"))
    }

    @Test fun planIsBoundToThePolicyThatAskedForIt() {
        assertNull(TargetPlan.create(base,"group:1","youtube"))
        val plan=TargetPlan.create(base,"merge:10","youtube")!!
        // Disabled-group node 4 and every non-member stay out of the batch.
        assertEquals(listOf(1L,2L),plan.members)
        assertTrue(plan.stillValid(base))
        assertFalse(plan.stillValid(base.copy(settings=base.settings+("smart.youtube.target" to "node:2"))))
        assertFalse(plan.stillValid(base.copy(merges=base.merges.map { if(it.id==10L)it.copy(nodeIds=listOf(2)) else it })))
        assertFalse(plan.stillValid(base.copy(merges=base.merges.map { if(it.id==10L)it.copy(mode="urltest") else it })))
    }

    @Test fun linkChangesDiscardTheFrozenRound() {
        val plan=TargetPlan.create(base,"merge:10","")!!
        val front=base.copy(groups=base.groups.map { if(it.id==1L)it.copy(frontProxy=3) else it })
        assertFalse(plan.stillValid(front))
        assertFalse(plan.stillValid(base.copy(groups=base.groups.map { if(it.id==1L)it.copy(landingProxy=3) else it })))
        assertFalse(plan.stillValid(base.copy(groups=base.groups.map { if(it.id==1L)it.copy(options="""{"groupIsSelector":false}""") else it })))
        assertFalse(plan.stillValid(base.copy(nodes=base.nodes.map { if(it.id==1L)it.copy(outbound="""{"type":"socks","server":"127.0.0.2","server_port":1}""") else it })))
        // A front proxy node's own outbound is part of the path, so editing it also invalidates the round.
        val withFront=base.copy(groups=base.groups.map { if(it.id==1L)it.copy(frontProxy=3) else it })
        val frontPlan=TargetPlan.create(withFront,"merge:10","")!!
        assertFalse(frontPlan.stillValid(withFront.copy(nodes=withFront.nodes.map { if(it.id==3L)it.copy(outbound="""{"type":"socks","server":"127.0.0.3","server_port":1}""") else it })))
        // Latency or status writes from the batch itself never invalidate it.
        assertTrue(plan.stillValid(base.copy(nodes=base.nodes.map { it.copy(ping=77,status=3) })))
    }

    @Test fun typedChainCandidatesAndNodeOverridesInvalidateTheRound() {
        val linked=base.copy(groups=base.groups.map {if(it.id==1L)it.copy(options="""{"chain":{"front":"none","landing":"group:2"}}""")else it})
        val plan=TargetPlan.create(linked,"node:1","")!!
        assertTrue(plan.stillValid(linked))
        assertFalse(plan.stillValid(linked.copy(nodes=linked.nodes+node(8,2))))
        assertFalse(plan.stillValid(linked.copy(nodes=linked.nodes.map {if(it.id==3L)it.copy(outbound="""{"type":"socks","server":"127.0.0.9","server_port":9}""")else it})))
        assertFalse(plan.stillValid(linked.copy(nodes=linked.nodes.map {if(it.id==1L)it.copy(metadata="""{"chain":{"front":"none","landing":"none"}}""")else it})))
    }

    @Test fun winnerIsStrictLowestSuccessAndIgnoresFailedOrEditedNodes() {
        val plan=TargetPlan.create(base,"merge:10","")!!
        assertEquals(2L to 40,plan.winner(base,mapOf(1L to 500,2L to 40)))
        assertEquals(1L to 40,plan.winner(base,mapOf(1L to 40,2L to 40)))
        assertEquals(1L to 800,plan.winner(base,mapOf(1L to 800,2L to -1)))
        assertNull(plan.winner(base,mapOf(1L to -1,2L to -2)))
        val edited=base.copy(nodes=base.nodes.map { if(it.id==2L)it.copy(outbound="""{"type":"socks","server":"127.0.0.1","server_port":1}""") else it })
        assertEquals(1L to 500,plan.winner(edited,mapOf(1L to 500,2L to 40)))
        assertEquals(mapOf("node-1" to 500,"node-2" to 0),plan.nativeResults(edited,mapOf(1L to 500,2L to 40)))
        assertEquals(mapOf("node-1" to 0,"node-2" to 0),plan.nativeResults(base,mapOf(1L to -1)))
    }

    @Test fun savedGroupChoiceBecomesTheSelectorDefaultOnlyWhenItIsSelectable() {
        fun groupDefault(data:AppData):String {
            val out=JSONObject(ConfigBuilder.build(data)).getJSONArray("outbounds")
            return (0 until out.length()).map { out.getJSONObject(it) }.single { it.optString("tag")=="group-1" }.getString("default")
        }
        assertEquals("node-2",groupDefault(base))
        assertEquals("node-1",groupDefault(base.copy(settings=base.settings+("groupSelection.group-1" to "node-1"))))
        assertEquals("node-2",groupDefault(base.copy(settings=base.settings+("groupSelection.group-1" to "node-3"))))
        val fixed=base.copy(groups=base.groups.map { if(it.id==1L)it.copy(options="""{"groupIsSelector":false}""") else it },settings=base.settings+("groupSelection.group-1" to "node-1"))
        assertEquals("node-2",groupDefault(fixed))
    }

    @Test fun targetLabelUsesLowestSuccessAndNeverInventsALatency() {
        assertEquals("5 ms",targetLatencyLabel(base,"merge:10"))
        assertEquals("5 ms",targetLatencyLabel(base,"node:2"))
        assertEquals("测试中",targetLatencyLabel(base,"merge:10",setOf(1L)))
        assertEquals("未测试",targetLatencyLabel(base,"node:3"))
        assertEquals("失败",targetLatencyLabel(base.copy(nodes=base.nodes.map { if(it.id==3L)it.copy(ping=-1,status=1) else it }),"node:3"))
        for(none in listOf("direct","off","block"))assertNull(none,targetLatencyLabel(base,none))
    }

    @Test fun restoreUsesTheSavedWinnerDelayEvenAfterAnAllFailedRound() {
        val saved=base.copy(settings=base.settings+("groupSelectionDelay.merge-11" to "40"))
        assertEquals(40,savedChoiceDelay(saved,"merge-11",-1))
        assertEquals(5,savedChoiceDelay(base,"merge-11",5))
        assertEquals(-1,savedChoiceDelay(base,"merge-11",-1))
        for(bad in listOf("0","-3","70000","abc",""))assertEquals(bad,7,savedChoiceDelay(base.copy(settings=base.settings+("groupSelectionDelay.merge-11" to bad)),"merge-11",7))
    }

    @Test fun frozenInputsFollowNestedChainsSourceDetoursAndGroupFrontLanding() {
        fun socks(host:String)="""{"type":"socks","server":"$host","server_port":1}"""
        fun chain(vararg ids:Long)="""{"type":"chain","node_ids":[${ids.joinToString(",")}]}"""
        val data=AppData(
            nodes=listOf(
                Node(6,1,"外层链",chain(7)),Node(7,1,"内层链",chain(8)),Node(8,1,"内层节点",socks("1.1.1.8")),
                Node(9,1,"带跳板","""{"type":"socks","server":"1.1.1.9","server_port":1,"detour":"hop"}"""),
                Node(10,1,"跳板来源",socks("1.1.1.10"),metadata="""{"sourceTag":"hop"}"""),
                Node(11,2,"前置链",chain(12)),Node(12,2,"前置内层",socks("1.1.1.12")),Node(13,2,"成员",socks("1.1.1.13")),
                Node(20,3,"无关",socks("1.1.1.20"))),
            groups=listOf(Group(1,"A","https://example.test/a"),Group(2,"B","https://example.test/b",frontProxy=11),Group(3,"C","https://example.test/c")))
        fun edit(id:Long,host:String)=data.copy(nodes=data.nodes.map { if(it.id==id)it.copy(outbound=socks(host)) else it })
        val nested=TargetPlan.create(data,"node:6","")!!
        assertTrue(nested.stillValid(data))
        assertFalse("内层嵌套链",nested.stillValid(edit(8,"9.9.9.8")))
        assertFalse("中间链",nested.stillValid(data.copy(nodes=data.nodes.map { if(it.id==7L)it.copy(outbound=chain(8,20)) else it })))
        assertTrue("无关节点",nested.stillValid(edit(20,"9.9.9.20")))
        val detour=TargetPlan.create(data,"node:9","")!!
        assertFalse("sourceTag 来源节点",detour.stillValid(edit(10,"9.9.9.10")))
        assertFalse("来源 metadata",detour.stillValid(data.copy(nodes=data.nodes.map { if(it.id==10L)it.copy(metadata="""{"sourceTag":"other"}""") else it })))
        val front=TargetPlan.create(data,"node:13","")!!
        assertFalse("分组前置链内层",front.stillValid(edit(12,"9.9.9.12")))
        assertTrue("非依赖",front.stillValid(edit(8,"9.9.9.8")))
    }

    @Test fun onlyLiveSelectorsWithAValidExplicitMarkerAreRestored() {
        fun restore(data:AppData)=restorableSelectorChoices(data,ConfigBuilder.build(data))
        val marked=base.copy(merges=base.merges.map { if(it.id==10L)it.copy(selectedId=1) else it },
            settings=base.settings+mapOf("groupSelection.group-1" to "node-1","groupSelection.merge-10" to "node-1",
                "groupSelection.merge-11" to "node-1","groupSelection.proxy" to "node-1"))
        // URLTest groups and the home selector are never handled here.
        assertEquals(mapOf("group-1" to "node-1","merge-10" to "node-1"),restore(marked))
        assertTrue(restore(base).isEmpty())
        // A later manual merge choice changes selectedId, so the old marker is stale; the group marker is untouched.
        val manual=marked.copy(merges=marked.merges.map { if(it.id==10L)it.copy(selectedId=2) else it })
        assertEquals(mapOf("group-1" to "node-1"),restore(manual))
        // A marker that is no longer a member of its group is ignored.
        assertEquals(mapOf("merge-10" to "node-1"),restore(marked.copy(settings=marked.settings+("groupSelection.group-1" to "node-3"))))
        // A merge switched to urltest is no longer a selector.
        assertEquals(mapOf("group-1" to "node-1"),restore(marked.copy(merges=marked.merges.map { if(it.id==10L)it.copy(mode="urltest") else it })))
    }
}
