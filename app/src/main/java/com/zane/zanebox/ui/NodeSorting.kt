package com.zane.zanebox.ui

import com.zane.zanebox.data.AppData
import com.zane.zanebox.data.Group
import com.zane.zanebox.data.Node
import org.json.JSONObject
import java.text.Collator
import java.util.Locale

internal enum class NodeSortMode { DEFAULT, LATENCY, NAME }

/** Reorder visible slots without moving hidden records or accepting stale/duplicate IDs. */
internal fun reorderVisibleIds(current:List<Long>,requested:List<Long>):List<Long> {
    require(requested.distinct().size==requested.size && current.toSet().containsAll(requested)) { "排序记录已变化，请重试" }
    val selected=requested.toSet();val next=requested.iterator()
    return current.map{if(it in selected)next.next() else it}
}

internal fun AppData.reorderNodes(requested:List<Long>):AppData {
    require(requested.distinct().size==requested.size && nodes.map{it.id}.toSet().containsAll(requested)) { "节点已变化，请重试" }
    val orders=mutableMapOf<Long,Int>();var nextSettings=settings
    val byGroup=nodes.groupBy{it.groupId}
    groups.forEach { group ->
        val current=byGroup[group.id].orEmpty().sortedWith(nodeComparator(this,group)).map{it.id}
        val currentIds=current.toSet()
        val visible=requested.filter{it in currentIds}
        val reordered=reorderVisibleIds(current,visible)
        if(reordered!=current) {
            reordered.forEachIndexed{i,id->orders[id]=i}
            nextSettings=nextSettings+("sort_group_${group.id}" to "false")
        }
    }
    return copy(nodes=nodes.map{orders[it.id]?.let{order->it.copy(order=order)} ?:it},settings=nextSettings)
}

internal fun AppData.reorderGroups(requested:List<Long>):AppData {
    val orders=reorderVisibleIds(groups.sortedBy{it.order}.map{it.id},requested).withIndex().associate{it.value to it.index}
    return copy(groups=groups.map{it.copy(order=orders.getValue(it.id))})
}

internal fun AppData.reorderRules(front:Boolean,requested:List<Long>):AppData {
    val orders=reorderVisibleIds(rules.filter{it.prioritize==front}.sortedBy{it.order}.map{it.id},requested).withIndex().associate{it.value to it.index}
    return copy(rules=rules.map{orders[it.id]?.let{order->it.copy(order=order)} ?:it})
}

internal fun nodeTestLabel(node:Node):String=when { node.status==1 || node.ping == -2->"失败";node.ping>=0->"${node.ping} ms";else->"未测试" }
/** Lowest successful latency of the target's candidates; null for targets without nodes (off/direct/block). */
internal fun targetLatencyLabel(data:com.zane.zanebox.data.AppData,target:String,testing:Set<Long> = emptySet()):String? {
    val members=com.zane.zanebox.config.ConfigBuilder.smartTargetNodeIds(data,target).toSet()
    if(members.isEmpty())return null
    if(members.any { it in testing })return "测试中"
    val nodes=data.nodes.filter { it.id in members }
    nodes.filter { it.ping>0 && it.status!=1 }.minOfOrNull { it.ping }?.let { return "$it ms" }
    return if(nodes.any { it.status==1 || it.ping==-2 })"失败" else "未测试"
}

internal fun nodeSortMode(data:AppData,group:Group):NodeSortMode {
    data.settings["sort_group_${group.id}"]?.toBooleanStrictOrNull()?.let { return if(it)NodeSortMode.LATENCY else NodeSortMode.DEFAULT }
    if(data.settings["sort_mode_group_${group.id}"]=="name")return NodeSortMode.NAME
    data.settings["legacy.preference.anybox_nodes.sort_group_${group.id}"]?.let { raw ->
        val old=runCatching { JSONObject(raw) }.getOrNull()
        if(old?.optString("type")=="boolean" && old.opt("value") is Boolean)
            return if(old.getBoolean("value"))NodeSortMode.LATENCY else NodeSortMode.DEFAULT
    }
    return when(runCatching { JSONObject(group.options).optInt("nodeSortOrder",0) }.getOrDefault(0)) {
        1 -> NodeSortMode.NAME
        2 -> NodeSortMode.LATENCY
        else -> NodeSortMode.DEFAULT
    }
}

internal fun nodeComparator(latency:Boolean):Comparator<Node> = nodeComparator(if(latency)NodeSortMode.LATENCY else NodeSortMode.DEFAULT)
internal fun nodeComparator(data:AppData,group:Group):Comparator<Node> {
    val mode=nodeSortMode(data,group)
    if(mode!=NodeSortMode.LATENCY)return nodeComparator(mode)
    fun ping(node:Node)=data.settings["sort_ping_${node.id}"]?.toIntOrNull() ?: node.ping
    return compareBy<Node> { if(ping(it)>0)0 else 1 }.thenBy { if(ping(it)>0)ping(it) else 0 }.thenBy { it.order }
}
internal fun nodeComparator(mode:NodeSortMode):Comparator<Node> = when(mode) {
    NodeSortMode.DEFAULT -> compareBy { it.order }
    NodeSortMode.LATENCY -> compareBy<Node> { if(it.ping>0)0 else 1 }.thenBy { if(it.ping>0)it.ping else 0 }.thenBy { it.order }
    NodeSortMode.NAME -> Comparator<Node> { a,b -> naturalNodeName(a.name,b.name) }.thenBy { it.order }
}

private val nameCollator=ThreadLocal.withInitial { Collator.getInstance(Locale.CHINA).apply { strength=Collator.SECONDARY } }

/** Port of DashboardGroupPolicyKt NATURAL_NAME_ORDER and compareDigitRuns. */
internal fun naturalNodeName(a:String,b:String):Int {
    var i=0;var j=0
    while(i<a.length && j<b.length) {
        val digitA=a[i].isDigit();val digitB=b[j].isDigit()
        if(digitA!=digitB)return nameCollator.get().compare(a.substring(i),b.substring(j))
        var endA=i;var endB=j
        while(endA<a.length && a[endA].isDigit()==digitA)endA++
        while(endB<b.length && b[endB].isDigit()==digitB)endB++
        val runA=a.substring(i,endA);val runB=b.substring(j,endB)
        val compared=if(digitA) {
            val cleanA=runA.trimStart('0');val cleanB=runB.trimStart('0')
            val length=cleanA.length.compareTo(cleanB.length)
            if(length!=0)length else cleanA.compareTo(cleanB).takeIf { it!=0 } ?: runA.length.compareTo(runB.length)
        } else nameCollator.get().compare(runA,runB)
        if(compared!=0)return compared
        i=endA;j=endB
    }
    return (a.length-i)-(b.length-j)
}
