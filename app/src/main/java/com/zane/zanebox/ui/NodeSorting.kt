package com.zane.zanebox.ui

import com.zane.zanebox.data.AppData
import com.zane.zanebox.data.Group
import com.zane.zanebox.data.Node
import org.json.JSONObject
import java.text.Collator
import java.util.Locale

internal enum class NodeSortMode { DEFAULT, LATENCY, NAME }

internal fun nodeTestLabel(node:Node):String=when { node.status==1 || node.ping == -2->"失败";node.ping>=0->"${node.ping} ms";else->"未测试" }

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
