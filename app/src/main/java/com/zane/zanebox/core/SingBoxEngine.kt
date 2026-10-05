package com.zane.zanebox.core

import libcore.BoxInstance
import libcore.Libcore

/** Only this class owns the main native box; test boxes are explicitly separate. */
class SingBoxEngine(private val platform:NativePlatform) {
    private var main:BoxInstance?=null
    var trackedTags:List<String> = emptyList();private set
    var configuredProxyDefault:String="";private set
    private val tests=java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.IdentityHashMap<BoxInstance,Boolean>()))
    fun start(config:String) {
        check(main==null)
        val next=Libcore.newSingBoxInstance(config,platform)
        try {
            val root=org.json.JSONObject(config)
            trackedTags=listOf("outbounds","endpoints").flatMap { key->val a=root.optJSONArray(key) ?: org.json.JSONArray();(0 until a.length()).map { a.getJSONObject(it).optString("tag") } }.distinct()
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
    fun resetNetwork()=Libcore.resetAllConnections(true)
    fun sleep() { main?.sleep() }
    fun wake() { main?.wake() }
    fun validate(config:String) { val box=Libcore.newTestSingBoxInstance(config,platform);box.close() }
    fun test(config:String,url:String,timeout:Int):Int {
        val box=Libcore.newTestSingBoxInstance(config,platform)
        tests.add(box)
        return try { box.start();Libcore.urlTest(box,url,timeout) } finally { tests.remove(box);runCatching { box.close() } }
    }
    fun cancelTests() { val snapshot=synchronized(tests) { tests.toList() };snapshot.forEach { runCatching { it.close() } } }
}
