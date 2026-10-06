package com.zane.zanebox

import com.zane.zanebox.config.*
import com.zane.zanebox.data.*
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SmartRuleCompatibilityTest {
    private val base=AppData(nodes=listOf(Node(1,1,"fixture","""{"type":"socks","server":"127.0.0.1","server_port":9}""")),groups=listOf(Group(1,"fixture")),settings=mapOf("selectedNodeId" to "1","smart.speed.target" to "off"))
    private fun read(path:String)=File("src/main/assets/$path").readText()
    @Test fun bundledPoliciesHaveExecutableRulesWithoutOtherClientDirectives() {
        val data=withBuiltinSmartRules(base.copy(settings=base.settings+builtinSmartRuleFiles.keys.associate{"smart.$it.target" to "proxy"}),::read)
        builtinSmartRuleFiles.keys.forEach {key->
            val rules=data.setting("smartRules.$key");assertTrue(key,rules.isNotBlank())
            assertFalse("$key 包含不能生效的匹配",rules.lines().any{it.substringBefore(',').trim() in setOf("USER-AGENT","IP-ASN","OR")})
        }
        assertTrue(ConfigBuilder.warnings(data).isEmpty())
        assertTrue(JSONObject(ConfigBuilder.build(data)).getJSONObject("route").getJSONArray("rules").length()>10)
    }
    @Test fun exactOldBundledRulesAreMigratedButCustomAdditionsStayUntouched() {
        val raw=read("anybox-rules/YouTube.list")
        val old=base.copy(settings=base.settings+mapOf("smart.youtube.target" to "proxy","smartRules.youtube" to raw))
        val migrated=withBuiltinSmartRules(old,::read)
        assertFalse(migrated.setting("smartRules.youtube").contains("USER-AGENT,"))
        assertTrue(migrated.setting("smartRules.youtube").contains("DOMAIN-SUFFIX,googlevideo.com"))
        assertEquals("移除无效条目不能改变可执行配置",ConfigBuilder.build(old),ConfigBuilder.build(migrated))
        val custom=old.copy(settings=old.settings+("smartRules.youtube" to raw+"\nUSER-AGENT,*user-custom*"))
        assertEquals(custom.settings,withBuiltinSmartRules(custom,::read).settings)
        assertTrue(ConfigBuilder.warnings(custom).any{it.contains("USER-AGENT")})
        assertTrue(ConfigBuilder.targetWarnings(custom).isEmpty())
        assertTrue(ConfigBuilder.targetWarnings(custom.copy(settings=custom.settings+("smart.youtube.target" to "region:unknown"))).any{it.contains("没有可用节点")})
    }
    @Test fun explicitEmptyAndRemoteRulesArePreserved() {
        val empty=base.copy(settings=base.settings+mapOf("smart.youtube.target" to "proxy","smartRules.youtube" to ""))
        assertEquals("",withBuiltinSmartRules(empty,::read).setting("smartRules.youtube"))
        val remote=empty.copy(settings=empty.settings+mapOf("smartUrl.youtube" to "https://example.test/youtube.list","smartRules.youtube" to read("anybox-rules/YouTube.list")))
        assertEquals(remote.settings,withBuiltinSmartRules(remote,::read).settings)
    }
}
