package com.zane.zanebox

import com.zane.zanebox.config.*
import com.zane.zanebox.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OptimizationConfigTest {
    private val base=AppData(nodes=listOf(Node(1,1,"fixture","""{"type":"socks","server":"127.0.0.1","server_port":9}""")),groups=listOf(Group(1,"g")),settings=mapOf("selectedNodeId" to "1","smartRules.google" to "DOMAIN,a.example\nDOMAIN,b.example\nDOMAIN-SUFFIX,example.net\nIP-CIDR,203.0.113.0/24\nPROCESS-NAME,fixture","smart.google.target" to "proxy"))
    @Test fun serviceRulesAreCombinedWithoutAndingDifferentMatchers() {
        val route=JSONObject(ConfigBuilder.build(base)).getJSONObject("route").getJSONArray("rules")
        val rules=(0 until route.length()).map{route.getJSONObject(it)}.filter{it.optString("outbound")=="proxy"}
        assertEquals(3,rules.size)
        val domain=rules.first{it.has("domain")}
        assertEquals(2,domain.getJSONArray("domain").length())
        assertTrue(domain.has("domain_suffix"))
        assertFalse(domain.has("ip_cidr"));assertFalse(domain.has("process_name"))
    }
    @Test fun offDisablesPolicyAndFallsThroughToOrdinaryRule() {
        val data=base.copy(settings=base.settings+("smart.google.target" to "off"),rules=listOf(RouteRule(1,"own","a.example",outbound="direct")))
        val rules=JSONObject(ConfigBuilder.build(data)).getJSONObject("route").getJSONArray("rules")
        assertFalse((0 until rules.length()).any{rules.getJSONObject(it).optString("outbound")=="proxy"})
        assertEquals("off",normalizeSmartTarget("off"))
    }
    @Test fun fakeIpPrecedesRealServiceDnsButHostsAndRejectAreKept() {
        val data=base.copy(settings=base.settings+mapOf("dnsHosts" to "127.0.0.1 host.example"),rules=listOf(RouteRule(1,"block","bad.example",outbound="block",prioritize=true)))
        val rules=JSONObject(ConfigBuilder.build(data)).getJSONObject("dns").getJSONArray("rules")
        val values=(0 until rules.length()).map{rules.getJSONObject(it)}
        val fake=values.indexOfFirst{it.optString("server")=="dns-fake"}
        assertTrue(fake>=0)
        assertTrue(values.indexOfFirst{it.optString("server")=="dns-hosts"}<fake)
        assertTrue(values.indexOfFirst{it.optString("action")=="reject"}<fake)
        assertFalse(values.take(fake).any{it.optString("server").startsWith("dns-route-")})
        assertFalse(JSONObject(ConfigBuilder.build(data.copy(settings=data.settings+("fakeDns" to "false")))).getJSONObject("dns").getJSONArray("rules").toString().contains("dns-fake"))
    }
    @Test fun ordinaryRejectKeepsItsPositionAfterTheEarlierServiceFakeIp() {
        val data=base.copy(rules=listOf(RouteRule(1,"ordinary reject","a.example",outbound="block")))
        val array=JSONObject(ConfigBuilder.build(data)).getJSONObject("dns").getJSONArray("rules")
        val rows=(0 until array.length()).map {array.getJSONObject(it)}
        assertTrue(rows.indexOfFirst {it.optString("server")=="dns-fake" && it.has("domain")} < rows.indexOfFirst {it.optString("action")=="reject"})
    }
    @Test fun perServiceFamilyFilteringDoesNotBecomeAGlobalRule() {
        val data=base.copy(settings=base.settings+mapOf("smart.google.target" to "direct","dnsStrategyDirect" to "ipv6_only","dnsStrategyRemote" to "ipv4_only","ipv6" to "true"))
        val array=JSONObject(ConfigBuilder.build(data)).getJSONObject("dns").getJSONArray("rules")
        val rows=(0 until array.length()).map {array.getJSONObject(it)}
        val directFamily=rows.first {it.optString("action")=="predefined" && it.has("domain")}
        assertEquals("A",directFamily.getJSONArray("query_type").getString(0));assertTrue(directFamily.has("inbound"))
        val fake=rows.first {it.optString("server")=="dns-fake" && it.has("domain")}
        assertFalse(fake.has("strategy"))
        val globalFamily=rows.first {it.optString("action")=="predefined" && !it.has("domain")}
        assertEquals("AAAA",globalFamily.getJSONArray("query_type").getString(0))
    }

}
