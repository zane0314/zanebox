package com.zane.zanebox

import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.config.builtinSmartRuleFiles
import com.zane.zanebox.data.*
import org.junit.Test

/** A legacy-format fixture for signed upgrade and real, controlled proxy smoke checks. */
class ChainUpgradeTest {
    @Test fun seedLegacyChainForSignedUpgrade() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        fun node(id:Long,group:Long,name:String,port:Int)=Node(id,group,name,"""{"type":"socks","server":"10.0.2.2","server_port":$port}""",ping=40,tx=123,rx=456)
        val data=AppData(
            nodes=listOf(node(111,101,"机场中转",19086),node(112,102,"固定前置",19085),node(113,103,"台湾家宽 B",19082),node(115,103,"备用家宽 A",19081),Node(114,104,"机场 → 家宽","""{"type":"chain","node_ids":[113,111]}""")),
            groups=listOf(Group(101,"机场分组",frontProxy=112,landingProxy=113),Group(102,"前置分组"),Group(103,"家宽分组"),Group(104,"独立链路")),
            rules=listOf(RouteRule(121,"链路验收",ipCidrs="203.0.113.9/32",outbound="proxy")),
            merges=listOf(MergeGroup(131,"链式节点汇总",nodeIds=listOf(111,114),selectedId=114)),
            settings=builtinSmartRuleFiles.keys.associate { "smart.$it.target" to "off" }+mapOf(
                "selectedNodeId" to "111","selectedGroupId" to "101","browseGroupId" to "101","appLanguage" to "zh-CN","uiSkin" to "linear",
                "factoryRouteDefaultsVersion" to "1","serviceMode" to "vpn","sniff" to "false","fakeDns" to "false","bypassLan" to "false","bypassLanInCore" to "false",
                "dnsDirect" to "local","dnsRemote" to "local","testUrl" to "http://203.0.113.9:19080/test","testTimeout" to "4000","unifiedDelay" to "false","statsEnabled" to "false","rulesUpdateInterval" to "off"))
        ZaneStore(context).use {it.replace(data)}
    }
}
