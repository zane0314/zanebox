package com.zane.zanebox.subscription

import com.zane.zanebox.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** One transformation pipeline for UI and durable background updates. */
object SubscriptionUpdater {
    suspend fun prepare(nodes:List<ParsedNode>,options:SubscriptionOptions,ipv6:Boolean,familyStrategy:String="",lookup:suspend(String)->List<String>):List<ParsedNode> = coroutineScope {
        val strategy=if(!ipv6) "ipv4_only" else familyStrategy
        require(strategy in listOf("","prefer_ipv4","prefer_ipv6","ipv4_only","ipv6_only")) { "节点解析地址族策略无效" }
        val cache=java.util.concurrent.ConcurrentHashMap<String,List<String>>()
        options.select(nodes).chunked(4).flatMap { batch -> batch.map { parsed -> async {
            currentCoroutineContext().ensureActive()
            val out=JSONObject(parsed.outbound);val meta=JSONObject(parsed.metadata)
            meta.put("subscriptionIdentity",NodeIdentity.key(parsed.outbound))
            val targets=mutableListOf<Pair<JSONObject,String>>()
            if(out.has("server")) targets.add(out to "server")
            out.optJSONArray("peers")?.let { peers -> for(i in 0 until peers.length()) targets.add(peers.getJSONObject(i) to "address") }
            if(options.forceResolve) targets.forEach { (target,key) ->
                val host=target.optString(key)
                if(host.isNotBlank() && !literal(host)) try {
                    val addresses=cache[host] ?: lookup(host).also { cache[host]=it }
                    currentCoroutineContext().ensureActive()
                    val ipv4=addresses.firstOrNull { !it.contains(':') };val ipv6Address=addresses.firstOrNull { it.contains(':') }
                    val address=when(strategy) { "ipv4_only" -> ipv4;"ipv6_only" -> ipv6Address;"prefer_ipv6" -> ipv6Address ?: ipv4;else -> ipv4 ?: ipv6Address }
                    if(address!=null) {
                        if(target===out) { out.optJSONObject("tls")?.takeIf { it.optBoolean("enabled") && it.optString("server_name").isBlank() }?.put("server_name",host);meta.put("subscriptionOriginalServer",host) }
                        target.put(key,address)
                    } else meta.put("subscriptionResolveError","没有允许地址族的解析结果，保留域名")
                } catch(e:CancellationException) { throw e } catch(_:Exception) { meta.put("subscriptionResolveError","节点域名解析失败，保留域名") }
            }
            parsed.copy(outbound=out.toString(),metadata=meta.toString())
        } }.awaitAll() }
    }
    private fun literal(host:String):Boolean = host.contains(':') || host.split('.').let { parts -> parts.size==4 && parts.all { it.toIntOrNull() in 0..255 } }
    private fun journal(options:String,block:(JSONObject)->Unit):String = JSONObject(options).apply { val state=optJSONObject("subscriptionRuntime") ?: JSONObject();block(state);put("subscriptionRuntime",state) }.toString()
    fun begin(current:AppData,expected:Group,request:String,now:Long):AppData {
        val group=current.groups.find { it.id==expected.id } ?: error("订阅组已删除")
        require(group.subscriptionUrl==expected.subscriptionUrl && SubscriptionOptions.signature(group.options)==SubscriptionOptions.signature(expected.options)) { "订阅组 URL 或选项已变更，请重新更新" }
        return current.copy(groups=current.groups.map { if(it.id!=group.id) it else it.copy(options=journal(it.options) { state -> state.put("request",request).put("state","updating").put("lastAttempt",now);state.remove("error");state.remove("nextAttempt") }) })
    }
    fun apply(current:AppData,baseline:Group,parsed:List<ParsedNode>,userInfo:String,now:Long,nextId:()->Long):AppData {
        require(parsed.isNotEmpty()) { "订阅没有有效节点，已保留原节点" }
        val group=current.groups.find { it.id==baseline.id } ?: error("订阅组已删除")
        val request=JSONObject(baseline.options).getJSONObject("subscriptionRuntime").getString("request")
        require(group.subscriptionUrl==baseline.subscriptionUrl && group.updatedAt==baseline.updatedAt && SubscriptionOptions.signature(group.options)==SubscriptionOptions.signature(baseline.options) && JSONObject(group.options).optJSONObject("subscriptionRuntime")?.optString("request")==request) { "订阅响应已过期，已保留当前数据" }
        val previous=current.nodes.filter { it.groupId==group.id }
        val remaining=previous.associateBy { it.id }.toMutableMap()
        fun identity(outbound:String,metadata:String)=JSONObject(metadata).optString("subscriptionIdentity").ifBlank { NodeIdentity.key(outbound) }
        val identities=previous.groupBy { identity(it.outbound,it.metadata) }.mapValues { java.util.ArrayDeque(it.value) }
        val links=previous.filter { it.shareLink.isNotBlank() }.groupBy { it.shareLink }.mapValues { java.util.ArrayDeque(it.value) }
        fun take(bucket:java.util.ArrayDeque<Node>?):Node? { while(bucket!=null && bucket.isNotEmpty()) { val n=bucket.removeFirst();if(remaining.remove(n.id)!=null) return n };return null }
        var nextOrder=(previous.maxOfOrNull { it.order } ?: -1)+1
        val fresh=parsed.map { incoming ->
            val match=take(identities[identity(incoming.outbound,incoming.metadata)]) ?: take(links[incoming.shareLink])
            val meta=JSONObject(match?.metadata ?: "{}");val addition=JSONObject(incoming.metadata);addition.keys().forEach { key -> meta.put(key,addition.get(key)) }
            (match ?: Node(nextId(),group.id,incoming.name,incoming.outbound,order=nextOrder++)).copy(name=incoming.name,outbound=incoming.outbound,shareLink=incoming.shareLink,metadata=meta.toString())
        }
        val removed=remaining.keys.toMutableSet()
        var all=current.nodes.filter { it.groupId!=group.id }+fresh
        // A chain losing a hop cannot silently become a shorter, differently routed chain.
        while(true) {
            val missing=all.filter { n -> val o=JSONObject(n.outbound);o.optString("type")=="chain" && o.getJSONArray("node_ids").let { ids -> (0 until ids.length()).any { ids.getLong(it) in removed } } }.map { it.id }
            if(missing.isEmpty()) break;removed.addAll(missing);all=all.filter { it.id !in removed }
        }
        val settings=current.settings.filterKeys { key -> removed.none { key=="nodeRegion.$it" } }.mapValues { (key,value) -> if(key.startsWith("smart.") && key.endsWith(".target") && value.startsWith("node:") && value.substringAfter(':').toLongOrNull() in removed) "off" else value }.toMutableMap()
        val enabledGroups=current.groups.filter { it.enabled }.sortedBy { it.order }
        val enabledIds=enabledGroups.map { it.id }.toSet()
        val selected=all.find { it.id==current.selectedNodeId && it.groupId in enabledIds } ?: enabledGroups.firstNotNullOfOrNull { candidate -> all.filter { it.groupId==candidate.id }.minByOrNull { it.order } }
        settings["selectedNodeId"]=(selected?.id ?: 0L).toString();settings["selectedGroupId"]=(selected?.groupId ?: 0L).toString()
        val disabledTargets=current.groups.filterNot { it.enabled }.map { "group:${it.id}" }.toSet()+all.filter { it.groupId !in enabledIds }.map { "node:${it.id}" }
        settings.entries.forEach { entry -> if(entry.key.startsWith("smart.") && entry.key.endsWith(".target") && entry.value in disabledTargets) entry.setValue("off") }
        return current.copy(nodes=all,settings=settings,
            groups=current.groups.map { g -> val cleaned=g.copy(frontProxy=if(g.frontProxy in removed)0 else g.frontProxy,landingProxy=if(g.landingProxy in removed)0 else g.landingProxy);if(g.id!=group.id) cleaned else cleaned.copy(updatedAt=now,userInfo=userInfo,options=JSONObject(journal(cleaned.options) { state -> state.put("state","success").put("lastSuccess",now);state.remove("error");state.remove("nextAttempt") }).put("subscriptionLastUpdated",now/1000).toString()) },
            rules=current.rules.map { if(it.outbound.startsWith("node:") && it.outbound.substringAfter(':').toLongOrNull() in removed) it.copy(outbound="proxy") else it },
            merges=current.merges.map { it.copy(nodeIds=it.nodeIds.filter { id -> id !in removed },selectedId=if(it.selectedId in removed)0 else it.selectedId) })
    }
    fun record(current:AppData,groupId:Long,state:String,now:Long,error:String="",nextAttempt:Long=0,request:String?=null):AppData = current.copy(groups=current.groups.map { group ->
        if(group.id!=groupId || (request!=null && JSONObject(group.options).optJSONObject("subscriptionRuntime")?.optString("request")!=request)) group
        else group.copy(options=journal(group.options) { value -> value.put("state",state).put("lastAttempt",now);if(error.isBlank()) value.remove("error") else value.put("error",error.take(256));if(nextAttempt>0) value.put("nextAttempt",nextAttempt) else value.remove("nextAttempt") })
    })
}
