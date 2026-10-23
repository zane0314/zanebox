package com.zane.zanebox.core

import libcore.BoxInstance
import libcore.Libcore

/** Only this class owns the main native box; test boxes are explicitly separate. */
class SingBoxEngine(private val platform:NativePlatform) {
    private var main:BoxInstance?=null
    var trackedTags:List<String> = emptyList();private set
    var configuredProxyDefault:String="";private set
    private class TestBox(val box:BoxInstance) {
        private var closed=false
        @Synchronized fun close() {if(!closed) {closed=true;box.close()}}
    }
    private val tests=java.util.Collections.synchronizedSet(mutableSetOf<TestBox>())
    fun start(config:String) {
        check(main==null)
        val next=Libcore.newSingBoxInstance(config,platform)
        try {
            val root=org.json.JSONObject(config)
            trackedTags=com.zane.zanebox.runtime.SamplingPolicy.trackedTags(root)
            val outbounds=root.optJSONArray("outbounds") ?: org.json.JSONArray()
            configuredProxyDefault=(0 until outbounds.length()).map { outbounds.getJSONObject(it) }.firstOrNull { it.optString("tag")=="proxy" && it.optString("type")=="selector" }?.optString("default").orEmpty()
            next.setV2rayStats(trackedTags.joinToString("\n"));next.setAsMain();next.start()
            com.zane.zanebox.runtime.ChainProbePlan.read(config).flatMap {listOfNotNull(it.front,it.landing)}.forEach {
                check(next.selectGroupOutbound(it.tag,it.unavailable)) { "代理链初始阻断无法应用" }
            }
            if(configuredProxyDefault.isNotBlank())check(next.selectOutbound(configuredProxyDefault)) { "默认节点无法应用" }
            main=next
        }
        catch(failure:Throwable) { trackedTags=emptyList();configuredProxyDefault="";runCatching { next.close() };platform.closeTun();throw failure }
    }
    fun close():Map<String,Pair<Long,Long>> {
        val old=main ?: return emptyMap()
        try {
            old.close()
            return trackedTags.associateWith { tag -> old.queryStats(tag,"uplink").coerceAtLeast(0) to old.queryStats(tag,"downlink").coerceAtLeast(0) }
        } finally { main=null;trackedTags=emptyList();configuredProxyDefault="";platform.closeTun() }
    }
    fun query(tag:String,direction:String):Long = main?.queryStats(tag,direction) ?: 0
    fun select(tag:String):Boolean = main?.selectOutbound(tag) ?: false
    /** Switches one named selector group; the home "proxy" selector is never touched. */
    fun selectGroup(groupTag:String,tag:String):Boolean = main?.selectGroupOutbound(groupTag,tag) ?: false
    /** Applies this round's delays (0 = failed) to a live URLTest group and returns its new strict choice, or "". */
    fun applyURLTestResults(groupTag:String,results:Map<String,Int>):String =
        main?.applyURLTestResults(groupTag,results.entries.joinToString("\n") { "${it.key}\t${it.value}" }) ?: ""
    fun resetNetwork()=Libcore.resetAllConnections(true)
    fun sleep() { main?.sleep() }
    fun wake() { main?.wake() }
    fun validate(config:String) { val box=Libcore.newTestSingBoxInstance(config,platform);box.close() }
    fun resolvedTestConfig(config:String,url:String,timeout:Int,active:()->Boolean={true}):String {
        if(com.zane.zanebox.runtime.ChainProbePlan.read(config).isEmpty())return config
        var resolved=config
        test(config,url,timeout,prepared={ resolved=it },active=active)
        return resolved
    }
    fun test(config:String,url:String,timeout:Int,prepared:(String)->Unit={},frontDelays:MutableMap<String,Int>?=null,active:()->Boolean={true}):Int {
        if(!active())throw kotlinx.coroutines.CancellationException("测速已取消")
        val root=org.json.JSONObject(config)
        val plans=com.zane.zanebox.runtime.ChainProbePlan.read(config)
        val frontKeys=if(frontDelays!=null)com.zane.zanebox.runtime.chainProbeKeys(config)else emptyMap()
        val outbounds=root.optJSONArray("outbounds") ?: org.json.JSONArray()
        val proxy=(0 until outbounds.length()).map { outbounds.getJSONObject(it) }.firstOrNull { it.optString("tag")=="proxy" && it.optString("type")=="selector" }
        val original=proxy?.optString("default").orEmpty()
        if(plans.isNotEmpty()) {
            check(proxy!=null && original.isNotBlank()) { "代理链测速缺少默认选择器" }
            root.getJSONObject("route").put("final","proxy")
            val members=proxy.getJSONArray("outbounds")
            plans.flatMap { listOfNotNull(it.front,it.landing).flatMap { p->p.candidates } }.distinct().forEach { members.put(it) }
        }
        val owned=TestBox(Libcore.newTestSingBoxInstance(root.toString(),platform))
        val box=owned.box
        tests.add(owned)
        return try {
            if(!active())throw kotlinx.coroutines.CancellationException("测速已取消")
            box.start()
            if(!active())throw kotlinx.coroutines.CancellationException("测速已取消")
            for(plan in plans) {
                for(probe in listOfNotNull(plan.front,plan.landing)) {
                    val winner=com.zane.zanebox.runtime.chooseChainProbe(probe,{ tag->
                        check(box.selectOutbound(tag)) { "代理链测速候选无法选择" }
                        fun measure()=try { Libcore.urlTest(box,url,timeout) } catch(e:Exception) { if(!active())throw kotlinx.coroutines.CancellationException("测速已取消");-1 }
                        if(probe==plan.front && frontDelays!=null) synchronized(frontDelays) {
                            frontDelays.getOrPut(frontKeys.getValue(tag)) { measure() }
                        } else measure()
                    },{group,tag->box.selectGroupOutbound(group,tag)},active)
                    if(winner==probe.unavailable)throw IllegalStateException("代理链候选全部不可用")
                    (0 until outbounds.length()).map { outbounds.getJSONObject(it) }.first { it.optString("tag")==probe.tag }.put("default",winner)
                }
            }
            if(plans.isNotEmpty())check(box.selectOutbound(original)) { "代理链测速默认节点无法恢复" }
            prepared(root.toString())
            Libcore.urlTest(box,url,timeout)
        } finally { try {runCatching {owned.close()}} finally {tests.remove(owned)} }
    }
    fun cancelTests() { val snapshot=synchronized(tests) { tests.toList() };snapshot.forEach { runCatching { it.close() } } }
}
