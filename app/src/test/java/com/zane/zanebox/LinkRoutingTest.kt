package com.zane.zanebox

import com.zane.zanebox.config.*
import com.zane.zanebox.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LinkRoutingTest {
    private val data=AppData(nodes=listOf(Node(1,1,"Australia","""{"type":"socks","server":"a.test","server_port":1080}"""),Node(2,1,"JP 东京","""{"type":"socks","server":"b.test","server_port":1080}""")),groups=listOf(Group(1,"g")))
    private fun suffix(rule:JSONObject,value:String):Boolean {val a=rule.optJSONArray("domain_suffix") ?: return false;return (0 until a.length()).any {a.optString(it)==value}}
    @Test fun allBundledPoliciesProduceDirectDomainAndPackageRoutes() {
        builtinSmartRuleFiles.keys.forEach { service ->
            val enabled=data.copy(settings=mapOf("fakeDns" to "false","smart.$service.target" to "direct","smartCustom.$service.packages" to "com.example.video","statsEnabled" to "false"))
            val loaded=withBuiltinSmartRules(enabled) { File("src/main/assets/$it").readText() }
            val root=JSONObject(ConfigBuilder.build(loaded));val route=root.getJSONObject("route");val rules=route.getJSONArray("rules")
            assertTrue(service,(0 until rules.length()).map{rules.getJSONObject(it)}.any{it.has("domain_suffix") && it.optString("outbound")=="direct"})
            assertTrue(service,(0 until rules.length()).map{rules.getJSONObject(it)}.any{it.optJSONArray("package_name")?.optString(0)=="com.example.video" && it.optString("outbound")=="direct"})
            assertTrue(route.getBoolean("find_process"))
            val dnsRules=root.getJSONObject("dns").getJSONArray("rules")
            assertTrue(service,(0 until dnsRules.length()).map{dnsRules.getJSONObject(it)}.any{it.has("domain_suffix") && it.optString("server")=="dns-direct"})
            assertEquals("",withBuiltinSmartRules(enabled.copy(settings=enabled.settings+builtinSmartRuleFiles.keys.associate{"smartRules.$it" to ""})){error("must preserve explicit empty rules")}.setting("smartRules.$service"))
        }
    }
    @Test fun removedRegionTargetsFollowTheMainProxyAndKeepManualMetadata() {
        val legacy=data.copy(settings=mapOf("fakeDns" to "false","selectedNodeId" to "1","nodeRegion.1" to "us","smart.youtube.target" to "region:jp","smartRules.youtube" to "DOMAIN-SUFFIX,video.test"))
        assertEquals(listOf(1L),ConfigBuilder.smartTargetNodeIds(legacy,"region:jp"))
        val root=JSONObject(ConfigBuilder.build(legacy));val raw=root.getJSONObject("route").getJSONArray("rules")
        assertEquals("proxy",(0 until raw.length()).map{raw.getJSONObject(it)}.single{it.has("domain_suffix")}.getString("outbound"))
        assertEquals("us",legacy.setting("nodeRegion.1"))
    }
    @Test fun sameGroupFrontAndLandingDependenciesDoNotWrapThemselves() {
        val main=Node(1,1,"主节点","""{"type":"socks","server":"middle.test","server_port":1080}""")
        val front=main.copy(id=2,name="前置")
        val landing=main.copy(id=3,name="落地")
        val root=JSONObject(ConfigBuilder.build(AppData(nodes=listOf(main,front,landing),groups=listOf(Group(1,"g",frontProxy=2,landingProxy=3)),settings=mapOf("fakeDns" to "false","selectedNodeId" to "1"))))
        val array=root.getJSONArray("outbounds");val outs=(0 until array.length()).map{array.getJSONObject(it)}.associateBy{it.getString("tag")}
        assertFalse(outs.getValue("node-2").has("detour"));assertFalse(outs.getValue("node-3").has("detour"))
        assertEquals("node-2",outs.getValue("node-1-hop").getString("detour"))
        assertEquals("node-1-hop",outs.getValue("node-1").getString("detour"))
    }

    @Test fun speedPolicyRunsBeforeOtherBuiltinPolicies() {
        val enabled=data.copy(settings=mapOf("fakeDns" to "false","smart.speed.target" to "node:2","smart.youtube.target" to "direct","smartRules.youtube" to "DOMAIN-SUFFIX,speedtest.net"))
        val root=JSONObject(ConfigBuilder.build(withBuiltinSmartRules(enabled){File("src/main/assets/$it").readText()}))
        val raw=root.getJSONObject("route").getJSONArray("rules");val rules=(0 until raw.length()).map{raw.getJSONObject(it)}
        val matched=rules.filter{suffix(it,"speedtest.net")}
        assertEquals(listOf("node-2","direct"),matched.map{it.getString("outbound")})
        assertTrue(rules.any{suffix(it,"dnsleaktest.com") && it.optString("outbound")=="node-2"})
    }

    @Test fun dnsOrderingFollowsSpeedThenOtherPolicies() {
        val enabled=data.copy(settings=mapOf("fakeDns" to "false","smart.speed.target" to "node:2","smart.netflix.target" to "direct"))
        val root=JSONObject(ConfigBuilder.build(withBuiltinSmartRules(enabled){File("src/main/assets/$it").readText()}))
        val array=root.getJSONObject("dns").getJSONArray("rules");val rules=(0 until array.length()).map{array.getJSONObject(it)}
        val matched=rules.filter{suffix(it,"fast.com") && it.optString("action")=="route"}
        assertEquals(listOf("dns-route-node-2","dns-direct"),matched.map{it.getString("server")})
        val servers=root.getJSONObject("dns").getJSONArray("servers")
        assertEquals("node-2",(0 until servers.length()).map{servers.getJSONObject(it)}.single{it.getString("tag")=="dns-route-node-2"}.getString("detour"))
    }

    @Test fun persistedPolicyOrderControlsBothRouteAndDns() {
        val enabled=data.copy(settings=mapOf("fakeDns" to "false","smart.speed.target" to "node:2","smart.netflix.target" to "direct","smartPolicyOrder" to "netflix\nspeed\nnetflix\nmissing"))
        assertEquals(listOf("netflix","speed"),smartPolicyKeys(enabled).take(2))
        val root=JSONObject(ConfigBuilder.build(withBuiltinSmartRules(enabled){File("src/main/assets/$it").readText()}))
        fun matches(array:org.json.JSONArray)= (0 until array.length()).map{array.getJSONObject(it)}.filter{suffix(it,"fast.com") && it.optString("action")=="route"}
        assertEquals(listOf("direct","node-2"),matches(root.getJSONObject("route").getJSONArray("rules")).map{it.getString("outbound")})
        assertEquals(listOf("dns-direct","dns-route-node-2"),matches(root.getJSONObject("dns").getJSONArray("rules")).map{it.getString("server")})
    }

    @Test fun frontAndRearRouteRulesSurroundTheOrderedPolicies() {
        val enabled=data.copy(rules=listOf(RouteRule(1,"前置",domains="fast.com",outbound="direct",prioritize=true),RouteRule(2,"后置",domains="fast.com",outbound="node:1")),settings=mapOf("fakeDns" to "false","smart.speed.target" to "node:2"))
        val root=JSONObject(ConfigBuilder.build(withBuiltinSmartRules(enabled){File("src/main/assets/$it").readText()}))
        fun matching(array:org.json.JSONArray)=(0 until array.length()).map{array.getJSONObject(it)}.filter{suffix(it,"fast.com") && it.optString("action")=="route"}
        assertEquals(listOf("direct","node-2","proxy","node-1"),matching(root.getJSONObject("route").getJSONArray("rules")).map{it.getString("outbound")})
        assertEquals(listOf("dns-direct","dns-route-node-2","dns-route-proxy","dns-route-node-1"),matching(root.getJSONObject("dns").getJSONArray("rules")).map{it.getString("server")})
    }

    @Test fun factoryRulesAreClassifiedDisabledAndDoNotOverwriteExistingRules() {
        val cn=withFactoryRouteDefaults(data,"CN")
        assertEquals(5,cn.rules.size);assertEquals(3,cn.rules.count{it.prioritize});assertTrue(cn.rules.none{it.enabled})
        assertTrue(cn.rules.all{it.id>2});assertEquals(cn,withFactoryRouteDefaults(cn,"US"))
        assertEquals(9,factoryRouteRules("US").size)
        val custom=data.copy(rules=listOf(RouteRule(99,"用户",domains="example.test")))
        assertEquals(custom.rules,withFactoryRouteDefaults(custom,"CN").rules)
        val removed=cn.copy(rules=emptyList());assertTrue(withFactoryRouteDefaults(removed,"CN").rules.isEmpty())
        val root=JSONObject(ConfigBuilder.build(cn.copy(rules=cn.rules.map{it.copy(enabled=true)})))
        val raw=root.getJSONObject("route").getJSONArray("rules");val rules=(0 until raw.length()).map{raw.getJSONObject(it)}
        assertEquals("reject",rules.first{it.has("port")}.getString("action"))
        assertTrue(rules.any{it.optJSONArray("rule_set")?.optString(0)=="geoip:cn" && it.getString("outbound")=="direct"})
    }

    @Test fun conditionalBlockRulesDoNotBlockAllDnsAndPolicyDnsKeepsFamilyLimits() {
        val blocked=data.copy(rules=listOf(RouteRule(1,"只屏蔽QUIC",domains="example.test",outbound="block",advanced="""{"network":["udp"],"port":[443]}""",prioritize=true)))
        val blockedDns=JSONObject(ConfigBuilder.build(blocked)).getJSONObject("dns").getJSONArray("rules")
        assertTrue((0 until blockedDns.length()).none{blockedDns.getJSONObject(it).optString("action")=="reject"})
        listOf("ipv4_only" to "AAAA","ipv6_only" to "A").forEach { (strategy,family)->
            val enabled=data.copy(settings=mapOf("fakeDns" to "false","smart.speed.target" to "node:2","dnsStrategyRemote" to strategy,"ipv6" to "true"))
            val root=JSONObject(ConfigBuilder.build(withBuiltinSmartRules(enabled){File("src/main/assets/$it").readText()}))
            assertTrue(root.getJSONObject("dns").getBoolean("reverse_mapping"))
            val rules=root.getJSONObject("dns").getJSONArray("rules")
            val matches=(0 until rules.length()).map{rules.getJSONObject(it)}.filter{suffix(it,"fast.com")}
            assertEquals("predefined",matches.first().getString("action"));assertEquals(family,matches.first().getJSONArray("query_type").getString(0))
            assertEquals("dns-route-node-2",matches[1].getString("server"))
        }
    }

    @Test fun directDnsUsesTheNativeDefaultDialerRatherThanAnEmptyDetour() {
        val root=JSONObject(ConfigBuilder.build(data.copy(settings=mapOf("fakeDns" to "false","dnsDirect" to "tcp://127.0.0.1:19087"))))
        val array=root.getJSONObject("dns").getJSONArray("servers")
        val direct=(0 until array.length()).map{array.getJSONObject(it)}.single{it.getString("tag")=="dns-direct"}
        assertFalse(direct.has("detour"));assertEquals(19087,direct.getInt("server_port"))
    }

}
