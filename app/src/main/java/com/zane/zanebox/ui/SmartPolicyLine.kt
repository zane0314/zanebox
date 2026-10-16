package com.zane.zanebox.ui

import com.zane.zanebox.config.ConfigBuilder
import com.zane.zanebox.config.normalizeSmartTarget
import com.zane.zanebox.data.AppData

private fun isGroupTarget(value:String):Boolean = normalizeSmartTarget(value).let { it=="auto" || it.startsWith("group:") || it.startsWith("merge:") }

/**
 * The node of a group target with the lowest latency among its latest successful tests, the same one the group's
 * latency value comes from. It is not the user's selection or the outbound in use. Null for single nodes, direct/off/block,
 * the home proxy, while a member is being tested, and when there is no successful result.
 */
internal fun smartLowestNode(data:AppData,value:String,testing:Set<Long>):String? {
    if(!isGroupTarget(value))return null
    val members=ConfigBuilder.smartTargetNodeIds(data,value)
    if(members.any { it in testing })return null
    val byId=data.nodes.associateBy { it.id }
    // minByOrNull keeps the first of equal latencies, so ties follow the member order.
    return members.mapNotNull { byId[it] }.filter { it.status!=1 && it.ping>0 }.minByOrNull { it.ping }?.name
}

/** The bare value: a group's lowest successful latency, or a status word (测试中/未测试/失败); no prefix of any kind. */
internal fun smartLatencyLabel(data:AppData,value:String,testing:Set<Long>):String? = targetLatencyLabel(data,value,testing)
