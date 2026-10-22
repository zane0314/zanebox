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
    fun test(config:String,url:String,timeout:Int,active:()->Boolean={true}):Int {
        if(!active())throw kotlinx.coroutines.CancellationException("测速已取消")
        val owned=TestBox(Libcore.newTestSingBoxInstance(config,platform))
        val box=owned.box
        tests.add(owned)
        return try {
            if(!active())throw kotlinx.coroutines.CancellationException("测速已取消")
            box.start()
            if(!active())throw kotlinx.coroutines.CancellationException("测速已取消")
            Libcore.urlTest(box,url,timeout)
        } finally { try {runCatching {owned.close()}} finally {tests.remove(owned)} }
    }
    fun cancelTests() { val snapshot=synchronized(tests) { tests.toList() };snapshot.forEach { runCatching { it.close() } } }
}
