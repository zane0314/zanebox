package com.zane.zanebox

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.zane.zanebox.data.*
import com.zane.zanebox.ui.*
import com.zane.zanebox.runtime.ServiceClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.*

class SmartTargetPickerTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun groupedNodesSaveAndRouteWithoutChangingHomeSelection() {
        val vm=ViewModelProvider(compose.activity)[AppViewModel::class.java]
        fun node(id:Long,group:Long,port:Int)=Node(id,group,"节点 $id","""{"type":"socks","server":"10.0.2.2","server_port":$port}""")
        val base=AppData(nodes=listOf(node(801,801,19081),node(802,801,19082),node(803,802,19081),node(804,804,19082)),groups=listOf(Group(801,"订阅 A","https://example.test/a"),Group(802,"本地 B"),Group(803,"空订阅","https://example.test/empty"),Group(804,"停用订阅","https://example.test/disabled",enabled=false)),merges=listOf(MergeGroup(805,"汇总",nodeIds=listOf(801,802))),settings=mapOf("appLanguage" to "zh-CN","selectedNodeId" to "801","serviceMode" to "proxy","dnsRemote" to "local","sniff" to "false","smart.youtube.target" to "proxy","smartRules.youtube" to "IP-CIDR,203.0.113.9/32"))
        compose.runOnIdle{vm.edit{base}}
        compose.waitUntil(10000){!vm.busy.value && vm.data.value.nodes.size==4}
        compose.onNodeWithTag("tab_1").performClick()
        fun dialog(text:String)=compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))
        fun open() {
            // Large-font scroll-to aligns this button beneath the transient save feedback.
            compose.waitUntil(10000){compose.onAllNodes(hasText("已保存，请应用修改使配置生效")).fetchSemanticsNodes().isEmpty()}
            compose.onNodeWithTag("smart_target_youtube").performScrollTo().performClick()
        }
        for(scale in listOf("1.0","2.0")) {
            compose.runOnIdle{vm.setting("fontScale",scale)}
            compose.waitUntil(10000){vm.data.value.setting("fontScale")==scale}
            open()
            for(id in 801..804)compose.onNodeWithText("节点 $id").assertDoesNotExist()
            dialog("分组 · 停用订阅").assertDoesNotExist()
            dialog("分组 · 订阅 A").performClick()
            dialog("节点 801").assertIsDisplayed();dialog("节点 802").assertIsDisplayed()
            dialog("节点 803").assertDoesNotExist();dialog("节点 804").assertDoesNotExist()
            dialog("取消").performClick();dialog("分流目标").assertIsDisplayed()
            assertEquals("proxy",vm.data.value.setting("smart.youtube.target"))
            dialog("分组 · 空订阅").performClick();dialog("整个分组").assertIsDisplayed();dialog("取消").performClick()
            dialog("分组 · 订阅 A").performClick();dialog("节点 802").performClick()
            compose.waitUntil(10000){vm.data.value.setting("smart.youtube.target")=="node:802"}
            open();dialog("分组 · 订阅 A").performClick()
            dialog("节点 802").assertIsSelected()
            dialog("整个分组").performClick();compose.waitUntil(10000){vm.data.value.setting("smart.youtube.target")=="group:801"}
            open();dialog("分组 · 本地 B").performClick();dialog("节点 803").assertIsDisplayed();dialog("取消").performClick()
            dialog("汇总组 · 汇总").performClick();compose.waitUntil(10000){vm.data.value.setting("smart.youtube.target")=="merge:805"}
            open();dialog("代理").performClick()
            compose.waitUntil(10000){vm.data.value.setting("smart.youtube.target")=="proxy"}
        }
        compose.runOnIdle{vm.setting("fontScale","1.0")}
        open();dialog("分组 · 订阅 A").performClick();dialog("节点 802").performClick()
        compose.waitUntil(10000){!vm.busy.value && vm.data.value.setting("smart.youtube.target")=="node:802"}
        val client=ServiceClient(compose.activity);client.connect()
        try {
            client.start();compose.waitUntil(30000){if(client.snapshot.value.state==4)fail(client.snapshot.value.error);client.snapshot.value.state==2}
            val request=URL("http://203.0.113.9:19080/probe").openConnection(Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",2080))) as HttpURLConnection
            try {request.connectTimeout=4000;request.readTimeout=4000;assertEquals("EXIT_B",request.inputStream.bufferedReader().use{it.readText()})}finally{request.disconnect()}
            assertEquals(801L,vm.store.snapshot().selectedNodeId)
            assertEquals("node:802",vm.store.snapshot().setting("smart.youtube.target"))
        }finally{client.stop();compose.waitUntil(30000){client.snapshot.value.state !in 1..3};client.close()}
    }
}
