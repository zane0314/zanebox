package com.zane.zanebox.config

import com.zane.zanebox.data.AppData
import com.zane.zanebox.data.Group
import com.zane.zanebox.data.Node
import com.zane.zanebox.data.withoutStaleNodeSettings
import org.json.JSONArray
import org.json.JSONObject

fun derivedGroupSourceId(group:Group):Long = JSONObject(group.options).optLong("chainSourceGroupId")
fun derivedNodeSourceId(node:Node):Long = JSONObject(node.metadata).optLong("chainSourceNodeId")
private fun AppData.largestRecordId():Long = (nodes.map { it.id }+groups.map { it.id }+rules.map { it.id }+merges.map { it.id }).maxOrNull() ?: 0
private fun AppData.derivedIdAllocator():()->Long { var next=largestRecordId();return { ++next } }
private fun derivedOutbound(sourceId:Long):String = JSONObject().put("type","chain").put("node_ids",JSONArray(listOf(sourceId))).toString()

fun AppData.createDerivedChainNode(sourceId:Long,name:String,targets:ChainTargets,allocateId:()->Long=derivedIdAllocator()):AppData {
    val source=nodes.firstOrNull { it.id==sourceId } ?: error("源节点已删除")
    require(name.isNotBlank()) { "链式节点名称不能为空" }
    require(JSONObject(source.outbound).optString("type")!="custom") { "完整自定义配置不能创建链式节点" }
    require(groups.any { it.id==source.groupId }) { "源节点分组不存在" }
    val destination=groups.firstOrNull { it.enabled && JSONObject(it.options).optBoolean("chainNodeContainer") && derivedGroupSourceId(it)==0L }
        ?: Group(allocateId(),"链式节点",order=(groups.maxOfOrNull { it.order } ?: -1)+1,options="""{"chainNodeContainer":true}""")
    val node=Node(allocateId(),destination.id,name.trim(),derivedOutbound(sourceId),order=(nodes.filter { it.groupId==destination.id }.maxOfOrNull { it.order } ?: -1)+1,
        metadata=JSONObject().put("chainSourceNodeId",sourceId).toString()).withChainTargets(targets)
    val result=copy(groups=if(groups.any { it.id==destination.id })groups else groups+destination,nodes=nodes+node)
    validateChainSettings(result)
    require(chainUnavailableReason(result,node)==null) { chainUnavailableReason(result,node).orEmpty() }
    return result
}

fun AppData.createDerivedChainGroup(sourceGroupId:Long,name:String,targets:ChainTargets,allocateId:()->Long=derivedIdAllocator()):AppData {
    val source=groups.firstOrNull { it.id==sourceGroupId } ?: error("源分组已删除")
    require(derivedGroupSourceId(source)==0L) { "暂不支持以派生分组继续创建派生分组" }
    require(name.isNotBlank()) { "链式分组名称不能为空" }
    require(nodes.any { it.groupId==source.id }) { "源分组没有节点" }
    val group=Group(allocateId(),name.trim(),order=(groups.maxOfOrNull { it.order } ?: -1)+1,
        options=JSONObject().put("chainSourceGroupId",source.id).toString()).withChainTargets(targets)
    val result=copy(groups=groups+group).syncDerivedChains(allocateId)
    validateChainSettings(result)
    result.nodes.filter { it.groupId==group.id }.forEach { member->require(chainUnavailableReason(result,member)==null) { chainUnavailableReason(result,member).orEmpty() } }
    return result
}

