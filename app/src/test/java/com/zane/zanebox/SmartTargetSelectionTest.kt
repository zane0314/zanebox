package com.zane.zanebox

import com.zane.zanebox.config.*
import com.zane.zanebox.data.*
import com.zane.zanebox.ui.targetName
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SmartTargetSelectionTest {
    private fun node(id:Long,group:Long,ping:Int)=Node(id,group,"节点 $id","""{"type":"socks","server":"127.0.0.1","server_port":${10000+id}}""",ping=ping)
    private val data=AppData(nodes=listOf(node(1,1,100),node(2,2,60),node(3,3,1),node(4,4,2)),groups=listOf(Group(1,"订阅 A","https://example.test/a"),Group(2,"订阅 B","https://example.test/b"),Group(3,"本地"),Group(4,"停用订阅","https://example.test/d",enabled=false)),merges=listOf(MergeGroup(5,"旧来源",nodeIds=listOf(3))),settings=mapOf("selectedNodeId" to "3","smartSourceGroupId" to "1","smartSourceMergeId" to "5","urlTestTolerance" to "50"))
    @Test fun autoUsesEveryEnabledSubscriptionAndIgnoresLocalAndSourceFilters() {
        assertEquals(listOf(1L,2L),ConfigBuilder.smartTargetNodeIds(data,"auto"))
        assertEquals(listOf(3L),ConfigBuilder.smartTargetNodeIds(data,"group:3"))
        val root=JSONObject(ConfigBuilder.build(data.copy(settings=data.settings+mapOf("smart.youtube.target" to "auto","smartRules.youtube" to "DOMAIN-SUFFIX,video.test"))))
        val out=root.getJSONArray("outbounds");val auto=(0 until out.length()).map{out.getJSONObject(it)}.single{it.optString("tag")=="smart-youtube"}
        assertEquals("[\"node-1\",\"node-2\"]",auto.getJSONArray("outbounds").toString())
        assertEquals(50,auto.getInt("tolerance"))
    }
    @Test fun emptySubscriptionPoolWarnsAndDoesNotIncludeLocalNodes() {
        val local=data.copy(groups=data.groups.map{if(it.subscriptionUrl.isNotBlank())it.copy(enabled=false)else it},settings=data.settings+("smart.youtube.target" to "auto"))
        assertTrue(ConfigBuilder.smartTargetNodeIds(local,"auto").isEmpty())
        assertTrue(ConfigBuilder.targetWarnings(local).any{it.contains("youtube")})
    }
    @Test fun removedRegionsHaveTheSameProxyBehaviorAndRulesStayIntact() {
        val settings=data.settings+mapOf("smartRules.youtube" to "DOMAIN-SUFFIX,video.test")
        val proxy=data.copy(settings=settings+("smart.youtube.target" to "proxy"))
        for(old in listOf("region:jp","region:unknown")) {
            val legacy=proxy.copy(settings=settings+("smart.youtube.target" to old))
            assertEquals(ConfigBuilder.build(proxy),ConfigBuilder.build(legacy))
            assertEquals(proxy.setting("smartRules.youtube"),legacy.setting("smartRules.youtube"))
            assertEquals(listOf(3L),ConfigBuilder.smartTargetNodeIds(legacy,old))
        }
        assertEquals("代理",targetName("proxy",data))
    }
}
