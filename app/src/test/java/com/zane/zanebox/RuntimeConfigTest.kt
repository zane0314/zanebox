package com.zane.zanebox

import com.zane.zanebox.config.ConfigBuilder
import com.zane.zanebox.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RuntimeConfigTest {
    private val node=Node(1,1,"n","""{"type":"socks","server":"example.com","server_port":1080}""")
    private val base=AppData(nodes=listOf(node),groups=listOf(Group(1,"g")))
    @Test fun ipv6FakeDnsStaysInsideVpnWhenLanIsBypassed() {
        val root=JSONObject(ConfigBuilder.build(base.copy(settings=mapOf("ipv6" to "true","bypassLan" to "true"))))
        val tun=root.getJSONArray("inbounds").getJSONObject(0)
        val excluded=tun.getJSONArray("route_exclude_address")
        assertFalse("FakeDNS IPv6 不能被局域网绕过",(0 until excluded.length()).any { excluded.getString(it)=="fc00::/7" })
        val prefixes=(0 until excluded.length()).map { excluded.getString(it) }
        val keep=listOf("fc00::/18","172.19.0.1/30","fdfe:dcba:9876::1/126")
        assertEquals(com.zane.zanebox.core.RouteMath.subtract(keep,emptyList()),com.zane.zanebox.core.RouteMath.subtract(keep,prefixes))
        assertTrue(root.getJSONObject("dns").getJSONArray("servers").toString().contains("fc00::/18"))
    }
    @Test fun homeAutoUsesEnabledGroupsAndSleepsAfterIdleWithoutTestingManualSelection() {
        val data=base.copy(nodes=listOf(node,node.copy(id=2,groupId=2),node.copy(id=3,groupId=3)),groups=listOf(Group(1,"local"),Group(2,"sub","https://example.test"),Group(3,"off",enabled=false)),settings=mapOf("homeAutoSelect" to "true"))
        fun out(d:AppData):List<JSONObject> { val a=JSONObject(ConfigBuilder.build(d)).getJSONArray("outbounds");return (0 until a.length()).map { a.getJSONObject(it) } }
        val auto=out(data).single { it.optString("tag")=="home-auto" }
        assertEquals("urltest",auto.getString("type"));assertEquals("10m",auto.getString("interval"));assertEquals("10m",auto.getString("idle_timeout"));assertEquals(30,auto.getInt("tolerance"))
        assertEquals(listOf("node-1","node-2"),(0 until auto.getJSONArray("outbounds").length()).map { auto.getJSONArray("outbounds").getString(it) })
        assertEquals("home-auto",out(data).single { it.optString("tag")=="proxy" }.getString("default"))
        assertFalse(out(base).any { it.optString("tag")=="home-auto" })
    }
    @Test fun geoRuleSetsUseBundledDatabases() {
        val root=JSONObject(ConfigBuilder.build(base.copy(rules=listOf(RouteRule(1,"cn",domains="geosite:cn\ngeoip:cn",outbound="direct")))))
        val sets=root.getJSONObject("route").getJSONArray("rule_set")
        assertEquals(2,sets.length())
        for(i in 0 until sets.length()) { val set=sets.getJSONObject(i);assertEquals("local",set.getString("type"));assertEquals(set.getString("tag"),set.getString("path"));assertFalse(set.has("url"));assertFalse(set.has("download_detour")) }
    }
    @Test fun findProcessOnlyWhenNeeded() {
        fun find(data:AppData)=JSONObject(ConfigBuilder.build(data)).getJSONObject("route").getBoolean("find_process")
        assertTrue("统计默认开启需要进程信息",find(base))
        val quiet=base.copy(settings=mapOf("statsEnabled" to "false"))
        assertFalse(find(quiet))
        assertTrue(find(quiet.copy(rules=listOf(RouteRule(1,"app",packages="com.example.app",outbound="direct")))))
        assertFalse(JSONObject(ConfigBuilder.build(base,com.zane.zanebox.config.Purpose.TEST,1)).getJSONObject("route").getBoolean("find_process"))
    }
}