/** Generated members track source identities; source records and manually authored chains stay untouched. */
fun AppData.syncDerivedChains(allocateId:()->Long=derivedIdAllocator()):AppData {
    val derived=groups.filter { derivedGroupSourceId(it)>0 }
    if(derived.isEmpty())return this
    val byGroup=nodes.groupBy { it.groupId }
    val groupsById=groups.associateBy { it.id }
    val members=mutableMapOf<Long,List<Node>>()
    val updatedGroups=groups.map { group->
        val sourceId=derivedGroupSourceId(group)
        if(sourceId==0L)return@map group
        val source=groupsById[sourceId]
        require(sourceId!=group.id && (source==null || derivedGroupSourceId(source)==0L)) { "派生分组不能引用自身或另一派生分组" }
        val options=JSONObject(group.options)
        if(source==null) {
            options.put("chainSourceMissing",true)
        } else {
            options.remove("chainSourceMissing")
            val old=byGroup[group.id].orEmpty()
            val generated=old.filter { derivedNodeSourceId(it)>0 }.groupBy(::derivedNodeSourceId)
            var order=(old.maxOfOrNull { it.order } ?: -1)+1
            members[group.id]=old.filter { derivedNodeSourceId(it)==0L }+byGroup[sourceId].orEmpty().map { node->
                generated[node.id]?.firstOrNull()?.let { previous->
                    val metadata=JSONObject(previous.metadata)
                    val oldSourceName=metadata.optString("chainSourceName",previous.name)
                    previous.copy(name=if(previous.name==oldSourceName)node.name else previous.name,outbound=derivedOutbound(node.id),metadata=metadata.put("chainSourceName",node.name).toString())
                } ?: Node(allocateId(),group.id,node.name,derivedOutbound(node.id),order=order++,metadata=JSONObject().put("chainSourceNodeId",node.id).put("chainSourceName",node.name).toString())
            }
        }
        val value=options.toString()
        if(group.options==value)group else group.copy(options=value)
    }
    val updatedNodes=nodes.filter { it.groupId !in members }+members.values.flatten()
    val updatedIds=updatedNodes.map { it.id }.toSet()
    val removed=nodes.filter { n->n.groupId in members && n.id !in updatedIds }
    val removedIds=removed.map { it.id }.toSet()
    val removedGroups=removed.associate { it.id to it.groupId }
    var updatedSettings=settings.mapValues { (key,value)->
        if(key.startsWith("smart.") && key.endsWith(".target") && value.startsWith("node:"))removedGroups[value.substringAfter(':').toLongOrNull()]?.let { "group:$it" } ?: value else value
    }
    val selectedGroup=groupsById[selectedGroupId]?.takeIf { derivedGroupSourceId(it)>0 }
        ?: nodes.firstOrNull { it.id==selectedNodeId }?.let { groupsById[it.groupId] }?.takeIf { derivedGroupSourceId(it)>0 }
    if(selectedGroup!=null && updatedNodes.none { it.id==selectedNodeId && it.groupId==selectedGroup.id }) {
        val replacement=updatedNodes.filter { it.groupId==selectedGroup.id }.minByOrNull { it.order }
        updatedSettings=updatedSettings+mapOf("selectedNodeId" to (replacement?.id ?: 0L).toString(),"selectedGroupId" to selectedGroup.id.toString())
    }
    val result=copy(groups=updatedGroups,nodes=updatedNodes,settings=updatedSettings,
        rules=rules.map { r->if(r.outbound.startsWith("node:") && r.outbound.substringAfter(':').toLongOrNull() in removedIds)r.copy(outbound="group:${removedGroups.getValue(r.outbound.substringAfter(':').toLong())}")else r },
        merges=merges.map { m->m.copy(nodeIds=m.nodeIds.filter { it !in removedIds },selectedId=if(m.selectedId in removedIds)0 else m.selectedId) })
    return if(removedIds.isEmpty())result else result.withoutStaleNodeSettings()
}

fun validateDerivedChains(data:AppData) {
    val groups=data.groups.associateBy { it.id }
    data.groups.forEach { group->
        val options=JSONObject(group.options)
        if(options.has("chainSourceGroupId")) {
            val sourceId=derivedGroupSourceId(group)
            require(sourceId>0 && sourceId!=group.id && group.subscriptionUrl.isBlank()) { "派生分组来源或订阅地址无效" }
            require(groups[sourceId]?.let { derivedGroupSourceId(it)==0L }!=false) { "暂不支持多层派生分组" }
        }
    }
    data.nodes.forEach { node->if(JSONObject(node.metadata).has("chainSourceNodeId"))require(derivedNodeSourceId(node)>0 && JSONObject(node.outbound).optString("type")=="chain") { "派生节点来源无效" } }
}

fun derivedGroupUnavailableReason(data:AppData,group:Group):String? {
    val sourceId=derivedGroupSourceId(group)
    if(sourceId==0L)return null
    if(data.groups.none { it.id==sourceId })return "源分组已删除"
    if(data.nodes.none { it.groupId==sourceId })return "源分组没有节点"
    return null
}
