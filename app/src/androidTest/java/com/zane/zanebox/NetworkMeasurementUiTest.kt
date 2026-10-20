package com.zane.zanebox

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.zane.zanebox.data.*
import com.zane.zanebox.ui.AppViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NetworkMeasurementUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val vm get()=ViewModelProvider(compose.activity)[AppViewModel::class.java]

    @Test fun measurementSwitchesPersistAndApplyToRunningService() {
        val data=AppData(nodes=listOf(Node(1,1,"fixture","""{"type":"socks","server":"10.0.2.2","server_port":19081}""")),groups=listOf(Group(1,"fixture")),settings=mapOf("appLanguage" to "zh-CN","serviceMode" to "proxy","statsEnabled" to "false","selectedNodeId" to "1","selectedGroupId" to "1","rulesUpdateInterval" to "off","dnsRemote" to "local"))
        compose.runOnIdle {vm.edit(autoApply=false){data}}
        compose.waitUntil(15000){!vm.busy.value && vm.data.value.nodes.size==1}
        compose.runOnIdle {vm.service.start()}
        compose.waitUntil(15000){vm.service.snapshot.value.state==2}
        try {
            if(compose.onAllNodesWithTag("tab_2").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithTag("tab_2").performClick()
            if(compose.onAllNodesWithTag("settings_general").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithTag("settings_general").performClick()
            for(key in listOf("unifiedDelay","tcpConcurrent")) {
                compose.onNodeWithTag("subpage_list").performScrollToNode(hasTestTag("setting_$key"))
                compose.onNodeWithTag("setting_$key").performScrollTo().assertIsDisplayed()
                assertTrue("新设置默认开启 $key",vm.data.value.bool(key))
                val generation=vm.service.snapshot.value.generation
                compose.onNodeWithTag("setting_$key").performClick()
                compose.waitUntil(15000){vm.data.value.setting(key)=="false" && vm.service.snapshot.value.state==2 && vm.service.snapshot.value.generation>generation}
                assertFalse(vm.store.snapshot().bool(key))
            }
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10000){!vm.busy.value && vm.data.value.setting("unifiedDelay")=="false" && vm.data.value.setting("tcpConcurrent")=="false"}
            if(compose.onAllNodesWithTag("tab_2").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithTag("tab_2").performClick()
            if(compose.onAllNodesWithTag("settings_general").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithTag("settings_general").performClick()
            for(key in listOf("unifiedDelay","tcpConcurrent")) {
                compose.onNodeWithTag("subpage_list").performScrollToNode(hasTestTag("setting_$key"))
                compose.onNodeWithTag("setting_$key").performScrollTo().performClick()
                compose.waitUntil(15000){vm.data.value.setting(key)=="true" && vm.service.snapshot.value.state==2}
            }
        } finally {compose.runOnIdle {vm.service.stop()}}
    }
}
