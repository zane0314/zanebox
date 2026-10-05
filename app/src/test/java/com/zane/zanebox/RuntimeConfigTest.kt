package com.zane.zanebox

import com.zane.zanebox.config.ConfigBuilder
import com.zane.zanebox.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RuntimeConfigTest {
    private val node=Node(1,1,"n","""{"type":"socks","server":"example.com","server_port":1080}""")
    private val base=AppData(nodes=listOf(node),groups=listOf(Group(1,"g")))
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
