package com.zane.zanebox

import com.zane.zanebox.config.*
import com.zane.zanebox.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SmartPolicyOrderTest {
    private val data=AppData(nodes=listOf(Node(1,1,"A","""{"type":"socks","server":"127.0.0.1","server_port":1080}""")),groups=listOf(Group(1,"fixture")),settings=mapOf("smartCustom.custom_7.name" to "工作","smartCustom.custom_7.packages" to "com.example.work","smartCustom.ai.packages" to "com.example.work","smart.ai.target" to "direct","smart.custom_7.target" to "proxy","smartRules.ai" to "","smartRules.custom_7" to "","perAppEnabled" to "true","perAppMode" to "include","perAppPackages" to "com.example.work"))
    @Test fun fullOrderPersistsWithoutChangingOtherFieldsAndControlsMatchingPriority() {
        val before=smartPolicyKeys(data)
        val requested=listOf("custom_7")+before.filter{it!="custom_7"}
        val sorted=data.reorderSmartPolicies(requested)
        assertEquals(requested,smartPolicyKeys(sorted))
        assertEquals(data.settings,sorted.settings-"smartPolicyOrder")
        assertEquals(data.nodes,sorted.nodes);assertEquals(data.groups,sorted.groups);assertEquals(data.rules,sorted.rules)
        val rules=JSONObject(ConfigBuilder.build(sorted)).getJSONObject("route").getJSONArray("rules")
        assertEquals(listOf("proxy","direct"),(0 until rules.length()).map{rules.getJSONObject(it)}.filter{it.has("package_name")}.map{it.getString("outbound")})
        val manager=com.zane.zanebox.backup.BackupManager()
        assertEquals(sorted.toJson(),manager.`import`(manager.export(sorted)).toJson())
    }
    @Test fun changedGroupsOrDuplicateKeysAreRejectedWithoutTouchingData() {
        val keys=smartPolicyKeys(data)
        for(requested in listOf(keys.dropLast(1),keys+keys.first(),keys+"unknown")) {
            try {data.reorderSmartPolicies(requested);fail("拒绝过期或重复排序")}catch(_:IllegalArgumentException){}
        }
        val current=data.copy(settings=data.settings+("smartCustom.custom_8.name" to "新组"))
        try {current.reorderSmartPolicies(keys);fail("拖动期间新增分组不能覆盖")}catch(_:IllegalArgumentException){}
        assertEquals("新组",current.setting("smartCustom.custom_8.name"))
    }
}
