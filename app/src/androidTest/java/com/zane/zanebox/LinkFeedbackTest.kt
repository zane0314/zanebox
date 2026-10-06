package com.zane.zanebox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.zane.zanebox.data.*
import com.zane.zanebox.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.launch

class LinkFeedbackTest {
    @get:Rule(order=0) val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=1) val failureEvidence=object:org.junit.rules.TestWatcher() {
        override fun failed(error:Throwable,description:org.junit.runner.Description) {
            val ins=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val bitmap=ins.uiAutomation.takeScreenshot() ?:return
            val file=java.io.File(ins.targetContext.getExternalFilesDir(null),"links-failure-${description.methodName}.png")
            file.outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
    }
    @org.junit.After fun clearFixtureClipboard() {
        compose.runOnIdle {
            val clipboard=compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if(android.os.Build.VERSION.SDK_INT>=28)clipboard.clearPrimaryClip()
            else clipboard.setPrimaryClip(ClipData.newPlainText("fixture",""))
        }
    }
    private fun seed():AppViewModel {
        val vm=ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnIdle { vm.edit { AppData(nodes=listOf(Node(801,801,"日本 B","""{"type":"socks","server":"10.0.2.2","server_port":19082}""")),groups=listOf(Group(801,"验收组")),settings=mapOf("appLanguage" to "zh-CN","selectedNodeId" to "801","browseGroupId" to "801","serviceMode" to "proxy","exitProbeUrl" to "http://10.0.2.2:19080/trace")) } }
        compose.waitUntil(10000){!vm.busy.value && vm.data.value.nodes.firstOrNull()?.id==801L}
        return vm
    }
    @Test fun builtinCompatibilityAndCustomRuleDisclosureKeepWorkingRoutes() {
        val vm=seed()
        val raw=compose.activity.assets.open("anybox-rules/YouTube.list").bufferedReader().use{it.readText()}
        compose.runOnIdle {vm.edit {it.copy(nodes=it.nodes+Node(802,801,"规则目标 A","""{"type":"socks","server":"10.0.2.2","server_port":19081}"""),settings=it.settings+mapOf("smart.youtube.target" to "node:802","smart.speed.target" to "off","smartRules.youtube" to raw,"dnsRemote" to "tcp://10.0.2.2:19087","dnsDirect" to "tcp://10.0.2.2:19087","sniff" to "false"))}}
        compose.waitUntil(10000){!vm.busy.value && vm.data.value.setting("smartRules.youtube")==raw}
        compose.onNodeWithTag("tab_1").performClick();compose.onNodeWithTag("smart_youtube").performClick()
        compose.onNodeWithText("内置兼容规则组").assertIsDisplayed()
        compose.onNodeWithText("规则来源").performClick()
        val editor=compose.onNodeWithTag("rule_source_rules")
        editor.assertTextContains("DOMAIN-SUFFIX,googlevideo.com",substring=true)
        assertFalse(editor.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text.contains("USER-AGENT,"))
        compose.onNodeWithTag("rule_source_compatibility").assertDoesNotExist()
        val custom="DOMAIN-SUFFIX,googlevideo.com\nUSER-AGENT,*user-custom*"
        editor.performTextReplacement(custom)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("subpage_list").performScrollToNode(hasTestTag("rule_source_compatibility"))
        compose.onNodeWithTag("rule_source_compatibility").assertIsDisplayed().assertTextContains("USER-AGENT",substring=true)
        compose.onNodeWithTag("subpage_list").performScrollToNode(hasTestTag("rule_source_save"))
        compose.onNodeWithTag("rule_source_save").performClick()
        compose.waitUntil(10000){!vm.busy.value && vm.data.value.setting("smartRules.youtube")==custom}
        assertEquals(custom,vm.store.snapshot().setting("smartRules.youtube"))
        kotlinx.coroutines.runBlocking {kotlinx.coroutines.withTimeout(30000) {
            while(!java.io.File(compose.activity.filesDir,"core-assets/yacd/index.html").isFile)kotlinx.coroutines.delay(50)
            vm.service.checkConfig(com.zane.zanebox.config.ConfigBuilder.build(vm.data.value))
        }}
        compose.onNodeWithTag("page_back").performClick();compose.onNodeWithTag("main_back").performClick()
        val messages=java.util.concurrent.CopyOnWriteArrayList<String>()
        val collector=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {vm.service.events.collect{messages.add(it)}}
        try {
            vm.service.start()
            compose.waitUntil(30000){vm.service.snapshot.value.state==2 || vm.service.snapshot.value.state==4}
            assertEquals(vm.service.snapshot.value.error,2,vm.service.snapshot.value.state)
            assertTrue("不应在每次连接时弹规则兼容消息：$messages",messages.none{it.contains("USER-AGENT")})
            listOf("googlevideo.com" to "EXIT_A","unmatched.test" to "EXIT_B").forEach {(host,expected)->
                val request=java.net.URL("http://$host:19080/probe").openConnection(java.net.Proxy(java.net.Proxy.Type.HTTP,java.net.InetSocketAddress("127.0.0.1",2080))) as java.net.HttpURLConnection
                try {request.connectTimeout=5000;request.readTimeout=5000;assertEquals(expected,request.inputStream.bufferedReader().use{it.readText()})} finally {request.disconnect()}
            }
        } finally {vm.service.stop();collector.cancel()}
    }
    @Test fun settingsEndIsFullyVisibleAtNormalAndLargeFontAndBackupStaysInTools() {
        val vm=seed()
        listOf("1.0","2.0").forEach { scale->
            compose.runOnIdle {vm.setting("fontScale",scale)}
            compose.waitUntil(10000){vm.data.value.setting("fontScale")==scale}
            val labels=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText("智能分流",useUnmergedTree=true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){it(labels)}
            val label=labels.single()
            assertEquals(1,label.lineCount);assertFalse(label.isLineEllipsized(0))
            assertEquals(label.layoutInput.text.length,label.getLineEnd(0))
            assertTrue("$scale 底栏标签溢出",label.getLineRight(0)-label.getLineLeft(0)<=label.size.width+1f)
            compose.onNodeWithTag("tab_2").performClick();compose.onNodeWithTag("settings_general").performClick()
            compose.onNodeWithTag("setting_appTheme").assertDoesNotExist()
            compose.onNodeWithTag("subpage_list").performScrollToNode(hasTestTag("factory_reset"))
            repeat(2){compose.onNodeWithTag("subpage_list").performTouchInput {swipeUp()}}
            val row=compose.onNodeWithTag("factory_reset").getUnclippedBoundsInRoot()
            val list=compose.onNodeWithTag("subpage_list").getUnclippedBoundsInRoot()
            assertTrue("$scale row=$row list=$list",row.bottom<=list.bottom+androidx.compose.ui.unit.Dp(1f))
            compose.onNodeWithText("备份与恢复").assertDoesNotExist()
            compose.onNodeWithTag("factory_reset").performTouchInput {click(Offset(width-8f,center.y))}
            compose.onNodeWithText("确认操作").assertIsDisplayed();compose.onNodeWithText("取消").performClick()
            compose.onNodeWithTag("page_back").performClick();compose.onNodeWithTag("main_back").performClick()
        }
        compose.runOnIdle {vm.setting("fontScale","1.0")};compose.waitUntil(10000){vm.data.value.setting("fontScale")=="1.0"}
        compose.onNodeWithTag("tab_2").performClick();compose.onNodeWithTag("network_tools").performClick();compose.onNodeWithTag("tools_backup_tab").performClick()
        compose.onNodeWithText("本地备份").assertIsDisplayed()
    }
    @Test fun outerAppSwitchOnlyReflectsTheInnerThreeModes() {
        val vm=seed();compose.onNodeWithTag("tab_2").performClick();compose.onNodeWithTag("settings_general").performClick()
        compose.onNodeWithTag("subpage_list").performScrollToNode(hasTestTag("setting_perAppEnabled"))
        compose.onNodeWithTag("setting_perAppMode").assertDoesNotExist()
        compose.onNodeWithTag("per_app_indicator",useUnmergedTree=true).assertHasNoClickAction().assertIsOff()
        compose.onNodeWithTag("per_app_indicator",useUnmergedTree=true).performTouchInput {click()}
        compose.onNodeWithTag("apps_mode_include").performClick();compose.onNodeWithTag("page_back").performClick()
        compose.waitUntil(10000){vm.data.value.bool("perAppEnabled") && vm.data.value.setting("perAppMode")=="include"}
        compose.onNodeWithTag("per_app_indicator",useUnmergedTree=true).assertIsOn()
        compose.onNodeWithTag("setting_perAppEnabled").performClick();compose.onNodeWithTag("apps_mode_exclude").performClick();compose.onNodeWithTag("page_back").performClick()
        compose.waitUntil(10000){vm.data.value.bool("perAppEnabled") && vm.data.value.setting("perAppMode")=="exclude"}
        compose.onNodeWithTag("per_app_indicator",useUnmergedTree=true).assertIsOn()
        compose.onNodeWithTag("setting_perAppEnabled").performClick();compose.onNodeWithTag("apps_mode_off").performClick();compose.onNodeWithTag("page_back").performClick()
        compose.waitUntil(10000){!vm.data.value.bool("perAppEnabled")}
        compose.onNodeWithTag("per_app_indicator",useUnmergedTree=true).assertIsOff()
    }
    @Test fun factoryRulesAreVisibleAndNativeAcceptsEnabledTemplates() {
        val vm=seed()
        compose.runOnIdle{vm.edit{com.zane.zanebox.config.withFactoryRouteDefaults(it,"CN")}}
        compose.waitUntil(10000){vm.data.value.rules.size==5 && !vm.busy.value}
        assertTrue(vm.data.value.rules.none{it.enabled})
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(30000) {
            while(!java.io.File(compose.activity.filesDir,"core-assets/yacd/index.html").isFile)kotlinx.coroutines.delay(50)
            vm.service.checkConfig(com.zane.zanebox.config.ConfigBuilder.build(vm.data.value.copy(rules=vm.data.value.rules.map{it.copy(enabled=true)})))
        } }
        compose.onNodeWithTag("tab_1").performClick();compose.onNodeWithTag("smart_rules").performClick()
        compose.onNodeWithText("前置路由规则").assertIsDisplayed();compose.onNodeWithText("后置路由规则").assertIsDisplayed()
        val firstRule=vm.data.value.rules.first().id
        compose.onNodeWithTag("rule_$firstRule").performClick()
        compose.onNodeWithTag("rule_position").performClick();compose.onNode(hasText("前置 · 在应用策略之前") and !hasTestTag("rule_position")).assertIsDisplayed();compose.onNodeWithText("后置 · 在应用策略之后").performClick()
        compose.onNodeWithTag("rule_save").performClick();compose.waitUntil(10000){!vm.data.value.rules.first{it.id==firstRule}.prioritize}
    }
    @Test fun longPressMovesPolicyAndPersistsTheActualOrder() {
        val vm=seed();compose.onNodeWithTag("tab_1").performClick()
        compose.onNodeWithTag("smart_speed").performTouchInput{longClick()}
        compose.onNodeWithTag("smart_move_down").performClick()
        compose.waitUntil(10000){com.zane.zanebox.config.smartPolicyKeys(vm.data.value).take(2)==listOf("youtube","speed")}
        assertTrue(compose.onNodeWithTag("smart_youtube").fetchSemanticsNode().boundsInRoot.top<compose.onNodeWithTag("smart_speed").fetchSemanticsNode().boundsInRoot.top)
        assertEquals("youtube",vm.store.snapshot().setting("smartPolicyOrder").lineSequence().first())
        compose.onNodeWithTag("smart_speed").performTouchInput{longClick()}
        compose.onNodeWithTag("smart_move_up").performClick()
        compose.waitUntil(10000){com.zane.zanebox.config.smartPolicyKeys(vm.data.value).first()=="speed"}
    }
    @Test fun clipboardUrlsReachSubscriptionImportAndDownloadActualNodes() {
        val vm=seed()
        val clipboard=compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("fixture","http://10.0.2.2:19080/subscription")) }
        compose.onNodeWithTag("add_nodes").performClick();compose.onNodeWithText("从剪贴板导入").performClick()
        compose.onNodeWithText("添加外部订阅？").assertIsDisplayed()
        compose.onNodeWithTag("import_confirm").performClick()
        compose.waitUntil(20000){vm.data.value.groups.any{it.subscriptionUrl=="http://10.0.2.2:19080/subscription" && it.updatedAt>0}}
        assertEquals(2,vm.data.value.nodes.count{it.groupId!=801L})
    }
    @Test fun builtinPoliciesOfferAppsDirectAndBundledRules() {
        val vm=seed();compose.onNodeWithTag("tab_1").performClick()
        compose.onNodeWithTag("smart_youtube").performClick()
        compose.onNodeWithTag("page_list").performScrollToNode(hasTestTag("smart_apps_youtube"))
        val appRow=compose.onNodeWithTag("smart_apps_youtube").getUnclippedBoundsInRoot()
        val viewport=compose.onNodeWithTag("page_list").getUnclippedBoundsInRoot()
        val delta=((appRow.top+appRow.bottom-viewport.top-viewport.bottom)/2).value*compose.activity.resources.displayMetrics.density
        compose.onNodeWithTag("page_list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy){it(0f,delta)}
        compose.onNodeWithTag("smart_apps_youtube").assertIsDisplayed().performClick()
        compose.waitUntil(5000){compose.onNodeWithTag("apps_save").isDisplayed()}
        compose.onNodeWithTag("apps_save").assertIsDisplayed();compose.onNodeWithTag("page_back").performClick()
        compose.onNodeWithTag("smart_target_youtube").performClick();compose.onNodeWithText("直连").performClick()
        compose.waitUntil(10000){vm.data.value.setting("smart.youtube.target")=="direct"}
        assertTrue(vm.data.value.setting("smartRules.youtube").contains("youtube.com"))
    }
    @Test fun chainGroupProxyAndSpeedPolicyEntriesAreAccessible() {
        seed();compose.onNodeWithTag("add_nodes").performClick();compose.onNodeWithText("创建链式代理").performClick()
        compose.onNodeWithText("链式节点 · 从出口到前置选择").assertIsDisplayed();compose.onNodeWithTag("page_back").performClick()
        compose.onNodeWithTag("group_801").performTouchInput{longClick()};compose.onNodeWithText("前置代理").performClick();compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("group_801").performTouchInput{longClick()};compose.onNodeWithText("后置代理（落地）").performClick();compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("tab_1").performClick();compose.onNodeWithTag("smart_speed").assertIsDisplayed()
        compose.onNodeWithTag("smart_target_speed").performClick();compose.onNodeWithText("直连").assertIsDisplayed();compose.onNodeWithText("取消").performClick()

    }
}
