package com.zane.zanebox

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.zane.zanebox.ui.AppViewModel
import com.zane.zanebox.data.*
import org.junit.Assert.*
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

class FunctionFeedbackTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Test fun webdavFailureIsVisibleInThePageThatRequestedIt() {
        val vm=ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnIdle { vm.setting("appLanguage","zh-CN") }
        compose.waitUntil(10000) { vm.data.value.setting("appLanguage")=="zh-CN" }
        compose.runOnIdle { vm.message.value="" }
        compose.onNodeWithTag("tab_2").performClick()
        compose.onNodeWithTag("network_tools").performClick()
        compose.onNodeWithTag("tools_backup_tab").performClick()
        compose.onNodeWithTag("subpage_list").performScrollToNode(hasText("从 WebDAV 恢复"))
        compose.onNodeWithText("从 WebDAV 恢复").performClick()
        val feedback=hasText("WebDAV地址无效") and hasAnyAncestor(hasTestTag("page_feedback_WebDAV 备份"))
        compose.waitUntil(10000) { compose.onNode(feedback).isDisplayed() }
        compose.onNode(feedback).assertIsDisplayed()
        compose.onNodeWithTag("webdav_refresh").performClick()
        compose.waitUntil(10000) { compose.onNode(feedback).isDisplayed() }
        compose.onNode(feedback).assertIsDisplayed()
    }

    private fun seed(data:AppData):AppViewModel {
        val vm=ViewModelProvider(compose.activity)[AppViewModel::class.java]
        compose.runOnIdle { vm.edit { data.copy(settings=data.settings+("appLanguage" to "zh-CN")) } }
        compose.waitUntil(10000) { !vm.busy.value && vm.data.value.nodes.map { it.id }==data.nodes.map { it.id } && vm.data.value.setting("appLanguage")=="zh-CN" }
        return vm
    }
    @Test fun emptyUpdateActionsExplainWhyNothingCanBeUpdated() {
        val vm=seed(AppData())
        val clipboard=compose.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        compose.runOnIdle { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("empty-fixture","")) }
        compose.onNodeWithTag("add_nodes").performClick();compose.onNodeWithText("从剪贴板导入").performClick()
        compose.onNodeWithText("没有可导入的内容").assertIsDisplayed()
        compose.onNodeWithTag("node_menu").performClick()
        compose.onNodeWithText("更新当前订阅").performClick()
        compose.onNodeWithText("请先选择订阅分组").assertIsDisplayed()
        compose.onNodeWithTag("node_menu").performClick()
        compose.onNodeWithText("全部更新（全部订阅）").performClick()
        compose.onNodeWithText("没有可更新的订阅").assertIsDisplayed()
        compose.onNodeWithTag("tab_1").performClick()
        compose.onNodeWithTag("smart_update").performClick()
        compose.onNodeWithText("没有配置远程规则源").assertIsDisplayed()
        assertTrue(vm.data.value.nodes.isEmpty())
    }
    @Test fun failedLatencyTestUpdatesTheOpenNodeDetailsAndHomeRow() {
        val vm=seed(AppData(nodes=listOf(Node(401,401,"失败节点","""{"type":"socks","server":"127.0.0.1","server_port":9}""")),groups=listOf(Group(401,"本地测试")),settings=mapOf("serviceMode" to "proxy","testUrl" to "http://127.0.0.1:9/test","testTimeout" to "1000","testConcurrency" to "legacy-invalid")))
        compose.onNodeWithTag("node_info_401").performClick()
        compose.onNodeWithText("测试延迟").performClick()
        compose.waitUntil(15000) { vm.data.value.nodes.single().status==1 && vm.service.testingNodes.value.isEmpty() }
        compose.onNodeWithTag("node_info_list").performScrollToNode(hasText("失败"))
        compose.onNode(hasText("失败") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("node_latency_401").assertTextEquals("失败")
    }
    @Test fun nativeValidationRejectsBrokenNodeAndKeepsTheEditorUntilCorrected() {
        val node=Node(402,402,"校验节点","""{"type":"vless","server":"example.test","server_port":443}""")
        val vm=seed(AppData(nodes=listOf(node),groups=listOf(Group(402,"本地校验")),settings=mapOf("serviceMode" to "proxy")))
        compose.onNodeWithTag("node_menu_402").performClick()
        compose.onNodeWithText("编辑").performClick()
        compose.onNodeWithTag("node_save").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("node_error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("node_error").assertIsDisplayed()
        assertEquals(node.outbound,vm.data.value.nodes.single().outbound)
        compose.onNodeWithTag("subpage_list").performScrollToNode(hasTestTag("node_field_uuid"))
        compose.onNodeWithTag("node_field_uuid").performClick()
        compose.onNodeWithTag("editor_field_0").performTextReplacement("11111111-1111-4111-8111-111111111111")
        compose.onNodeWithTag("editor_save").performClick()
        compose.onNodeWithTag("node_save").performClick()
        compose.waitUntil(15000) { JSONObject(vm.data.value.nodes.single().outbound).optString("uuid").isNotBlank() && !vm.busy.value }
        compose.onNodeWithTag("node_save").assertDoesNotExist()
        compose.onNodeWithTag("node_402").assertIsDisplayed()
    }
    @Test fun speedSetupFailureProducesAResultAndCancelClearsRunningState() {
        val vm=seed(AppData(nodes=listOf(Node(403,403,"速度错误","""{"type":"socks","server":"127.0.0.1","server_port":9}""")),groups=listOf(Group(403,"测速")),settings=mapOf("serviceMode" to "proxy","speedTimeout" to "invalid")))
        compose.onNodeWithTag("node_menu_403").performClick()
        compose.onNodeWithText("速度测试").performClick()
        compose.onNodeWithTag("speed_simple").performClick()
        compose.waitUntil(15000) { runCatching { JSONObject(vm.service.speedResult.value).optBoolean("done") }.getOrDefault(false) }
        assertTrue(JSONObject(vm.service.speedResult.value).getString("error").isNotBlank())
        compose.onNodeWithTag("speed_result").assertIsDisplayed()
        compose.onNodeWithTag("speed_cancel").performClick()
        compose.waitUntil(10000) { runCatching { JSONObject(vm.service.speedResult.value).optString("stage")=="cancelled" }.getOrDefault(false) }
        assertTrue(JSONObject(vm.service.speedResult.value).getBoolean("done"))
    }
    @Test fun connectLabelStartsAndStopsTheActualProxyListener() {
        val vm=seed(AppData(nodes=listOf(Node(404,404,"标签连接","""{"type":"socks","server":"127.0.0.1","server_port":9}""")),groups=listOf(Group(404,"连接")),settings=mapOf("serviceMode" to "proxy")))
        compose.onNodeWithTag("tab_0").performClick()
        compose.waitUntil(30000) { vm.service.snapshot.value.state==2 }
        java.net.Socket("127.0.0.1",2080).use { assertTrue(it.isConnected) }
        compose.onNodeWithTag("tab_0").performClick()
        compose.waitUntil(10000) { vm.service.snapshot.value.state==0 }
    }
    @Test fun routingProbeRequiresAConnectedProxy() {
        seed(AppData())
        compose.onNodeWithTag("tab_1").performClick()
        compose.onNodeWithTag("smart_menu").performClick()
        compose.onNodeWithText("分流检测").performClick()
        compose.onNodeWithTag("routing_probe_domain").performTextReplacement("example.test")
        compose.onNodeWithTag("routing_probe_start").performClick()
        compose.onNodeWithTag("routing_probe_error").assertTextEquals("请先连接代理后再检测分流")
    }
    @Test fun groupCopyRouteResetAndAutomaticAppSelectionChangeTheActualData() {
        val group=Group(501,"分享分组","https://example.test/sub?key=a+b")
        val vm=seed(AppData(nodes=listOf(Node(501,501,"本组","""{"type":"socks","server":"example.test","server_port":1080}""")),groups=listOf(group),rules=listOf(RouteRule(502,"重置测试",domains="example.test",outbound="direct")),settings=mapOf("serviceMode" to "proxy")))
        compose.onNodeWithTag("tab_2").performClick();compose.onNodeWithTag("settings_groups").performClick()
        compose.onNodeWithTag("manage_group_menu_501").performClick();compose.onNodeWithText("复制订阅链接").performClick()
        val clipboard=compose.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val copied=IncomingLink.parse(clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(group.subscriptionUrl,copied.subscriptionUrl);assertEquals(group.name,copied.name)
        compose.onNodeWithTag("manage_group_menu_501").performClick();compose.onNodeWithText("订阅二维码").performClick()
        compose.onNodeWithText("节点二维码").assertIsDisplayed();compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("page_back").performClick();compose.onNodeWithTag("main_back").performClick()
        compose.onNodeWithTag("tab_1").performClick();compose.onNodeWithTag("smart_rules").performClick()
        compose.onNodeWithTag("rules_menu").performClick();compose.onNodeWithText("重置全部规则").performClick()
        compose.onNodeWithText("取消").performClick();assertEquals(1,vm.data.value.rules.size)
        compose.onNodeWithTag("rules_menu").performClick();compose.onNodeWithText("重置全部规则").performClick();compose.onNodeWithText("确认").performClick()
        compose.waitUntil(10000) { vm.data.value.rules.isEmpty() }
        compose.onNodeWithTag("page_back").performClick();compose.onNodeWithTag("main_back").performClick()
        compose.onNodeWithTag("tab_2").performClick();compose.onNodeWithTag("settings_general").performClick()
        compose.onNodeWithTag("subpage_list").performScrollToNode(hasTestTag("select_apps"));compose.onNodeWithTag("select_apps").performClick()
        compose.waitUntil(10000) { compose.onNodeWithTag("apps_auto").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) }
        compose.onNodeWithTag("apps_mode_include").performClick()
        compose.onNodeWithTag("apps_auto").performClick();compose.onNodeWithTag("apps_auto_confirm").performClick()
        val presets=compose.activity.assets.open("proxy_packagename.txt").bufferedReader().use { it.readLines() }.map { it.trim() }.toSet()
        val installed=compose.activity.packageManager.getInstalledApplications(0)
        val expected=installed.filter { it.packageName in presets || it.uid==1000 }.map { it.packageName }.toSet()
        assertTrue("模拟器必须有内置名单中的合成验收应用",expected.isNotEmpty())
        compose.onNodeWithTag("apps_save").performClick()
        compose.waitUntil(10000) { vm.data.value.setting("perAppPackages").lines().filter { it.isNotBlank() }.toSet()==expected }
        assertEquals(expected,vm.store.snapshot().setting("perAppPackages").lines().toSet())
        compose.onNodeWithTag("subpage_list").performScrollToNode(hasTestTag("select_apps"))
        compose.onNodeWithTag("select_apps").performClick();compose.onNodeWithTag("apps_mode_exclude").performClick()
        compose.onNodeWithTag("apps_auto").performClick();compose.onNodeWithTag("apps_auto_confirm").performClick();compose.onNodeWithTag("apps_save").performClick()
        val bypass=installed.filter { it.packageName !in presets && it.uid!=1000 }.map { it.packageName }.toSet()
        compose.waitUntil(10000) { vm.data.value.setting("perAppPackages").lines().filter { it.isNotBlank() }.toSet()==bypass }
        assertEquals("exclude",vm.data.value.setting("perAppMode"))
    }
    @Test fun everySupportedProtocolEditorChangesAndSavesThroughNativeValidation() {
        val context=compose.activity
        val fixture=AppData.fromJson(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("native-configs/synthetic-appdata.json").bufferedReader().use { it.readText() })
        val uuid="11111111-1111-4111-8111-111111111111"
        fun base(type:String)=JSONObject().put("type",type).put("server","example.test").put("server_port",443)
        val simple=listOf(
            base("socks"),base("http"),base("shadowsocks").put("method","aes-128-gcm").put("password","fixture"),
            base("vmess").put("uuid",uuid).put("security","auto"),base("vless").put("uuid",uuid),
            base("trojan").put("password","fixture").put("tls",JSONObject().put("enabled",true).put("server_name","example.test")),
            base("hysteria").put("auth_str","fixture").put("up_mbps",100).put("down_mbps",100).put("tls",JSONObject().put("enabled",true))
        ).mapIndexed { index,out->Node((index+1).toLong(),1,"编辑器 ${out.getString("type")}",out.toString(),order=index) }
        val trojanGo=simple[5].copy(id=20,order=17,name="编辑器 trojan-go",metadata="""{"editorProtocol":"trojan-go"}""")
        val nodes=simple+fixture.nodes.mapIndexed { index,node->node.copy(groupId=1,order=simple.size+index) }+trojanGo
        val vm=seed(AppData(nodes=nodes,groups=listOf(Group(1,"协议编辑回归")),settings=mapOf("serviceMode" to "proxy")))
        val result=org.json.JSONArray()
        try {
            nodes.forEachIndexed { index,node->
                compose.onNodeWithTag("page_list").performScrollToIndex(index+3)
                compose.onNodeWithTag("node_menu_${node.id}").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() };compose.onNodeWithText("编辑").performClick()
                val name=node.name+" · 已验证"
                compose.onNodeWithTag("node_name").performTextReplacement(name)
                val credentials=JSONObject(node.outbound).optString("type") in listOf("socks","http","shadowsocks","trojan","hysteria2","tuic","anytls","ssh","juicity","shadowtls")
                if(credentials) {
                    compose.onNodeWithTag("subpage_list").performScrollToNode(hasTestTag("node_field_password"))
                    compose.onNodeWithTag("node_field_password").performClick()
                    compose.onNodeWithTag("editor_field_0").performTextReplacement("fixture-updated")
                    compose.onNodeWithTag("editor_save").performClick()
                }
                compose.onNodeWithTag("node_save").performClick()
                try { compose.waitUntil(15000) { vm.data.value.nodes.first { it.id==node.id }.name==name && !vm.busy.value } }
                catch(e:Throwable) {
                    compose.onNodeWithTag("subpage_list").performScrollToIndex(0)
                    val errors=compose.onAllNodesWithTag("node_error").fetchSemanticsNodes().joinToString { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text){emptyList()}.joinToString { text->text.text } }
                    java.io.File(context.getExternalFilesDir(null),"protocol-editor-failure.txt").writeText("${node.id}: $errors")
                    throw AssertionError("协议 ${node.id} 保存失败：$errors",e)
                }
                val saved=vm.store.snapshot().nodes.first { it.id==node.id }
                assertEquals(name,saved.name)
                if(credentials)assertEquals("fixture-updated",JSONObject(saved.outbound).getString("password"))
                compose.onNodeWithTag("node_save").assertDoesNotExist()
                result.put(JSONObject().put("id",node.id).put("type",if(node.id==20L)"trojan-go" else JSONObject(node.outbound).getString("type")).put("nativeValidatedAndSaved",true).put("passwordEdited",credentials))
            }
            assertEquals(18,result.length())
        } finally { java.io.File(context.getExternalFilesDir(null),"protocol-editor-result.json").writeText(result.toString(2)) }
    }
    @Test fun automaticReconnectHonorsThePriorConnectionAndSupportedBroadcasts() {
        val vm=seed(AppData(settings=mapOf("serviceMode" to "proxy","autoStart" to "true")))
        val activity=compose.activity
        val prefs=activity.getSharedPreferences("runtime",android.content.Context.MODE_PRIVATE)
        var started:android.content.Intent?=null
        val recorder=object:android.content.ContextWrapper(activity) {
            override fun startForegroundService(intent:android.content.Intent):android.content.ComponentName? { started=intent;return intent.component }
        }
        val receiver=com.zane.zanebox.runtime.StartReceiver()
        try {
            prefs.edit().putBoolean("connected",false).commit()
            receiver.onReceive(recorder,android.content.Intent(android.content.Intent.ACTION_MY_PACKAGE_REPLACED));assertNull(started)
            prefs.edit().putBoolean("connected",true).commit()
            receiver.onReceive(recorder,android.content.Intent("invalid"));assertNull(started)
            receiver.onReceive(recorder,android.content.Intent(android.content.Intent.ACTION_MY_PACKAGE_REPLACED))
            assertEquals(com.zane.zanebox.runtime.ZaneProxyService::class.java.name,started?.component?.className)
            assertEquals("start",started?.action)
            compose.runOnIdle { vm.setting("autoStart","false") }
            compose.waitUntil(10000) { vm.data.value.setting("autoStart")=="false" };started=null
            receiver.onReceive(recorder,android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED));assertNull(started)
        } finally { prefs.edit().putBoolean("connected",false).commit() }
    }
    @Test fun backgroundProcessLossFinishesPendingLatencyAndSpeedStates() {
        val vm=seed(AppData(nodes=listOf(Node(505,505,"中断测速","""{"type":"socks","server":"10.0.2.2","server_port":19081}""")),groups=listOf(Group(505,"中断")),settings=mapOf("serviceMode" to "proxy","testUrl" to "http://203.0.113.9:19080/hang","testTimeout" to "20000","speedDownloadUrl" to "http://203.0.113.9:19080/hang")))
        try {
            compose.runOnIdle { vm.service.testNodes(listOf(505));vm.service.speedTest(505) }
            compose.waitUntil(10000) { vm.service.testingNodes.value.isNotEmpty() && vm.service.speedResult.value.isNotBlank() && !JSONObject(vm.service.speedResult.value).optBoolean("done") }
            val manager=compose.activity.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val bg=manager.runningAppProcesses.first { it.processName=="com.zane.zanebox:bg" }
            android.os.Process.killProcess(bg.pid)
            compose.waitUntil(15000) { vm.service.testingNodes.value.isEmpty() && JSONObject(vm.service.speedResult.value).optBoolean("done") }
            assertTrue(JSONObject(vm.service.speedResult.value).getString("error").contains("服务已断开"))
        } finally { compose.runOnIdle { vm.service.stop() } }
    }
}
