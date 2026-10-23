package com.zane.zanebox

import com.zane.zanebox.config.*
import com.zane.zanebox.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChainRoutingTest {
    private fun node(id:Long,group:Long=1)=Node(id,group,"n$id","""{"type":"socks","server":"n$id.example","server_port":1080}""")
    private fun data()=AppData(nodes=listOf(node(1),node(2,2),node(3,2),node(4,3),node(5,3)),groups=listOf(Group(1,"主体"),Group(2,"前置"),Group(3,"落地")),settings=mapOf("selectedNodeId" to "1"))
    private fun outputs(data:AppData):Map<String,JSONObject> {
        val array=JSONObject(ConfigBuilder.build(data)).getJSONArray("outbounds")
        return (0 until array.length()).map { array.getJSONObject(it) }.associateBy { it.getString("tag") }
    }
    @Test fun sideOverridesAndRoundTrip() {
        val base=data();val group=base.groups.first().withChainTargets(ChainTargets("node:2","group:3"))
        val n=base.nodes.first().withChainTargets(ChainTargets("none","inherit"))
        val d=base.copy(groups=listOf(group)+base.groups.drop(1),nodes=listOf(n)+base.nodes.drop(1))
        assertEquals(2L,group.frontProxy);assertEquals(0L,group.landingProxy)
        assertEquals(ChainTargets("none","group:3"),effectiveChainTargets(d,n))
        assertEquals(listOf("n1","落地 · 自动"),chainPathLabels(d,1))
        assertEquals(nodeChainTargets(n),nodeChainTargets(AppData.fromJson(d.toJson()).nodes.first()))
    }
    @Test fun contextualDynamicLandingAndIsolatedCandidates() {
        val base=data();val n=base.nodes.first().withChainTargets(ChainTargets("group:2","group:3"))
        val out=outputs(base.copy(nodes=listOf(n)+base.nodes.drop(1)))
        assertEquals("chain-1-front",out.getValue("node-1-hop").getString("detour"))
        listOf(4,5).forEach { id->assertEquals("node-1-hop",out.getValue("chain-1-landing-$id").getString("detour")) }
        assertFalse(out.getValue("node-4").has("detour"))
        assertEquals(3,out.getValue("node-1").getJSONArray("outbounds").length())
        assertEquals("chain-1-unavailable",out.getValue("node-1").getString("default"))
        assertEquals("selector",out.getValue("node-1").getString("type"))
        assertFalse(out.getValue("node-1").getBoolean("restore_cache"))
        assertFalse(out.getValue("chain-1-front").getBoolean("restore_cache"))
        assertFalse(out.getValue("proxy").has("restore_cache"))
    }
    @Test fun fixedTargetsUseRawMembersAndOldChainOrder() {
        val base=data();val ref=base.nodes[1].withChainTargets(ChainTargets("node:4","none"))
        val chain=Node(6,1,"组合","""{"type":"chain","node_ids":[3,2]}""")
        val d=base.copy(nodes=listOf(base.nodes.first().withChainTargets(ChainTargets("node:2","none")),ref)+base.nodes.drop(2)+chain)
        val out=outputs(d)
        assertEquals("chain-1-front-2",out.getValue("node-1").getString("detour"))
        assertFalse(out.getValue("chain-1-front-2").has("detour"))
        assertEquals(listOf("n2","n3"),chainPathLabels(d,6))
        assertEquals("node-6-chain-1",out.getValue("node-6").getString("detour"))
    }
    @Test fun brokenReferencesAndDuplicatePathsFailClosed() {
        val base=data()
        listOf(ChainTargets("node:1"),ChainTargets("node:2","node:2")).forEach { t->
            val d=base.copy(nodes=listOf(base.nodes.first().withChainTargets(t))+base.nodes.drop(1))
            assertTrue(runCatching { outputs(d) }.isFailure)
        }
        listOf(ChainTargets("node:99"),ChainTargets("group:99")).forEach { target->
            val broken=base.copy(nodes=listOf(base.nodes.first().withChainTargets(target))+base.nodes.drop(1))
            val out=outputs(broken)
            assertEquals("block",out.getValue("node-1").getString("type"))
            assertEquals("socks",out.getValue("node-2").getString("type"))
            assertTrue(ConfigBuilder.warnings(broken).any { it.contains("已删除") })
        }
        val cyclic=Node(6,1,"循环","""{"type":"chain","node_ids":[6]}""")
        assertTrue(runCatching { outputs(base.copy(nodes=base.nodes+cyclic)) }.isFailure)
    }
    @Test fun originalDetourCannotBeSilentlyOverwritten() {
        val base=data();val n=base.nodes.first().copy(outbound="""{"type":"socks","server":"x.example","server_port":1080,"detour":"source"}""").withChainTargets(ChainTargets("node:2"))
        assertTrue(runCatching { outputs(base.copy(nodes=listOf(n)+base.nodes.drop(1))) }.isFailure)
    }
    @Test fun fixedBothSidesAndSameGroupMembersStayRaw() {
        val base=data();val group=base.groups.first().withChainTargets(ChainTargets("node:2","node:3"))
        val d=base.copy(groups=listOf(group)+base.groups.drop(1),nodes=base.nodes.map { if(it.id in listOf(2L,3L))it.copy(groupId=1)else it })
        val out=outputs(d)
        assertEquals("node-2",out.getValue("node-1-hop").getString("detour"))
        assertEquals("node-1-hop",out.getValue("node-1").getString("detour"))
        assertFalse(out.getValue("node-2").has("detour"));assertFalse(out.getValue("node-3").has("detour"))
    }
    @Test fun missingReferencesRemainEditableButCannotConnectOrExportDynamicChains() {
        val base=data();val broken=base.copy(nodes=base.nodes+Node(6,1,"失效链","""{"type":"chain","node_ids":[99]}"""))
        validateChainSettings(broken)
        assertEquals("block",outputs(broken).getValue("node-6").getString("type"))
        val testing=JSONObject(ConfigBuilder.build(broken,Purpose.TEST,6)).getJSONArray("outbounds")
        assertTrue((0 until testing.length()).any { testing.getJSONObject(it).optString("tag")=="node-6" && testing.getJSONObject(it).optString("type")=="block" })
        val dynamic=base.copy(nodes=listOf(base.nodes.first().withChainTargets(ChainTargets("group:2")))+base.nodes.drop(1),settings=base.settings+mapOf("statsEnabled" to "false","clashApi" to "false"))
        val root=JSONObject(ConfigBuilder.build(dynamic))
        assertTrue(root.getJSONObject("experimental").has("clash_api"))
        assertTrue(runCatching { ConfigBuilder.build(dynamic,Purpose.EXPORT) }.isFailure)
        val disabled=dynamic.copy(groups=dynamic.groups.map { if(it.id==2L)it.copy(enabled=false)else it })
        assertTrue(runCatching { outputs(disabled) }.isFailure)
    }
    @Test fun originalSourceDetourCyclesAndRepeatedExpansionAreRejected() {
        val a=node(1).copy(outbound="""{"type":"socks","server":"a.test","server_port":1080,"detour":"b"}""",metadata="""{"sourceTag":"a"}""")
        val b=node(2).copy(outbound="""{"type":"socks","server":"b.test","server_port":1080,"detour":"a"}""",metadata="""{"sourceTag":"b"}""")
        val d=AppData(nodes=listOf(a,b),groups=listOf(Group(1,"组")))
        assertTrue(runCatching { validateChainSettings(d) }.isFailure)
        assertTrue(runCatching { outputs(d) }.isFailure)
    }

    @Test fun mergeGroupsAlwaysReferenceCompleteChainEntries() {
        val base=data()
        val attached=base.nodes.first().withChainTargets(ChainTargets("node:2","node:4"))
        val standalone=Node(6,1,"A → B","""{"type":"chain","node_ids":[3,2]}""")
        val automatic=node(7).withChainTargets(ChainTargets("group:2","group:3"))
        listOf("selector","urltest").forEach { mode->
            listOf(false,true).forEach { byGroup->
                val merge=MergeGroup(1,"链路汇总",nodeIds=if(byGroup)emptyList()else listOf(1,6,7),groupIds=if(byGroup)listOf(1)else emptyList(),mode=mode,selectedId=6)
                val out=outputs(base.copy(nodes=listOf(attached)+base.nodes.drop(1)+standalone+automatic,merges=listOf(merge)))
                val members=out.getValue("merge-1").getJSONArray("outbounds")
                assertEquals(listOf("node-1","node-6","node-7"),(0 until members.length()).map { members.getString(it) })
                assertEquals("node-1-hop",out.getValue("node-1").getString("detour"))
                assertEquals("node-2",out.getValue("node-1-hop").getString("detour"))
                assertEquals("node-6-chain-1",out.getValue("node-6").getString("detour"))
                assertEquals("n3.example",out.getValue("node-6").getString("server"))
                assertEquals("n2.example",out.getValue("node-6-chain-1").getString("server"))
                assertEquals("selector",out.getValue("node-7").getString("type"))
                assertEquals("chain-7-front",out.getValue("node-7-hop").getString("detour"))
                val landingMembers=out.getValue("node-7").getJSONArray("outbounds")
                assertEquals("node-7-hop",out.getValue(landingMembers.getString(0)).getString("detour"))
                if(mode=="selector")assertEquals("node-6",out.getValue("merge-1").getString("default"))
                else assertEquals(base.setting("testUrl"),out.getValue("merge-1").getString("url"))
            }
        }
    }

    @Test fun onlyAutomaticParentsDependingOnDynamicChainsAreManaged() {
        val base=data()
        val dynamic=base.nodes.first().withChainTargets(ChainTargets("group:2","group:3"))
        val d=base.copy(nodes=listOf(dynamic)+base.nodes.drop(1),groups=base.groups.map { if(it.id==1L)it.copy(subscriptionUrl="https://fixture.test/sub")else it },
            merges=listOf(MergeGroup(1,"动态节点",nodeIds=listOf(1),mode="urltest"),MergeGroup(2,"动态分组",groupIds=listOf(1),mode="urltest"),MergeGroup(3,"普通节点",nodeIds=listOf(2,3),mode="urltest")),
            settings=base.settings+mapOf("homeAutoSelect" to "true","smart.ai.target" to "auto","smartRules.ai" to "DOMAIN,ai.fixture.test"))
        val out=outputs(d)
        listOf("merge-1","merge-2","home-auto","smart-ai").forEach { assertTrue(it,out.getValue(it).getBoolean("managed_test")) }
        assertFalse(out.getValue("merge-3").has("managed_test"))
        assertFalse(out.getValue("node-1").has("managed_test"))
        assertFalse(out.getValue("chain-1-front").has("managed_test"))
        val plain=outputs(d.copy(nodes=base.nodes))
        listOf("merge-1","merge-2","merge-3","home-auto","smart-ai").forEach { assertFalse(it,plain.getValue(it).has("managed_test")) }
    }

}
