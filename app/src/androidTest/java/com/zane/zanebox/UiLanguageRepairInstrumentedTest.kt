package com.zane.zanebox

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.zane.zanebox.data.*
import com.zane.zanebox.ui.AppViewModel
import org.junit.Rule
import org.junit.Test

class UiLanguageRepairInstrumentedTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Test fun englishHomeSettingsAndEditorsKeepUserNamesAndTranslateLabels() {
        val vm=ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnIdle {vm.edit(autoApply=false) {AppData(
            nodes=listOf(Node(1,1,"设置","""{"type":"socks","server":"127.0.0.1","server_port":9}""")),
            groups=listOf(Group(1,"规则")),settings=mapOf("appLanguage" to "en","serviceMode" to "proxy",
                "selectedNodeId" to "1","selectedGroupId" to "1","confirmProfileDelete" to "true","smartCustom.custom_en.name" to "设置 %s"))}}
        compose.waitUntil(10000) {!vm.busy.value && vm.data.value.setting("appLanguage")=="en"}
        compose.onNodeWithTag("node_1").assertTextContains("设置",substring=true)
        compose.onNodeWithTag("home_auto").assertTextContains("Choose the lowest-latency node",substring=true)
        compose.onNodeWithTag("tab_2").performClick()
        compose.onNodeWithTag("settings_general").performClick()
        compose.onNodeWithTag("subpage_list").performScrollToNode(hasTestTag("setting_autoTestSelectedNode"))
        compose.onNodeWithTag("setting_autoTestSelectedNode").assertTextContains("Update latency after selecting a node",substring=true)
        compose.onNodeWithTag("page_back").performClick()
        compose.onNodeWithTag("settings_groups").performClick()
        compose.onNodeWithTag("manage_group_1").assertTextContains("规则",substring=true).assertTextContains("1 nodes",substring=true)
        compose.onNodeWithTag("page_back").performClick()
        compose.onNodeWithTag("main_back").performClick()
        compose.onNodeWithTag("node_menu_1").performClick()
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithTag("subpage_list").performScrollToNode(hasTestTag("node_field_server"))
        compose.onNodeWithTag("node_field_server").assertTextContains("Server address",substring=true)
        compose.onNodeWithTag("page_back").performClick()
        compose.onNodeWithTag("node_menu_1").performClick()
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onNodeWithText("Delete 1 nodes?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("tab_1").performClick()
        compose.onNodeWithTag("page_list").performScrollToNode(hasTestTag("smart_vpn_hint"))
        compose.onNodeWithTag("smart_vpn_hint").assertTextEquals("Smart app routing requires VPN mode.")
        compose.onNodeWithTag("smart_add_custom").assertTextContains("Add custom app group",substring=true).performClick()
        compose.onNode(hasText("Add custom app group") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithTag("page_back").performClick()
        compose.onNodeWithTag("page_list").performScrollToNode(hasTestTag("smart_custom_en"))
        compose.onNodeWithTag("smart_custom_en").assertTextContains("设置 %s",substring=true).performClick()
        compose.onNodeWithTag("page_list").performScrollToNode(hasText("Delete app group"))
        compose.onNodeWithText("Delete app group").performClick()
        compose.onNodeWithText("Delete custom app group?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("page_list").performScrollToNode(hasTestTag("smart_youtube"))
        compose.onNodeWithTag("smart_youtube").performClick()
        compose.onNodeWithTag("page_list").performScrollToNode(hasText("Rule source"))
        compose.onNodeWithText("Rule source").performClick()
        compose.onNodeWithTag("subpage_list").performScrollToNode(hasTestTag("rule_domain_add"))
        compose.onNodeWithTag("rule_domain_add").assertTextContains("Add",substring=true).performClick()
        compose.onNodeWithText("Domain match type").assertIsDisplayed()
        compose.onNodeWithTag("choice_DOMAIN-SUFFIX").performClick()
        compose.onNodeWithText("Add domain rule").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("page_back").performClick()
        compose.onNodeWithTag("smart_menu").performClick()
        compose.onNodeWithText("Backup & Restore").performClick()
        compose.onNodeWithText("Restore from WebDAV").performClick()
        compose.onNodeWithText("No cloud backups").assertIsDisplayed()
        compose.onNodeWithTag("page_back").performClick()
    }
}
