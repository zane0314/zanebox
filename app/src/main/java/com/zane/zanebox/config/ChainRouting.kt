package com.zane.zanebox.config

import com.zane.zanebox.data.AppData
import com.zane.zanebox.data.Group
import com.zane.zanebox.data.Node
import org.json.JSONObject

data class ChainTargets(val front:String="none", val landing:String="none")

fun groupChainTargets(group:Group):ChainTargets {
    val chain=JSONObject(group.options).optJSONObject("chain")
    return ChainTargets(chain?.optString("front")?.takeIf { it.isNotBlank() } ?: if(group.frontProxy>0)"node:${group.frontProxy}" else "none",
        chain?.optString("landing")?.takeIf { it.isNotBlank() } ?: if(group.landingProxy>0)"node:${group.landingProxy}" else "none")
}
fun nodeChainTargets(node:Node):ChainTargets {
    val chain=JSONObject(node.metadata).optJSONObject("chain")
    return ChainTargets(chain?.optString("front","inherit") ?: "inherit",chain?.optString("landing","inherit") ?: "inherit")
}
fun effectiveChainTargets(data:AppData,node:Node):ChainTargets {
    val own=nodeChainTargets(node)
    val group=data.groups.firstOrNull { it.id==node.groupId }
    val inherited=group?.let(::groupChainTargets) ?: ChainTargets()
    // Attachment members keep their raw connection when inheriting their group's chain.
    val exempt=listOf(inherited.front,inherited.landing).any { it=="node:${node.id}" }
    val base=if(exempt)ChainTargets()else inherited
    return ChainTargets(if(own.front=="inherit")base.front else own.front,if(own.landing=="inherit")base.landing else own.landing)
}
fun Group.withChainTargets(targets:ChainTargets):Group = copy(options=JSONObject(options).put("chain",JSONObject().put("front",targets.front).put("landing",targets.landing)).toString(),
    frontProxy=targets.front.takeIf { it.startsWith("node:") }?.substringAfter(':')?.toLongOrNull() ?: 0,
    landingProxy=targets.landing.takeIf { it.startsWith("node:") }?.substringAfter(':')?.toLongOrNull() ?: 0)
fun Node.withChainTargets(targets:ChainTargets):Node = copy(metadata=JSONObject(metadata).put("chain",JSONObject().put("front",targets.front).put("landing",targets.landing)).toString())

internal fun chainTargetNodes(data:AppData,target:String,allowMissing:Boolean=false):List<Node> {
    if(target=="none")return emptyList()
    val id=target.substringAfter(':').toLongOrNull() ?: error("代理链目标无效: $target")
    val result=when {
        target.startsWith("node:")->data.nodes.filter { it.id==id }
        target.startsWith("group:")->data.nodes.filter { it.groupId==id }
        else->error("代理链目标无效: $target")
    }
    if(allowMissing && result.isEmpty())return result
    require(result.isNotEmpty()) { "代理链目标不存在或分组为空: $target" }
    require(allowMissing || result.all { n->data.groups.any { it.id==n.groupId && it.enabled } }) { "代理链目标分组已停用: $target" }
    require(result.none { JSONObject(it.outbound).optString("type")=="custom" }) { "完整自定义配置不能嵌入代理链" }
    return result
}

internal fun rawChainIds(data:AppData,id:Long,active:MutableSet<Long> = mutableSetOf(),allowMissing:Boolean=false,nodesById:Map<Long,Node> = data.nodes.associateBy { it.id }):List<Long> {
    require(active.size<64 && active.add(id)) { "代理链引用循环或过深" }
    val node=nodesById[id]
    if(node==null && allowMissing) { active.remove(id);return emptyList() }
    require(node!=null) { "代理链节点不存在: $id" }
    val bean=JSONObject(node.outbound)
    val ids=if(bean.optString("type")=="chain") {
        val members=bean.getJSONArray("node_ids");require(members.length()>0) { "代理链为空" }
        require((0 until members.length()).map { members.getLong(it) }.distinct().size==members.length()) { "代理链不能重复经过同一节点" }
        (members.length()-1 downTo 0).flatMap { rawChainIds(data,members.getLong(it),active,allowMissing,nodesById) }
    } else {
        require(bean.optString("type")!="custom") { "完整自定义配置不能嵌入代理链" };listOf(id)
    }
    active.remove(id)
    require(ids.distinct().size==ids.size) { "代理链不能重复经过同一节点" }
    return ids
}

internal fun rawRouteIds(data:AppData,path:List<Long>,active:MutableSet<Long> = mutableSetOf(),nodesById:Map<Long,Node> = data.nodes.associateBy { it.id },allowMissing:Boolean=false):List<Long> {
    if(path.isEmpty())return path
    require(active.size<64 && path.none { it in active }) { "代理链引用循环或过深" }
    val nodes=nodesById
    path.forEachIndexed { index,id->require(index==0 || !JSONObject(nodes.getValue(id).outbound).has("detour")) { "代理链与节点原有 detour 冲突" } }
    val first=nodes.getValue(path.first());val detour=JSONObject(first.outbound).optString("detour")
    val upstream=if(detour.isBlank() || detour=="direct")emptyList() else {
        val source=data.nodes.firstOrNull { it.groupId==first.groupId && JSONObject(it.metadata).optString("sourceTag")==detour }
        if(source==null)emptyList() else {
            active.addAll(path)
            val result=rawRouteIds(data,rawChainIds(data,source.id,nodesById=nodesById,allowMissing=allowMissing),active,nodesById,allowMissing)
            active.removeAll(path.toSet());result
        }
    }
    val result=upstream+path
    require(result.distinct().size==result.size) { "代理链不能重复经过同一节点" }
    return result
}

