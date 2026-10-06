package com.zane.zanebox

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.zane.zanebox.data.*
import com.zane.zanebox.ui.AppViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SmartDomainPickerTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val vm get()=ViewModelProvider(compose.activity)[AppViewModel::class.java]

    @Test fun domainPickerAddsAndReplacesWithoutDroppingRawLines() {
        val original="# preserve comment\nDOMAIN,old.example.test,proxy\nIP-CIDR,10.0.0.0/8\nUSER-AGENT,*custom*"
        compose.runOnIdle { vm.edit { AppData(
            nodes=listOf(Node(801,801,"域名选择节点","""{"type":"socks","server":"127.0.0.1","server_port":9}""")),
            groups=listOf(Group(801,"域名选择组")),
            settings=mapOf("appLanguage" to "zh-CN","serviceMode" to "proxy","selectedNodeId" to "801","browseGroupId" to "801","smartRules.speed" to original,"smart.speed.target" to "off","statsEnabled" to "false")
        ) } }
        compose.waitUntil(15000){!vm.busy.value && vm.data.value.setting("smartRules.speed")==original}
        compose.onNodeWithTag("tab_1").performClick()
        compose.onNodeWithTag("smart_speed").performClick()
        compose.onNodeWithText("规则来源").performClick()
        compose.onNodeWithTag("rule_source_raw").assertIsDisplayed()
        compose.onNodeWithTag("rule_source_rules").assertDoesNotExist()

        compose.onNodeWithTag("rule_domain_add").performClick()
        compose.onNodeWithText("域名后缀 · DOMAIN-SUFFIX").performClick()
        compose.onNodeWithTag("rule_domain_value").performTextInput("chosen.example.test")
        compose.onNodeWithTag("rule_domain_confirm").performClick()

        compose.onNodeWithTag("rule_domain_1").performClick()
        compose.onNodeWithText("域名关键词 · DOMAIN-KEYWORD").performClick()
        compose.onNodeWithTag("rule_domain_value").performTextReplacement("chosen-keyword")
        compose.onNodeWithTag("rule_domain_confirm").performClick()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("rule_source_save").performClick()
        compose.waitUntil(10000){!vm.busy.value && vm.data.value.setting("smartRules.speed").contains("chosen.example.test")}

        val saved=vm.store.snapshot().setting("smartRules.speed")
        assertTrue(saved.contains("# preserve comment"))
        assertTrue(saved.contains("DOMAIN-KEYWORD,chosen-keyword,proxy"))
        assertTrue(saved.contains("DOMAIN-SUFFIX,chosen.example.test"))
        assertTrue(saved.contains("IP-CIDR,10.0.0.0/8"))
        assertTrue(saved.contains("USER-AGENT,*custom*"))
        assertFalse(saved.contains("DOMAIN,old.example.test"))
    }
}
