package com.zane.zanebox

import com.zane.zanebox.config.*
import com.zane.zanebox.data.*
import com.zane.zanebox.subscription.SubscriptionParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import com.zane.zanebox.subscription.SubscriptionClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin

class ConfigSubscriptionTest {
    private val node = Node(1, 1, "测试", """{"type":"vless","server":"example.com","server_port":443,"uuid":"a","future":{"x":1}}""")
    @Test fun completeOutboundAndIsolatedTests() {
        val data = AppData(nodes=listOf(node), groups=listOf(Group(1,"组")), settings=mapOf("clashApi" to "true", "shareEnabled" to "true"))
        val main=JSONObject(ConfigBuilder.build(data,runtimeSecret="runtime-only"))
        assertEquals(1,main.getJSONArray("outbounds").getJSONObject(0).getJSONObject("future").getInt("x"))
        assertEquals(3,main.getJSONArray("inbounds").length())
        assertFalse(ConfigBuilder.build(data,Purpose.EXPORT).contains("runtime-only"))
        val test=JSONObject(ConfigBuilder.build(data,Purpose.TEST,1))
        assertEquals(0,test.getJSONArray("inbounds").length()); assertFalse(test.has("experimental"))
    }
    @Test fun ruleTargetsAndDnsSets() {
        val data=AppData(nodes=listOf(node),groups=listOf(Group(1,"组")),merges=listOf(MergeGroup(2,"合并",groupIds=listOf(1))),rules=listOf(RouteRule(1,"国内",domains="geosite:cn,geoip:cn",outbound="direct"),RouteRule(2,"拒绝",domains="ads.example",outbound="block"),RouteRule(3,"合并",domains="site.example",outbound="merge:2")))
        val root=JSONObject(ConfigBuilder.build(data)); assertEquals("reject",root.getJSONObject("route").getJSONArray("rules").getJSONObject(3).getString("action"))
        val dns=root.getJSONObject("dns").getJSONArray("rules").getJSONObject(0).getJSONArray("rule_set")
        assertEquals(1,dns.length());assertEquals("geosite:cn",dns.getString(0))
    }
    @Test fun shareLinksBase64YamlAndCanonicalJson() {
        val link="vless://abc@example.com:443?security=reality&pbk=key&sid=aa&type=ws&host=cdn.example&path=%2Fws#%E6%B5%8B%E8%AF%95"
        val parsed=SubscriptionParser.parse(Base64.getEncoder().encodeToString(link.toByteArray())).single()
        assertEquals("测试",parsed.name);assertEquals("ws",JSONObject(parsed.outbound).getJSONObject("transport").getString("type"))
        val ss=SubscriptionParser.parse("ss://YWVzLTEyOC1nY206cHc@example.com:8388#SS").single(); assertEquals("pw",JSONObject(ss.outbound).getString("password"))
        val yaml=SubscriptionParser.parse("proxies:\n  - name: yaml\n    type: trojan\n    server: example.com\n    port: 443\n    password: pw\n").single();assertEquals("trojan",JSONObject(yaml.outbound).getString("type"))
        assertTrue(SubscriptionParser.parse(node.outbound).single().outbound.contains("future"))
    }
    @Test fun advancedSmartAndChainTopology() {
        val front=node.copy(id=2,groupId=2,name="日本 JP")
        val landing=node.copy(id=3,groupId=2)
        val data=AppData(nodes=listOf(node,front,landing),groups=listOf(Group(1,"链",frontProxy=2,landingProxy=3),Group(2,"普通")),rules=listOf(RouteRule(1,"优先",domains="first.example",advanced="{\"port\":[443]}",prioritize=true),RouteRule(2,"普通",domains="last.example")),settings=mapOf("smart.ai.target" to "region:jp","smartRules.ai" to "DOMAIN-SUFFIX,ai.example"))
        val root=JSONObject(ConfigBuilder.build(data));val outs=root.getJSONArray("outbounds")
        val hop=(0 until outs.length()).map { outs.getJSONObject(it) }.first { it.getString("tag")=="node-1-hop" }
        assertEquals("node-2",hop.getString("detour"))
        val rules=root.getJSONObject("route").getJSONArray("rules")
        assertEquals(443,rules.getJSONObject(2).getJSONArray("port").getInt(0));assertEquals("proxy",rules.getJSONObject(3).getString("outbound"))
        assertEquals("last.example",rules.getJSONObject(4).getJSONArray("domain_suffix").getString(0))
        try { ConfigBuilder.build(data.copy(groups=listOf(Group(1,"环",frontProxy=2),Group(2,"环",frontProxy=1))));fail("cycle must fail") } catch (_: IllegalArgumentException) {}
    }
    @Test fun boundedRedirectFetchAndCancellation() = runBlocking {
        val server=MockWebServer()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","/body"))
        server.enqueue(MockResponse().setBody("trojan://pw@example.com:443").setHeader("subscription-userinfo","upload=1; download=2"))
        server.enqueue(MockResponse().setHeader("Content-Length",9L*1024*1024))
        server.enqueue(MockResponse().setBody("x".repeat(100)).throttleBody(1,100,TimeUnit.MILLISECONDS))
        server.start()
        try {
            val base=server.url("/").toString().removeSuffix("/")
            val result=SubscriptionClient.fetch("$base/redirect");assertTrue(result.body.startsWith("trojan://"));assertTrue(result.userInfo.contains("download=2"))
            try { SubscriptionClient.fetch("$base/large");fail("size limit") } catch(_:IllegalArgumentException) {}
            val job=launch { SubscriptionClient.fetch("$base/slow") };delay(100);val time=System.nanoTime();job.cancelAndJoin();assertTrue((System.nanoTime()-time)/1000000 < 2000)
        } finally { server.shutdown() }
    }
    @Test fun serviceModesApplicationFiltersAndGlobals() {
        val base=AppData(nodes=listOf(node),groups=listOf(Group(1,"组")),rules=listOf(RouteRule(1,"用户",domains="custom.example")),settings=mapOf("smart.ai.target" to "auto","smartRules.ai" to "DOMAIN,ai.example"))
        listOf("global","direct").forEach { mode -> val root=JSONObject(ConfigBuilder.build(base.copy(settings=base.settings+mapOf("routeMode" to mode))));assertEquals(2,root.getJSONObject("route").getJSONArray("rules").length());assertEquals(if(mode=="direct") "direct" else "proxy",root.getJSONObject("route").getString("final")) }
        listOf("include","exclude").forEach { appMode ->
            val root=JSONObject(ConfigBuilder.build(base.copy(settings=mapOf("perAppEnabled" to "true","perAppMode" to appMode,"perAppPackages" to "com.zane.one\ncom.zane.two","bypassLan" to "true","strictRoute" to "false"))))
            val tun=root.getJSONArray("inbounds").getJSONObject(0);assertEquals(2,tun.getJSONArray("${appMode}_package").length());assertFalse(tun.getBoolean("strict_route"));assertTrue(tun.has("route_exclude_address"))
        }
        val proxy=JSONObject(ConfigBuilder.build(base.copy(settings=mapOf("serviceMode" to "proxy","disableMixedInbound" to "true","hosts" to "1.2.3.4 test.example"))))
        assertEquals(1,proxy.getJSONArray("inbounds").length());assertEquals("mixed",proxy.getJSONArray("inbounds").getJSONObject(0).getString("type"))
        assertEquals("dns-hosts",proxy.getJSONObject("dns").getJSONArray("rules").getJSONObject(0).getString("server"))
        val dnsServers=proxy.getJSONObject("dns").getJSONArray("servers")
        assertEquals("fakeip",(0 until dnsServers.length()).map{dnsServers.getJSONObject(it)}.single{it.optString("tag")=="dns-fake"}.getString("type"))
        val tls=node.copy(outbound="{\"type\":\"trojan\",\"server\":\"example.com\",\"server_port\":443,\"password\":\"pw\",\"tls\":{\"enabled\":true}}")
        val global=JSONObject(ConfigBuilder.build(base.copy(nodes=listOf(tls),settings=mapOf("globalAllowInsecure" to "true","muxEnabled" to "true")))).getJSONArray("outbounds").getJSONObject(0)
        assertTrue(global.getJSONObject("tls").getBoolean("insecure"));assertTrue(global.getJSONObject("multiplex").getBoolean("enabled"))
    }
    @Test fun migratedChainCustomAndWireguardBuild() {
        val other=node.copy(id=2,name="二")
        val chain=node.copy(id=3,outbound="{\"type\":\"chain\",\"node_ids\":[1,2]}")
        val data=AppData(nodes=listOf(node,other,chain),groups=listOf(Group(1,"组")),settings=mapOf("selectedNodeId" to "3"))
        val root=JSONObject(ConfigBuilder.build(data));val outs=root.getJSONArray("outbounds")
        val first=(0 until outs.length()).map { outs.getJSONObject(it) }.first { it.getString("tag")=="node-3" }
        assertEquals("node-3-chain-1",first.getString("detour"));assertFalse(root.toString().contains("node_ids"))
        val custom=node.copy(outbound="{\"type\":\"custom\",\"config\":{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"original\"}],\"experimental\":{\"clash_api\":{\"secret\":\"old\"}}}}")
        val customData=data.copy(nodes=listOf(custom),settings=mapOf("selectedNodeId" to "1"))
        val built=ConfigBuilder.build(customData,runtimeSecret="runtime")
        assertTrue(built.contains("original"));assertFalse(built.contains("old"));assertTrue(built.contains("runtime"))
        assertFalse(ConfigBuilder.build(customData,Purpose.EXPORT).contains("secret"))
        val wg=node.copy(outbound="{\"type\":\"wireguard\",\"server\":\"example.com\",\"server_port\":51820,\"local_address\":[\"10.0.0.2/32\"],\"private_key\":\"private\",\"peer_public_key\":\"public\"}")
        val wireguard=JSONObject(ConfigBuilder.build(data.copy(nodes=listOf(wg),settings=emptyMap())))
        assertEquals("example.com",wireguard.getJSONArray("endpoints").getJSONObject(0).getJSONArray("peers").getJSONObject(0).getString("address"))
    }
    @Test fun customOverridesRecursiveListOrderAndIsolation() {
        val changed=node.copy(metadata="{\"customConfig\":{\"log\":{\"level\":\"debug\"},\"route\":{\"+rules\":[{\"domain\":[\"first.example\"],\"outbound\":\"direct\"}]},\"experimental\":{\"clash_api\":{\"secret\":\"old\"}},\"inbounds+\":[{\"type\":\"mixed\",\"tag\":\"custom-in\",\"listen_port\":2089}]}}")
        val data=AppData(nodes=listOf(changed),groups=listOf(Group(1,"组")),rules=listOf(RouteRule(1,"自定义规则",advanced="{\"customRule\":{\"domain_suffix\":[\"only.example\"],\"outbound\":\"direct\"}}")),settings=mapOf("globalCustomConfig" to "{\"log\":{\"timestamp\":true}}","dnsStrategyServer" to "ipv4_only","dnsStrategyRemote" to "prefer_ipv6"))
        val root=JSONObject(ConfigBuilder.build(data,runtimeSecret="new"));assertEquals("debug",root.getJSONObject("log").getString("level"));assertTrue(root.getJSONObject("log").getBoolean("timestamp"))
        assertEquals("first.example",root.getJSONObject("route").getJSONArray("rules").getJSONObject(0).getJSONArray("domain").getString(0));assertEquals(3,root.getJSONArray("inbounds").length())
        assertFalse(root.toString().contains("customRule"));assertEquals("new",root.getJSONObject("experimental").getJSONObject("clash_api").getString("secret"))
        val dnsRules=root.getJSONObject("dns").getJSONArray("rules")
        val customDns=(0 until dnsRules.length()).map{dnsRules.getJSONObject(it)}.first{it.optString("action")=="route" && it.optJSONArray("domain_suffix")?.optString(0)=="only.example"}
        assertEquals("dns-direct",customDns.getString("server"))
        assertEquals(0,JSONObject(ConfigBuilder.build(data,Purpose.TEST,1)).getJSONArray("inbounds").length())
        assertFalse(ConfigBuilder.build(data,Purpose.EXPORT).contains("secret"))
        val parsed=SubscriptionParser.parse("[{\"type\":\"trojan\",\"server\":\"example.com\",\"server_port\":443,\"password\":\"pw\",\"tag\":\"old-name\",\"display_name\":\"新名字\"}]").single()
        assertEquals("新名字",parsed.name);assertEquals("old-name",JSONObject(parsed.metadata).getString("sourceTag"));assertFalse(parsed.outbound.contains("display_name"))
    }
    @Test fun disabledGroupsDns114AndHysteriaPorts() {
        val enabled=node.copy(id=2,groupId=2,name="日本")
        val data=AppData(nodes=listOf(node,enabled),groups=listOf(Group(1,"停用",enabled=false),Group(2,"启用")),settings=mapOf("selectedNodeId" to "1","smart.ai.target" to "node:1","smartRules.ai" to "DOMAIN,ai.example","dnsStrategyRemote" to "ipv4_only"))
        val root=JSONObject(ConfigBuilder.build(data));val outs=root.getJSONArray("outbounds");val proxy=(0 until outs.length()).map { outs.getJSONObject(it) }.first { it.getString("tag")=="proxy" }
        assertEquals("node-2",proxy.getString("default"));assertEquals(1,proxy.getJSONArray("outbounds").length());assertFalse(root.toString().contains("ai.example"))
        val dns=root.getJSONObject("dns");for(i in 0 until dns.getJSONArray("rules").length()) assertFalse(dns.getJSONArray("rules").getJSONObject(i).has("strategy"))
        assertEquals("ipv4_only",dns.getString("strategy"));assertTrue(dns.toString().contains("predefined"))
        try { ConfigBuilder.build(data.copy(groups=data.groups.map { it.copy(enabled=false) }));fail("all disabled") } catch(_:IllegalArgumentException) {}
        val dependency=data.copy(groups=listOf(Group(1,"停用",enabled=false),Group(2,"启用",frontProxy=1)))
        val dependent=JSONObject(ConfigBuilder.build(dependency));val depOuts=dependent.getJSONArray("outbounds");assertTrue((0 until depOuts.length()).any { depOuts.getJSONObject(it).optString("tag")=="node-1" })
        val one=JSONObject();SubscriptionParser.applyHysteriaPorts(one,"443");assertEquals(443,one.getInt("server_port"));assertFalse(one.has("server_ports"))
        SubscriptionParser.applyHysteriaPorts(one,"443,500-600");assertEquals("443:443",one.getJSONArray("server_ports").getString(0));assertEquals("500:600",one.getJSONArray("server_ports").getString(1))
        try { SubscriptionParser.applyHysteriaPorts(one,"+443");fail("invalid positive sign") } catch(_:IllegalArgumentException) {}
    }
    @Test fun persistentFakeIpCacheAndTestIsolationAfterOverrides() {
        val data=AppData(nodes=listOf(node),groups=listOf(Group(1,"组")),settings=mapOf("statsEnabled" to "false"))
        val main=JSONObject(ConfigBuilder.build(data))
        val cache=main.optJSONObject("experimental")?.optJSONObject("cache_file")
        assertNotNull("MAIN FakeIP持久缓存缺失",cache)
        assertTrue(cache!!.getBoolean("enabled"));assertTrue(cache.getBoolean("store_fakeip"));assertEquals("../cache/cache.db",cache.getString("path"))
        val exported=JSONObject(ConfigBuilder.build(data,Purpose.EXPORT));assertEquals(cache.getString("path"),exported.getJSONObject("experimental").getJSONObject("cache_file").getString("path"))
        val custom=node.copy(metadata="{\"customConfig\":{\"inbounds\":[{\"type\":\"mixed\",\"listen_port\":2090}],\"experimental\":{\"cache_file\":{\"enabled\":true,\"path\":\"../cache/cache.db\"},\"clash_api\":{\"external_controller\":\"127.0.0.1:9090\"}}}}")
        val overridden=data.copy(nodes=listOf(custom),settings=data.settings+mapOf("globalCustomConfig" to "{\"experimental\":{\"cache_file\":{\"enabled\":true},\"clash_api\":{\"external_controller\":\"127.0.0.1:9090\"}}}"))
        val testing=JSONObject(ConfigBuilder.build(overridden,Purpose.TEST,1))
        assertEquals(0,testing.getJSONArray("inbounds").length());assertFalse(testing.optJSONObject("experimental")?.has("cache_file") ?: false);assertFalse(testing.optJSONObject("experimental")?.has("clash_api") ?: false)
        val full=custom.copy(outbound="{\"type\":\"custom\",\"config\":{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\"}],\"inbounds\":[{\"type\":\"mixed\",\"listen_port\":2090}],\"experimental\":{\"cache_file\":{\"enabled\":true},\"clash_api\":{\"external_controller\":\"127.0.0.1:9090\"}}}}")
        val fullTest=JSONObject(ConfigBuilder.build(data.copy(nodes=listOf(full)),Purpose.TEST,1))
        assertEquals(0,fullTest.getJSONArray("inbounds").length());assertFalse(fullTest.optJSONObject("experimental")?.has("cache_file") ?: false);assertFalse(fullTest.optJSONObject("experimental")?.has("clash_api") ?: false)
    }
    @Test fun malformedEntriesAreSkippedButEmptyResultFails() {
        assertEquals(1,SubscriptionParser.parse("trojan://pw@example.com:443#ok\nunsupported://x@example.com:443").size)
        try { SubscriptionParser.parse("unsupported://x@example.com:443"); fail("must reject empty result") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }
}
