package com.zane.zanebox

import com.zane.zanebox.config.*
import com.zane.zanebox.data.*
import com.zane.zanebox.subscription.*
import com.zane.zanebox.ui.withEnabledSelection
import com.zane.zanebox.backup.BackupManager
import com.zane.zanebox.backup.BackupScope
import com.zane.zanebox.backup.backupSelection
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DerivedChainsTest {
    private fun node(id:Long,group:Long,name:String="A$id")=Node(id,group,name,JSONObject("""{"type":"socks","server":"n$id.example","server_port":1080}""").toString(),shareLink="source-$id",ping=15,tx=42,rx=71,order=id.toInt(),status=1,metadata="""{"annotation":"keep-$id"}""")
    private fun source()=AppData(nodes=listOf(node(10,1),node(11,1),node(20,2)),groups=listOf(Group(1,"机场","https://fixture.test/sub",updatedAt=123,userInfo="remain",options="""{"future":true}"""),Group(2,"台湾",order=1)),settings=mapOf("selectedNodeId" to "10","selectedGroupId" to "1"))
    private fun assertSources(before:AppData,after:AppData) {
        before.nodes.forEach { assertEquals(it,after.nodes.single { n->n.id==it.id }) }
        before.groups.forEach { assertEquals(it,after.groups.single { g->g.id==it.id }) }
    }
    @Test fun singleChainUsesDedicatedContainerAndLeavesEverySourceFieldUntouched() {
        val base=source()
        val first=base.createDerivedChainNode(10,"机场台湾链式代理",ChainTargets(landing="node:20"))
        assertSources(base,first)
        val c=first.nodes.single { it.id !in base.nodes.map(Node::id) }
        assertEquals(10L,JSONObject(c.outbound).getJSONArray("node_ids").getLong(0))
        assertEquals("机场台湾链式代理",c.name)
        assertNotEquals(1L,c.groupId)
        assertTrue(JSONObject(first.groups.single { it.id==c.groupId }.options).getBoolean("chainNodeContainer"))
        assertEquals(ChainTargets("none","node:20"),nodeChainTargets(c))
        val second=first.createDerivedChainNode(11,"另一个C",ChainTargets(landing="group:2"))
        assertEquals(first.groups,second.groups)
        assertSources(base,second)
        assertEquals(base.settings,second.settings)
        assertTrue(runCatching { base.createDerivedChainNode(10,"错误新链",ChainTargets("node:999")) }.isFailure)
    }
    @Test fun derivedGroupKeepsSourceURLAndMetadataOutOfCAndRoundTripsBackup() {
        val base=source()
        val d=base.createDerivedChainGroup(1,"台湾链式分组",ChainTargets(landing="group:2"))
        assertSources(base,d)
        val group=d.groups.single { derivedGroupSourceId(it)>0 }
        assertEquals("",group.subscriptionUrl)
        assertEquals(1L,derivedGroupSourceId(group))
        val members=d.nodes.filter { it.groupId==group.id }
        assertEquals(setOf(10L,11L),members.map(::derivedNodeSourceId).toSet())
        members.forEach { assertFalse(JSONObject(it.metadata).has("annotation"));assertEquals(ChainTargets("inherit","inherit"),nodeChainTargets(it)) }
        assertEquals(d,AppData.fromJson(d.toJson()))
        val backup=BackupManager()
        assertEquals(d,backup.`import`(backup.export(backupSelection(d,BackupScope(rules=false,settings=false)))).copy(settings=d.settings))
        assertEquals(d,d.syncDerivedChains())
    }
    @Test fun synchronizationKeepsIdsCustomNamesStateOrderAndLocalChainSettings() {
        val base=source().createDerivedChainGroup(1,"C",ChainTargets(landing="node:20"))
        val group=base.groups.single { derivedGroupSourceId(it)>0 }
        val old=base.nodes.single { it.groupId==group.id && derivedNodeSourceId(it)==10L }
        val customized=old.copy(name="我命名的C",ping=7,status=2,tx=100,rx=200,order=99).withChainTargets(ChainTargets("none","none"))
        val changed=base.copy(nodes=base.nodes.filter { it.id!=11L }.map { if(it.id==10L)it.copy(name="源改名")else if(it.id==old.id)customized else it }+node(12,1))
        val result=changed.syncDerivedChains()
        val kept=result.nodes.single { it.id==old.id }
        assertEquals(customized.copy(metadata=JSONObject(customized.metadata).put("chainSourceName","源改名").toString()),kept)
        assertEquals(setOf(10L,12L),result.nodes.filter { it.groupId==group.id }.map(::derivedNodeSourceId).toSet())
        assertEquals(result,result.syncDerivedChains())
        assertEquals(base.groups.first(),result.groups.first())
    }
    @Test fun defaultMemberNameFollowsSourceButGroupNameNeverChanges() {
        val d=source().createDerivedChainGroup(1,"自定义C名称",ChainTargets())
        val group=d.groups.single { derivedGroupSourceId(it)>0 }
        val changed=d.copy(nodes=d.nodes.map { if(it.id==10L)it.copy(name="最新源名")else it }).syncDerivedChains()
        assertEquals("自定义C名称",changed.groups.single { it.id==group.id }.name)
        assertEquals("最新源名",changed.nodes.single { it.groupId==group.id && derivedNodeSourceId(it)==10L }.name)
    }
    @Test fun removedSelectedMemberStaysWithinCAndEmptyCDoesNotFallBackToSource() {
        val d=source().createDerivedChainGroup(1,"C",ChainTargets(landing="node:20"))
        val group=d.groups.single { derivedGroupSourceId(it)>0 }
        val selected=d.nodes.single { it.groupId==group.id && derivedNodeSourceId(it)==11L }
        val selectedData=d.copy(settings=d.settings+mapOf("selectedNodeId" to selected.id.toString(),"selectedGroupId" to group.id.toString()))
        val one=selectedData.copy(nodes=selectedData.nodes.filter { it.id!=11L }).syncDerivedChains().withEnabledSelection()
        assertEquals(group.id,one.selectedGroupId)
        assertEquals(10L,derivedNodeSourceId(one.nodes.single { it.id==one.selectedNodeId }))
        val empty=one.copy(nodes=one.nodes.filter { it.groupId!=1L }).syncDerivedChains().withEnabledSelection()
        assertEquals(group.id,empty.selectedGroupId);assertEquals(0L,empty.selectedNodeId)
        assertTrue(runCatching { ConfigBuilder.build(empty) }.isFailure)
    }
    @Test fun deletingSourcePreservesMissingCAndDeletingCDoesNotTouchSources() {
        val base=source();val d=base.createDerivedChainGroup(1,"C",ChainTargets(landing="node:20"))
        val group=d.groups.single { derivedGroupSourceId(it)>0 }
        val removed=nodeRemovalIds(d.nodes,setOf(10,11))
        assertEquals(setOf(10L,11L),removed)
        val missing=d.copy(groups=d.groups.filter { it.id!=1L },nodes=d.nodes.filter { it.id !in removed },settings=emptyMap()).syncDerivedChains()
        assertTrue(JSONObject(missing.groups.single { it.id==group.id }.options).getBoolean("chainSourceMissing"))
        assertEquals(2,missing.nodes.count { it.groupId==group.id })
        val missingOut=JSONObject(ConfigBuilder.build(missing)).getJSONArray("outbounds")
        val missingMap=(0 until missingOut.length()).map { missingOut.getJSONObject(it) }.associateBy { it.getString("tag") }
        missing.nodes.filter { it.groupId==group.id }.forEach { assertEquals("block",missingMap.getValue("node-${it.id}").getString("type")) }
        assertEquals("socks",missingMap.getValue("node-20").getString("type"))
        val withoutC=d.copy(groups=d.groups.filter { it.id!=group.id },nodes=d.nodes.filter { it.groupId!=group.id }).syncDerivedChains()
        assertEquals(base,withoutC)
    }
    @Test fun sourceSubscriptionRefreshSynchronizesOnlyGeneratedMembers() {
        val base=source().createDerivedChainGroup(1,"C",ChainTargets(landing="node:20"))
        val group=base.groups.single { derivedGroupSourceId(it)>0 }
        val keptId=base.nodes.single { it.groupId==group.id && derivedNodeSourceId(it)==10L }.id
        val manual=Node(50,group.id,"手工链","""{"type":"chain","node_ids":[20,10]}""",metadata="""{"chain":{"front":"none","landing":"none"}}""")
        val started=SubscriptionUpdater.begin(base.copy(nodes=base.nodes+manual),base.groups.first(),"request",1000)
        var nextId=60L
        val result=SubscriptionUpdater.apply(started,started.groups.first(),listOf(ParsedNode("更新A",base.nodes.first().outbound),ParsedNode("新源",node(12,1).outbound)),"new-info",2000) { nextId++ }
        assertEquals(manual,result.nodes.single { it.id==50L })
        assertEquals(keptId,result.nodes.single { it.groupId==group.id && derivedNodeSourceId(it)==10L }.id)
        assertEquals(setOf(10L,60L),result.nodes.filter { it.groupId==group.id && derivedNodeSourceId(it)>0 }.map(::derivedNodeSourceId).toSet())
        assertEquals(base.groups[1],result.groups.single { it.id==2L })
        assertEquals(result,result.syncDerivedChains())
    }
    @Test fun nestedDerivedGroupsAreRejectedButDisabledSourcesRemainIndependent() {
        val d=source().createDerivedChainGroup(1,"C",ChainTargets())
        val group=d.groups.single { derivedGroupSourceId(it)>0 }
        assertTrue(runCatching { d.createDerivedChainGroup(group.id,"D",ChainTargets()) }.isFailure)
        val disabled=d.copy(groups=d.groups.map { if(it.id==1L)it.copy(enabled=false)else it },settings=d.settings+mapOf("selectedNodeId" to d.nodes.first { it.groupId==group.id }.id.toString(),"selectedGroupId" to group.id.toString()))
        ConfigBuilder.build(disabled)
    }
    @Test fun twoCompleteAddressAndNameReplacementsKeepCAndGroupTargetsStable() {
        val original=source()
        val initial=original.createDerivedChainGroup(1,"我的AB链式代理",ChainTargets(landing="group:2")).createDerivedChainNode(10,"固定源C",ChainTargets(landing="group:2"))
        val fixedC=initial.nodes.single { derivedNodeSourceId(it)==10L && initial.groups.single { group->group.id==it.groupId }.let { derivedGroupSourceId(it)==0L } }
        val c=initial.groups.single { derivedGroupSourceId(it)>0 }
        val selected=initial.nodes.first { it.groupId==c.id }
        var data=initial.copy(merges=listOf(MergeGroup(80,"汇总C",groupIds=listOf(c.id),mode="urltest")),settings=initial.settings+mapOf("selectedNodeId" to selected.id.toString(),"selectedGroupId" to c.id.toString()))
        fun refresh(groupId:Long,firstId:Long,round:Int):AppData {
            val group=data.groups.single { it.id==groupId }
            val started=SubscriptionUpdater.begin(data,group,"$round-$groupId",round*1000L+groupId)
            var next=firstId
            val parsed=(0..1).map { offset->ParsedNode("全新名字-$round-$groupId-$offset",node(firstId+offset,groupId).outbound.replace("n${firstId+offset}.example","192.0.$round.${firstId+offset-90}")) }
            return SubscriptionUpdater.apply(started,started.groups.single { it.id==groupId },parsed,"round-$round",round*1000L+100+groupId) { next++ }
        }
        (1..2).forEach { round->
            val priorMemberIds=data.nodes.filter { it.groupId==c.id }.map { it.id }.toSet()
            data=refresh(1,100L*round,round)
            assertTrue(data.nodes.filter { it.groupId==c.id }.none { it.id in priorMemberIds })
            data=refresh(2,100L*round+10,round)
            assertEquals(c.id,data.selectedGroupId)
            assertTrue(data.nodes.any { it.id==data.selectedNodeId && it.groupId==c.id })
            val currentC=data.groups.single { it.id==c.id }
            assertEquals(c.name,currentC.name)
            assertEquals("group:2",groupChainTargets(currentC).landing)
            assertEquals(1L,derivedGroupSourceId(currentC))
            assertEquals(listOf(c.id),data.merges.single().groupIds)
            val root=JSONObject(ConfigBuilder.build(data));val out=root.getJSONArray("outbounds")
            val values=(0 until out.length()).map { out.getJSONObject(it) }.associateBy { it.getString("tag") }
            assertEquals("block",values.getValue("node-${fixedC.id}").getString("type"))
            assertEquals("链路引用的节点已删除",chainUnavailableReason(data,data.nodes.single { it.id==fixedC.id }))
            val merged=values.getValue("merge-80").getJSONArray("outbounds")
            assertEquals(data.nodes.filter { it.groupId==c.id }.map { "node-${it.id}" },(0 until merged.length()).map { merged.getString(it) })
            listOf(10L,11L,20L).forEach { old->assertFalse(values.containsKey("node-$old")) }
            assertFalse(root.toString().contains("n10.example"));assertFalse(root.toString().contains("n20.example"))
            data.nodes.filter { it.groupId==c.id }.forEach { member->
                val sourceId=JSONObject(member.outbound).getJSONArray("node_ids").getLong(0)
                assertTrue(data.nodes.any { it.id==sourceId && it.groupId==1L })
                val candidates=values.getValue("node-${member.id}").getJSONArray("outbounds")
                val expected=data.nodes.filter { it.groupId==2L }.map { "chain-${member.id}-landing-${it.id}" }.toSet()
                assertEquals(expected,(0 until candidates.length()).map { candidates.getString(it) }.filterNot { it.endsWith("-unavailable") }.toSet())
            }
        }
    }

    @Test fun sharedAllocatorKeepsReservedIdsOutOfDerivedRecords() {
        val base=source()
        var sequence=20L
        val reserved=++sequence
        val created=base.createDerivedChainGroup(1,"C",ChainTargets(),allocateId={++sequence})
        assertFalse(created.groups.any { it.id==reserved })
        assertFalse(created.nodes.any { it.id==reserved })
        assertSources(base,created)
        val group=created.groups.single { derivedGroupSourceId(it)>0 }
        val moved=created.copy(nodes=created.nodes.map { if(it.id==20L)it.copy(groupId=1)else it }).syncDerivedChains(allocateId={++sequence})
        assertNotEquals(reserved,moved.nodes.single { it.groupId==group.id && derivedNodeSourceId(it)==20L }.id)
        val single=moved.createDerivedChainNode(10,"独立C",ChainTargets(),allocateId={++sequence})
        assertFalse(single.groups.any { it.id==reserved })
        assertFalse(single.nodes.any { it.id==reserved })
        assertEquals(base.nodes.first(),single.nodes.single { it.id==10L })
    }

}
