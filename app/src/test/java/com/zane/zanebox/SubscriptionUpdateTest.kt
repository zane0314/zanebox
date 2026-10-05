package com.zane.zanebox

import com.zane.zanebox.data.*
import com.zane.zanebox.subscription.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse

class SubscriptionUpdateTest {
    private val outbound="{\"type\":\"trojan\",\"server\":\"edge.example\",\"server_port\":443,\"password\":\"pw\",\"tls\":{\"enabled\":true}}"
    private val group=Group(1,"订阅","https://subscription.example/list",options="{\"future\":\"preserve\"}")
    @Test fun filterFindModesDeduplicateAndRejectEmpty() = runBlocking {
        val nodes=listOf(ParsedNode("日本 A",outbound),ParsedNode("美国",outbound),ParsedNode("日本 B",outbound))
        assertEquals(2,SubscriptionOptions.parse("{\"filterMode\":1,\"filterRegex\":\"日本\"}").select(nodes).size)
        assertEquals("美国",SubscriptionOptions.parse("{\"filterMode\":2,\"filterRegex\":\"日本\"}").select(nodes).single().name)
        assertEquals("日本 A",SubscriptionOptions.parse("{\"deduplication\":true}").select(nodes).single().name)
        try { SubscriptionOptions.parse("{\"filterMode\":1,\"filterRegex\":\"absent\"}").select(nodes);fail("empty result") } catch(_:IllegalArgumentException) {}
        try { SubscriptionOptions.parse("{\"filterMode\":1,\"filterRegex\":\"[\"}");fail("bad regex") } catch(_:IllegalArgumentException) {}
    }
    @Test fun forcedResolvePreservesTlsAndCanonicalIdentity() = runBlocking {
        val prepared=SubscriptionUpdater.prepare(listOf(ParsedNode("节点",outbound)),SubscriptionOptions.parse("{\"forceResolve\":true}"),ipv6=false) { listOf("2001:db8::1","192.0.2.8") }.single()
        val result=JSONObject(prepared.outbound)
        assertEquals("192.0.2.8",result.getString("server"));assertEquals("edge.example",result.getJSONObject("tls").getString("server_name"))
        assertEquals(NodeIdentity.key(outbound),JSONObject(prepared.metadata).getString("subscriptionIdentity"))
    }
    @Test fun updatePreservesIdsTrafficReferencesAndUnknownOptions() {
        val kept=Node(10,1,"旧名字",outbound,ping=30,tx=44,metadata="{\"annotation\":\"keep\"}")
        val removed=Node(11,1,"删除",outbound.replace("edge.example","old.example"))
        val started=SubscriptionUpdater.begin(AppData(nodes=listOf(kept,removed),groups=listOf(group),rules=listOf(RouteRule(2,"引用",domains="a.example",outbound="node:11")),merges=listOf(MergeGroup(3,"合并",nodeIds=listOf(10,11),selectedId=11)),settings=mapOf("selectedNodeId" to "10","smart.ai.target" to "node:11")),group,"request-a",1000)
        val baseline=started.groups.single()
        val updated=SubscriptionUpdater.apply(started,baseline,listOf(ParsedNode("新名字",outbound)),"upload=1",2000) { 99 }
        assertEquals(10L,updated.nodes.single().id);assertEquals(44L,updated.nodes.single().tx);assertEquals(30,updated.nodes.single().ping)
        assertEquals("keep",JSONObject(updated.nodes.single().metadata).getString("annotation"));assertEquals("proxy",updated.rules.single().outbound);assertEquals("off",updated.setting("smart.ai.target"));assertEquals(listOf(10L),updated.merges.single().nodeIds)
        assertEquals("preserve",JSONObject(updated.groups.single().options).getString("future"));assertEquals(2000L,updated.groups.single().updatedAt)
        val newer=SubscriptionUpdater.begin(started,baseline,"request-b",1100)
        try { SubscriptionUpdater.apply(newer,baseline,listOf(ParsedNode("晚到",outbound)),"",2000) { 99 };fail("stale response") } catch(_:IllegalArgumentException) {}
    }
    @Test fun customUaAndRuntimeServerDnsAreConsumed() = runBlocking {
        val server=MockWebServer();server.enqueue(MockResponse().setBody("ok"));server.start()
        try { SubscriptionClient.fetch(server.url("/").toString(),"Zane-custom/2");assertEquals("Zane-custom/2",server.takeRequest().getHeader("User-Agent")) } finally { server.shutdown() }
        val g=group.copy(options="{\"serverDnsResolver\":\"https://dns.example/dns-query\",\"future\":true}")
        val root=JSONObject("{\"outbounds\":[{\"type\":\"trojan\",\"tag\":\"node-10\",\"server\":\"edge.example\"}],\"dns\":{\"servers\":[{\"type\":\"local\",\"tag\":\"dns-direct\"}]}}")
        SubscriptionDnsOptions.apply(root,AppData(nodes=listOf(Node(10,1,"节点",outbound)),groups=listOf(g)))
        assertEquals("subscription-dns-1",root.getJSONArray("outbounds").getJSONObject(0).getString("domain_resolver"))
        assertEquals("dns.example",root.getJSONObject("dns").getJSONArray("servers").getJSONObject(1).getString("server"))
        assertEquals("dns-direct",root.getJSONObject("dns").getJSONArray("servers").getJSONObject(1).getString("domain_resolver"))
    }
    @Test fun firstUpdateAndDisabledSelectionChooseEnabledOrderedDefault() {
        fun update(data:AppData):AppData { val started=SubscriptionUpdater.begin(data,group,"first",1000);return SubscriptionUpdater.apply(started,started.groups.first { it.id==1L },listOf(ParsedNode("首节点",outbound)),"",2000) { 99 } }
        val fresh=update(AppData(groups=listOf(group)))
        assertEquals(99L,fresh.selectedNodeId);assertEquals(1L,fresh.selectedGroupId)
        val other=Node(20,2,"其他",outbound,order=0)
        val valid=update(AppData(nodes=listOf(other),groups=listOf(group,Group(2,"其他",order=-1)),settings=mapOf("selectedNodeId" to "20")))
        assertEquals(20L,valid.selectedNodeId);assertEquals(2L,valid.selectedGroupId)
        val disabled=update(AppData(nodes=listOf(Node(10,1,"停用",outbound),other),groups=listOf(group.copy(enabled=false),Group(2,"启用",order=-1)),settings=mapOf("selectedNodeId" to "10")))
        assertEquals(20L,disabled.selectedNodeId);assertEquals(2L,disabled.selectedGroupId)
    }
    @Test fun forceResolveCoversWireguardPeersAndIpv6Preference() = runBlocking {
        val wg=ParsedNode("WG","{\"type\":\"wireguard\",\"address\":[\"10.0.0.2/32\"],\"private_key\":\"key\",\"peers\":[{\"address\":\"wg.example\",\"port\":51820,\"public_key\":\"pub\"}]}")
        val result=SubscriptionUpdater.prepare(listOf(wg),SubscriptionOptions(forceResolve=true),ipv6=true) { listOf("2001:db8::5","192.0.2.5") }.single()
        assertEquals("192.0.2.5",JSONObject(result.outbound).getJSONArray("peers").getJSONObject(0).getString("address"))
        val unchanged=SubscriptionUpdater.prepare(listOf(ParsedNode("IPv6",outbound)),SubscriptionOptions(forceResolve=true),ipv6=false) { listOf("2001:db8::5") }.single()
        assertEquals("edge.example",JSONObject(unchanged.outbound).getString("server"))
        assertTrue(JSONObject(unchanged.metadata).has("subscriptionResolveError"))
    }
    @Test fun durablePlanHonorsMinutesDueAndConnectedOnly() {
        val automatic=group.copy(updatedAt=1000,options="{\"autoUpdate\":true,\"autoUpdateDelay\":5,\"updateWhenConnectedOnly\":true}")
        assertEquals(301000L,SubscriptionPlan.nextAt(automatic));assertFalse(SubscriptionPlan.shouldUpdate(automatic,300000,true));assertFalse(SubscriptionPlan.shouldUpdate(automatic,301000,false));assertTrue(SubscriptionPlan.shouldUpdate(automatic,301000,true))
        assertTrue(SubscriptionPlan.shouldUpdate(automatic.copy(options="{\"autoUpdate\":true,\"autoUpdateDelay\":5}"),301000,false))
        assertFalse(SubscriptionPlan.shouldUpdate(automatic.copy(enabled=false),301000,true))
        assertEquals(1440,SubscriptionOptions.parse("{}").autoUpdateDelay)
    }
}
