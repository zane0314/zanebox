package com.zane.zanebox.runtime

import org.json.JSONObject

/** Each selector owns its candidates; landing tags already contain the selected front and body detour. */
internal data class ChainProbe(val tag:String,val candidates:List<String>,val unavailable:String)
internal data class ChainProbePlan(val nodeId:Long,val front:ChainProbe?,val landing:ChainProbe?) {
    companion object {
        fun read(config:String):List<ChainProbePlan> {
            val a=JSONObject(config).optJSONArray("outbounds") ?: return emptyList()
            val groups=(0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("type")=="selector" }.associateBy { it.optString("tag") }
            fun probe(tag:String):ChainProbe? {
                val group=groups[tag] ?: return null
                val members=group.optJSONArray("outbounds") ?: return null
                val candidates=(0 until members.length()).map { members.getString(it) }.filter { !it.endsWith("-unavailable") }
                if(candidates.isEmpty())return null
                return ChainProbe(tag,candidates,group.optString("default"))
            }
            val ids=groups.keys.mapNotNull { Regex("chain-([0-9]+)-front").matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }.toMutableSet()
            groups.forEach { (tag,g)-> if(tag.matches(Regex("node-[0-9]+")) && g.optJSONArray("outbounds")?.toString()?.contains("chain-${tag.removePrefix("node-")}-landing-")==true)ids.add(tag.removePrefix("node-").toLong()) }
            return ids.sorted().map { id->ChainProbePlan(id,probe("chain-$id-front"),probe("node-$id")) }
        }
    }
}

/** A round never reuses landing delays from before the front selection, including failed rounds. */
internal inline fun chooseChainProbe(probe:ChainProbe,test:(String)->Int,select:(String,String)->Boolean,active:()->Boolean):String {
    var winner=probe.unavailable;var best=Int.MAX_VALUE
    for(candidate in probe.candidates) {
        check(active()) { "代理链测速已过期" }
        val delay=test(candidate)
        check(active()) { "代理链测速已过期" }
        if(delay>0 && delay<best) { best=delay;winner=candidate }
    }
    check(active()) { "代理链测速已过期" }
    check(select(probe.tag,winner)) { "代理链选择无法应用：${probe.tag}" }
    return winner
}

/** Tags differ per subject even when a front candidate's raw route is identical. */
internal fun chainProbeKeys(config:String):Map<String,String> {
    val root=JSONObject(config);val a=root.getJSONArray("outbounds")
    val nodes=(0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.getString("tag") }
    fun path(key:String,seen:Set<String>):String {
        if(key in seen)return key
        val value=nodes[key]?.let {JSONObject(it.toString())} ?: return key
        value.remove("tag")
        value.optString("detour").takeIf {it.isNotBlank()}?.let { value.put("detour",path(it,seen+key)) }
        return value.toString()
    }
    val dns=root.optJSONObject("dns").toString()
    return ChainProbePlan.read(config).flatMap {it.front?.candidates.orEmpty()}.distinct().associateWith {dns+path(it,emptySet())}
}

internal data class ChainParentProbe(val tag:String,val members:List<String>,val dependencies:Set<Long>)
internal fun chainParentProbes(config:String):List<ChainParentProbe> {
    val root=JSONObject(config);val a=root.getJSONArray("outbounds")
    val nodes=(0 until a.length()).map {a.getJSONObject(it)}.associateBy {it.getString("tag")}
    val dynamic=ChainProbePlan.read(config).map {it.nodeId}.toSet()
    fun dependencies(tag:String,seen:Set<String>):Set<Long> {
        if(tag in seen)return emptySet()
        tag.removePrefix("node-").toLongOrNull()?.takeIf {tag.startsWith("node-") && it in dynamic}?.let {return setOf(it)}
        val members=nodes[tag]?.optJSONArray("outbounds") ?: return emptySet()
        return (0 until members.length()).flatMap {dependencies(members.getString(it),seen+tag)}.toSet()
    }
    return nodes.values.filter {it.optString("type")=="urltest" && it.optBoolean("managed_test")}.map {group->
        val members=group.getJSONArray("outbounds").let {m->(0 until m.length()).map {m.getString(it)}}
        ChainParentProbe(group.getString("tag"),members,members.flatMap {dependencies(it,emptySet())}.toSet())
    }
}

internal fun routedChainNodes(config:String):Set<Long> {
    val root=JSONObject(config);val a=root.getJSONArray("outbounds")
    val nodes=(0 until a.length()).map {a.getJSONObject(it)}.associateBy {it.getString("tag")}
    val seen=mutableSetOf<String>();val ids=mutableSetOf<Long>()
    fun visit(tag:String) {
        if(!seen.add(tag))return
        tag.takeIf {it.startsWith("node-") }?.removePrefix("node-")?.toLongOrNull()?.let {ids.add(it)}
        val node=nodes[tag] ?: return
        if(tag=="proxy")node.optString("default").takeIf {it.isNotBlank()}?.let(::visit)
        else node.optJSONArray("outbounds")?.let {m->for(i in 0 until m.length())visit(m.getString(i))}
    }
    SamplingPolicy.trackedTags(root).forEach(::visit)
    return ids
}