fun validateChainTargetSyntax(data:AppData) {
    fun syntax(value:String,node:Boolean) { require(value=="none" || (node && value=="inherit") || (value.matches(Regex("(node|group):[1-9][0-9]*")) && value.substringAfter(':').toLongOrNull()!=null)) { "代理链目标无效: $value" } }
    data.groups.forEach { val t=groupChainTargets(it);syntax(t.front,false);syntax(t.landing,false) }
    data.nodes.forEach { val t=nodeChainTargets(it);syntax(t.front,true);syntax(t.landing,true) }
}

internal fun validateChainNodeSettings(data:AppData,n:Node,nodesById:Map<Long,Node> = data.nodes.associateBy { it.id }) {
    if(JSONObject(n.outbound).optString("type")=="custom")return
    val body=rawChainIds(data,n.id,allowMissing=true,nodesById=nodesById)
    val t=effectiveChainTargets(data,n)
    val fronts=chainTargetNodes(data,t.front,true).map { rawChainIds(data,it.id,allowMissing=true,nodesById=nodesById) }.ifEmpty { listOf(emptyList()) }
    val landings=chainTargetNodes(data,t.landing,true).map { rawChainIds(data,it.id,allowMissing=true,nodesById=nodesById) }.ifEmpty { listOf(emptyList()) }
    val frontIds=fronts.flatMap { rawRouteIds(data,it,nodesById=nodesById,allowMissing=true) }.toSet();val landingIds=landings.flatten().toSet()
    val bodyIds=if(frontIds.isEmpty())rawRouteIds(data,body,nodesById=nodesById,allowMissing=true)else body
    require(bodyIds.none { it in frontIds || it in landingIds } && frontIds.intersect(landingIds).isEmpty()) { "代理链不能引用自身或重复节点" }
    fun checkPath(path:List<Long>,hasUpstream:Boolean) { path.forEachIndexed { index,id->
        require((index==0 && !hasUpstream) || !JSONObject(data.nodes.first { it.id==id }.outbound).has("detour")) { "代理链与节点原有 detour 冲突" }
    } }
    fronts.forEach { checkPath(it,false) };checkPath(body,frontIds.isNotEmpty());landings.forEach { checkPath(it,true) }

}

fun validateChainSettings(data:AppData) {
    validateChainTargetSyntax(data)
    val nodesById=data.nodes.associateBy { it.id }
    data.nodes.forEach { validateChainNodeSettings(data,it,nodesById) }
}

fun chainUnavailableReason(data:AppData,node:Node):String? {
    val groups=data.groups.associateBy { it.id };val nodes=data.nodes.associateBy { it.id }
    groups[node.groupId]?.let { derivedGroupUnavailableReason(data,it) }?.let { return it }
    val visited=mutableSetOf<Long>()
    fun raw(id:Long):String? {
        val source=nodes[id] ?: return "链路引用的节点已删除"
        if(!visited.add(id))return null
        val bean=JSONObject(source.outbound)
        if(bean.optString("type")=="chain") {
            val members=bean.getJSONArray("node_ids")
            for(i in 0 until members.length())raw(members.getLong(i))?.let { return it }
        }
        val detour=bean.optString("detour")
        if(detour.isNotBlank() && detour!="direct") {
            val upstream=data.nodes.firstOrNull { it.groupId==source.groupId && JSONObject(it.metadata).optString("sourceTag")==detour }
                ?: return "原始上游节点已失效"
            raw(upstream.id)?.let { return it }
        }
        return null
    }
    raw(node.id)?.let { return it }
    val targets=effectiveChainTargets(data,node)
    listOf(targets.front,targets.landing).forEach { target->
        when {
            target.startsWith("node:")->raw(target.substringAfter(':').toLong())?.let { return it }
            target.startsWith("group:")-> {
                val id=target.substringAfter(':').toLong()
                if(id !in groups)return "链路引用的分组已删除"
                val members=data.nodes.filter { it.groupId==id }
                if(members.isEmpty())return "链路引用的分组没有节点"
                members.forEach { raw(it.id)?.let { reason->return reason } }
            }
        }
    }
    return null
}

fun chainPathLabels(data:AppData,nodeId:Long):List<String> {
    val node=data.nodes.firstOrNull { it.id==nodeId } ?: return listOf("节点已失效")
    fun label(target:String):List<String> = when {
        target=="none"->emptyList()
        target.startsWith("group:")->listOf((data.groups.firstOrNull { it.id==target.substringAfter(':').toLongOrNull() }?.name ?: "分组已失效")+" · 自动")
        else->runCatching { rawChainIds(data,target.substringAfter(':').toLong()).map { id->data.nodes.first { it.id==id }.name } }.getOrDefault(listOf("节点已失效"))
    }
    val t=effectiveChainTargets(data,node)
    return label(t.front)+runCatching { rawChainIds(data,node.id).map { id->data.nodes.first { it.id==id }.name } }.getOrDefault(listOf("${node.name} · 链路失效"))+label(t.landing)
}
