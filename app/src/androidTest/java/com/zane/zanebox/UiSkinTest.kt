package com.zane.zanebox

import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import com.zane.zanebox.data.*
import com.zane.zanebox.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UiSkinThemeTest {
    @get:Rule val compose=createComposeRule()
    @Test fun fiveSkinsAndLegacySettingsKeepTheirOwnAppearanceUnderSystemNightMode() {
        var data by mutableStateOf(AppData())
        var background=Color.Unspecified
        var resolved=""
        compose.setContent {
            val night=Configuration(LocalConfiguration.current).apply {
                uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
            }
            CompositionLocalProvider(LocalConfiguration provides night) {
                ZaneTheme(data) {
                    val color=MaterialTheme.colorScheme.background
                    val skin=skinPalette.name
                    SideEffect {background=color;resolved=skin}
                    Text("浅色界面")
                }
            }
        }
        for(skin in listOf("prism","linear","claude","supabase","miro","azure","spectrum","unknown"))for(mode in listOf("dark","system")) {
            compose.runOnIdle {data=AppData(settings=mapOf("uiSkin" to skin,"theme" to mode))}
            compose.waitForIdle()
            assertEquals(if(skin in listOf("prism","linear","claude","supabase","miro"))skin else "prism",resolved)
            assertEquals(when(skin) {
                "linear"->Color(0xFFF5F6F8); "claude"->Color(0xFFFAF9F5)
                "supabase"->Color(0xFF1C1C1C); "miro"->Color.White; else->Color(0xFFEAEBF3)
            },background)
        }
    }
}

class UiSkinNavigationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val vm get()=ViewModelProvider(compose.activity)[AppViewModel::class.java]
    @Test fun everyStructuredSkinKeepsPagesDialogsAndDataAcrossRecreation() {
        val outbound="""{"type":"socks","server":"127.0.0.1","server_port":9}"""
        val original=AppData(nodes=listOf(Node(1,1,"保留节点",outbound)),groups=listOf(Group(1,"保留分组")),
            settings=mapOf("appLanguage" to "zh-CN","selectedNodeId" to "1","browseGroupId" to "1"))
        compose.runOnIdle {vm.edit {original}}
        compose.waitUntil(15000){!vm.busy.value && vm.data.value.nodes.size==1}
        for(skin in listOf("linear","claude","supabase","miro")) {
            compose.runOnIdle {vm.setting("uiSkin",skin)}
            compose.waitUntil(10000){vm.data.value.setting("uiSkin")==skin && !vm.busy.value}
            compose.onNodeWithTag("white_connection_card").assertIsDisplayed()
            compose.onNodeWithTag("home_toolbar").assertDoesNotExist()
            compose.onNodeWithTag("node_1").assertIsDisplayed()
            compose.onNodeWithTag("node_info_1").performClick()
            compose.onNodeWithText("节点详情").assertIsDisplayed()
            compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithTag("tab_1").performClick()
            compose.onNodeWithTag("smart_rules").assertIsDisplayed()
            compose.onNodeWithTag("tab_2").performClick()
            compose.onNodeWithTag("settings_general").performClick()
            compose.onNodeWithTag("setting_launcherIcon").assertDoesNotExist()
            compose.onNodeWithTag("setting_uiSkin").performClick()
            for((id,name) in uiSkinChoices) {
                compose.onNodeWithTag("choice_$id").assertIsDisplayed().assertTextContains(name)
            }
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithTag("page_back").performClick()
            compose.onNodeWithTag("home_tab").performClick()
            compose.runOnUiThread {compose.activity.recreate()}
            compose.waitUntil(10000){compose.onAllNodesWithTag("white_connection_card").fetchSemanticsNodes().isNotEmpty()}
            assertEquals(skin,vm.store.snapshot().setting("uiSkin"))
            assertEquals(original.nodes,vm.data.value.nodes)
            assertEquals(original.groups,vm.data.value.groups)
        }
    }
    @Test fun switchingUiKeepsDataAndWhiteUiRetainsNavigationSearchMenusAndSelection() {
        val outbound="""{"type":"socks","server":"127.0.0.1","server_port":9}"""
        val original=AppData(
            nodes=(1L..8L).map{Node(it,if(it<=4)1 else 2,"验收节点 $it",outbound,order=it.toInt())},
            groups=listOf(Group(1,"分组一"),Group(2,"分组二")),
            rules=listOf(RouteRule(1,"保留规则",domains="example.test",outbound="direct")),
            merges=listOf(MergeGroup(1,"保留汇总",nodeIds=listOf(1,2))),
            settings=mapOf("appLanguage" to "zh-CN","selectedNodeId" to "1","browseGroupId" to "1","uiSkin" to "prism","theme" to "dark"))
        compose.runOnIdle {vm.edit{original}}
        compose.waitUntil(15000){!vm.busy.value && vm.data.value.nodes.size==8}
        compose.onNodeWithTag("home_toolbar").assertIsDisplayed()
        compose.onNodeWithTag("tab_2").performClick()
        compose.onNodeWithTag("settings_general").performClick()
        compose.onNodeWithTag("setting_theme").assertDoesNotExist()
        compose.onNodeWithTag("setting_uiSkin").performClick()
        compose.onNodeWithText("天青留白").assertDoesNotExist()
        compose.onNodeWithText("光谱水晶").assertDoesNotExist()
        compose.onNodeWithText("简洁留白").performClick()
        compose.waitUntil(10000){vm.data.value.setting("uiSkin")=="linear"}
        compose.onNodeWithTag("page_back").performClick()
        compose.onNodeWithTag("home_tab").performClick()
        compose.onNodeWithTag("white_connection_card").assertIsDisplayed()
        compose.onNodeWithTag("home_toolbar").assertDoesNotExist()
        compose.onNodeWithTag("connect_toggle").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("node_2").performClick()
        compose.waitUntil(10000){vm.data.value.selectedNodeId==2L}
        compose.onNodeWithTag("home_selected_node").assertTextContains("验收节点 2",substring=true)
        compose.onNodeWithTag("search_toggle").performClick()
        compose.onNodeWithTag("node_search").performTextInput("不存在的节点")
        compose.onNodeWithText("未找到匹配节点").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭搜索").performClick()
        compose.onNodeWithTag("node_info_2").performClick()
        compose.onNodeWithText("节点详情").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("group_2").performClick()
        compose.waitUntil(10000){vm.data.value.browseGroupId==2L}
        compose.onNodeWithTag("node_5").assertIsDisplayed()
        compose.onNodeWithTag("tab_1").performClick()
        compose.onNodeWithTag("smart_rules").assertIsDisplayed()
        compose.onNodeWithTag("tab_2").performClick()
        compose.onNodeWithTag("settings_groups").performClick()
        compose.onNodeWithTag("manage_group_1").assertIsDisplayed()
        compose.onNodeWithTag("page_back").performClick()
        compose.onNodeWithTag("home_tab").performClick()
        compose.runOnUiThread {compose.activity.recreate()}
        compose.waitUntil(10000){compose.onAllNodesWithTag("white_connection_card").fetchSemanticsNodes().isNotEmpty()}
        assertEquals("linear",vm.store.snapshot().setting("uiSkin"))
        assertEquals(original.nodes,vm.data.value.nodes)
        assertEquals(original.groups,vm.data.value.groups)
        assertEquals(original.rules,vm.data.value.rules)
        assertEquals(original.merges,vm.data.value.merges)
        compose.runOnIdle {vm.setting("showBottomBar","false")}
        compose.waitUntil(10000){!vm.data.value.bool("showBottomBar")}
        compose.onNodeWithTag("home_tab").assertDoesNotExist()
        compose.onNodeWithTag("connect_toggle").assertIsDisplayed()
        compose.onNodeWithTag("node_menu").performClick()
        compose.onNodeWithText("设置").performScrollTo().performClick()
        compose.onNodeWithTag("settings_general").performClick()
        compose.onNodeWithTag("setting_uiSkin").performClick()
        compose.onNodeWithText("棱镜流光").performClick()
        compose.waitUntil(10000){vm.data.value.setting("uiSkin")=="prism"}
        compose.onNodeWithTag("page_back").performClick()
        compose.onNodeWithTag("main_back").performClick()
        compose.runOnIdle {vm.setting("showBottomBar","true")}
        compose.waitUntil(10000){vm.data.value.bool("showBottomBar")}
        compose.onNodeWithTag("home_toolbar").assertIsDisplayed()
        assertEquals(2L,vm.data.value.selectedNodeId)
    }
}
