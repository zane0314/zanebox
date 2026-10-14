package com.zane.zanebox.runtime

import com.zane.zanebox.config.ConfigBuilder
import com.zane.zanebox.config.normalizeSmartTarget
import com.zane.zanebox.config.smartTarget
import com.zane.zanebox.data.AppData
import org.json.JSONObject

/** The sing-box group a routing target resolves to; null means the target only reports latency. */
internal data class TargetGroup(val tag:String,val urlTest:Boolean,val mergeId:Long=0)

internal const val GROUP_SELECTION_PREFIX="groupSelection."
/** The winner's latency when it was chosen; only used to restore that choice after later rounds overwrite node pings. */
internal const val GROUP_SELECTION_DELAY_PREFIX="groupSelectionDelay."
/** Restore latency for a saved URLTest winner: the delay saved with the choice, else the node's current ping. */
internal fun savedChoiceDelay(data:AppData,tag:String,nodePing:Int):Int =
    data.setting(GROUP_SELECTION_DELAY_PREFIX+tag,"").toIntOrNull()?.takeIf { it in 1..65535 } ?: nodePing

/**
 * Selector groups whose explicit target-test choice must be re-applied after start: sing-box restores a cached
 * selector choice before the config default, so a newer offline choice would otherwise lose to an old cache entry.
 * Only live group-/merge- selectors qualify; a merge marker is valid only while it still equals merges.selectedId,
 * so a later manual choice makes it stale. Returns group tag to node tag.
 */
internal fun restorableSelectorChoices(data:AppData,config:String):Map<String,String> {
    val outbounds=JSONObject(config).optJSONArray("outbounds") ?: return emptyMap()
    val result=linkedMapOf<String,String>()
    for(i in 0 until outbounds.length()) {
        val item=outbounds.getJSONObject(i);val tag=item.optString("tag")
        if(item.optString("type")!="selector" || !(tag.startsWith("group-") || tag.startsWith("merge-")))continue
        val marker=data.setting(GROUP_SELECTION_PREFIX+tag,"").takeIf { it.isNotBlank() } ?: continue
        val members=item.optJSONArray("outbounds")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        if(marker !in members)continue
        if(tag.startsWith("merge-")) {
            val merge=data.merges.firstOrNull { "merge-${it.id}"==tag }
            if(merge==null || merge.mode!="selector" || "node-${merge.selectedId}"!=marker)continue
        }
        result[tag]=marker
    }
    return result
}

/**
 * One latency batch for a routing target. Members, group kind and node outbounds are frozen when the
 * batch starts, so a result is applied only if the same target still means the same thing.
 */
internal class TargetPlan private constructor(
    val target:String,val serviceKey:String,val members:List<Long>,val group:TargetGroup?,private val outbounds:Map<Long,String>,private val inputs:List<Any?>
) {
    /** The best member of this round, lowest latency first and member order on ties. Null when nothing succeeded. */
    fun winner(latest:AppData,pings:Map<Long,Int>):Pair<Long,Int>? {
        val current=latest.nodes.associate { it.id to it.outbound }
        return members.filter { id -> (pings[id] ?: 0)>0 && current[id]==outbounds[id] }
            .minByOrNull { pings.getValue(it) }?.let { it to pings.getValue(it) }
    }
    /** Failed or edited members are sent as 0 so the native group drops their stale history. */
    fun nativeResults(latest:AppData,pings:Map<Long,Int>):Map<String,Int> {
        val current=latest.nodes.associate { it.id to it.outbound }
        return members.associate { id -> "node-$id" to (pings[id]?.takeIf { it>0 && current[id]==outbounds[id] } ?: 0) }
    }
    /** The frozen batch still describes the same members, their node links and the same group; anything else discards the round. */
    fun stillValid(latest:AppData):Boolean = create(latest,target,serviceKey).let { it!=null && it.members.toSet()==members.toSet() && it.group==group && it.inputs==inputs }

    companion object {
        /** Null when [serviceKey] no longer points at [target], so a changed policy never receives an old test. */
        fun create(data:AppData,target:String,serviceKey:String):TargetPlan? {
            if(serviceKey.isNotBlank() && smartTarget(data,serviceKey)!=normalizeSmartTarget(target))return null
            val members=ConfigBuilder.smartTargetNodeIds(data,target)
            val byId=data.nodes.associate { it.id to it.outbound }
            return TargetPlan(target,serviceKey,members,resolveGroup(data,target,serviceKey),members.associateWith { byId.getValue(it) },linkInputs(data,members))
        }
        /**
         * Everything ConfigBuilder pulls into a member's test config: nested chain parts, sourceTag detours and the
         * group front/landing nodes (each followed the same way). Only those nodes and groups are frozen, so unrelated
         * edits never cancel the round.
         */
        private fun linkInputs(data:AppData,members:List<Long>):List<Any?> {
            val nodes=data.nodes.associateBy { it.id };val groups=data.groups.associateBy { it.id }
            fun bean(text:String)=runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            // Same precedence as ConfigBuilder: the first node in list order owns a (group, sourceTag).
            val sources=data.nodes.asReversed().mapNotNull { n -> bean(n.metadata).optString("sourceTag").takeIf { it.isNotBlank() }?.let { (n.groupId to it) to n } }.toMap()
            val seen=sortedSetOf<Long>();val usedGroups=sortedSetOf<Long>();val lookups=mutableListOf<String>()
            val queue=java.util.ArrayDeque<Long>(members)
            while(queue.isNotEmpty()) {
                val id=queue.removeFirst()
                if(!seen.add(id))continue
                val node=nodes[id] ?: continue
                val outbound=bean(node.outbound)
                if(outbound.optString("type")=="chain")outbound.optJSONArray("node_ids")?.let { ids -> for(i in 0 until ids.length())queue.add(ids.optLong(i)) }
                if(outbound.has("detour")) {
                    val tag=outbound.optString("detour");val source=sources[node.groupId to tag]
                    lookups.add("${node.groupId}/$tag=${source?.id}");source?.let { queue.add(it.id) }
                }
                groups[node.groupId]?.let { group ->
                    usedGroups.add(group.id)
                    listOf(group.frontProxy,group.landingProxy).filter { it>0 }.forEach { queue.add(it) }
                }
            }
            return listOf(seen.map { id -> nodes[id]?.let { listOf(it.id,it.groupId,it.outbound,it.metadata) } },
                usedGroups.map { id -> groups[id]?.let { listOf(it.id,it.enabled,it.frontProxy,it.landingProxy,it.options) } },
                lookups.sorted())
        }
        fun resolveGroup(data:AppData,target:String,serviceKey:String):TargetGroup? {
            val choice=normalizeSmartTarget(target)
            return when {
                choice=="auto" -> serviceKey.takeIf { it.isNotBlank() }?.let { TargetGroup("smart-$it",true) }
                choice.startsWith("group:") -> {
                    val group=data.groups.firstOrNull { it.enabled && it.id==choice.substringAfter(':').toLongOrNull() } ?: return null
                    val options=JSONObject(group.options)
                    // A non-selectable group only exposes its default member, so there is nothing to choose.
                    if(options.optBoolean("groupIsSelector",options.optBoolean("isSelector",true))) TargetGroup("group-${group.id}",false) else null
                }
                choice.startsWith("merge:") -> data.merges.firstOrNull { it.id==choice.substringAfter(':').toLongOrNull() }
                    ?.let { TargetGroup("merge-${it.id}",it.mode=="urltest",it.id) }
                else -> null
            }
        }
    }
}
