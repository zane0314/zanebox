package com.zane.zanebox

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.zane.zanebox.runtime.ServiceClient
import com.zane.zanebox.config.ConfigBuilder
import com.zane.zanebox.config.Purpose
import kotlinx.coroutines.runBlocking
import java.io.File
import androidx.lifecycle.ViewModelProvider
import com.zane.zanebox.data.*
import com.zane.zanebox.ui.AppViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class AutomaticApplyTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val vm get()=ViewModelProvider(compose.activity)[AppViewModel::class.java]
    private fun start(controller:Boolean=false) {
        stop()
        compose.runOnIdle {vm.edit(autoApply=false) {AppData(nodes=listOf(Node(1,1,"出口 A","""{"type":"socks","server":"10.0.2.2","server_port":19081}"""),Node(2,1,"出口 B","""{"type":"socks","server":"10.0.2.2","server_port":19082}""")),groups=listOf(Group(1,"自动应用")),settings=mapOf("appLanguage" to "zh-CN","serviceMode" to "proxy","selectedNodeId" to "1","selectedGroupId" to "1","statsEnabled" to "false","clashApi" to controller.toString(),"dnsRemote" to "local","rulesUpdateInterval" to "off"))}}
        compose.waitUntil(15000){!vm.busy.value && vm.data.value.nodes.size==2}
        compose.runOnIdle {vm.service.start()}
        compose.waitUntil(40000){vm.service.snapshot.value.state==2}
        assertExit()
    }
    private fun assertExit(expected:String="EXIT_A") {
        val connection=URL("http://203.0.113.9:19080/probe").openConnection(Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",2080))) as HttpURLConnection
        connection.connectTimeout=5000;connection.readTimeout=5000
        try {assertEquals(expected,connection.inputStream.bufferedReader().use{it.readText()}.trim())}finally{connection.disconnect()}
    }
    private fun stop() {compose.runOnIdle{vm.service.stop()};compose.waitUntil(10000){vm.service.snapshot.value.state==0}}
    @Test fun safeSettingAppliesAutomaticallyAndLiveSettingDoesNotRestart() {
        start()
        val events=CopyOnWriteArrayList<String>()
        val observer=CoroutineScope(Dispatchers.Unconfined).launch{vm.service.events.collect{events.add(it)}}
        try {
            val generation=vm.service.snapshot.value.generation
            compose.runOnIdle{vm.setting("sniff","false")}
            compose.waitUntil(12000){vm.service.snapshot.value.state==2 && vm.service.snapshot.value.generation>generation && events.any{it.contains("已自动应用")}}
            assertExit();assertEquals(1L,vm.store.snapshot().selectedNodeId)
            val active=vm.service.snapshot.value.generation
            compose.runOnIdle{vm.setting("testTimeout","10000");vm.setting("showDirectSpeed","true")}
            compose.waitUntil(7000){!vm.busy.value && vm.data.value.setting("showDirectSpeed")=="true"}
            assertEquals(active,vm.service.snapshot.value.generation)
            assertFalse(vm.message.value.contains("应用修改"))
            compose.runOnIdle{vm.setting("mixedPort","2081");vm.setting("sniff","true")}
            compose.waitUntil(7000){events.any{it.contains("手动")}}
            assertEquals(active,vm.service.snapshot.value.generation);assertExit()
        } finally {observer.cancel();stop()}
    }
    @Test fun invalidConfigKeepsConnectionAndStartupFailureRestoresOldRuntimeWithoutLosingSavedSettings() {
        start()
        val events=CopyOnWriteArrayList<String>()
        val observer=CoroutineScope(Dispatchers.Unconfined).launch{vm.service.events.collect{events.add(it)}}
        try {
            val generation=vm.service.snapshot.value.generation
            compose.runOnIdle{vm.setting("dnsRemote","https://")}
            compose.waitUntil(10000){events.any{it.contains("失败") || it.contains("无效")}}
            assertEquals(generation,vm.service.snapshot.value.generation);assertEquals(2,vm.service.snapshot.value.state);assertExit()
            compose.runOnIdle{vm.setting("dnsRemote","local")}
            compose.waitUntil(12000){vm.service.snapshot.value.state==2 && events.any{it.contains("无需重启")}}
            ServerSocket(2081).use {
                compose.runOnIdle{vm.setting("mixedPort","2081")}
                compose.waitUntil(7000){!vm.busy.value && vm.store.snapshot().setting("mixedPort")=="2081"}
                compose.runOnIdle{vm.service.reload()}
                compose.waitUntil(15000){vm.service.snapshot.value.state==2 && events.any{it.contains("恢复")}}
                assertEquals("2081",vm.store.snapshot().setting("mixedPort"));assertEquals(2080,vm.service.snapshot.value.mixedPort);assertExit()
            }
        } finally {observer.cancel();stop()}
    }
    @Test fun pendingModeDoesNotChangeServiceDuringValidationSelectionOrActivityRecreation() {
        start()
        try {
            compose.runOnIdle{vm.setting("serviceMode","vpn")}
            compose.waitUntil(7000){!vm.busy.value && vm.service.snapshot.value.pendingManual}
            runBlocking{vm.service.checkConfig(ConfigBuilder.build(vm.store.snapshot(),Purpose.TEST,1))}
            val second=ServiceClient(compose.activity)
            try {assertEquals(2,runBlocking{second.readSnapshot()}.state);assertExit()}finally{second.close()}
            compose.runOnUiThread{compose.activity.recreate()}
            compose.waitUntil(7000){compose.onAllNodesWithTag("apply_changes").fetchSemanticsNodes().isNotEmpty()}
            val generation=vm.service.snapshot.value.generation
            compose.runOnIdle{vm.service.selectAuto()}
            compose.waitUntil(15000){vm.service.snapshot.value.state==2 && vm.service.snapshot.value.generation>generation && vm.store.snapshot().bool("homeAutoSelect")}
            compose.runOnIdle{vm.service.selectNode(2)}
            compose.waitUntil(15000){vm.service.snapshot.value.state==2 && vm.store.snapshot().selectedNodeId==2L && !vm.store.snapshot().bool("homeAutoSelect")}
            assertExit("EXIT_B");assertEquals("vpn",vm.store.snapshot().setting("serviceMode"));assertTrue(vm.service.snapshot.value.pendingManual)
        } finally {stop()}
    }
    @Test fun externalPanelSelectionSurvivesFailedReload() {
        start(controller=true)
        val events=CopyOnWriteArrayList<String>()
        val observer=CoroutineScope(Dispatchers.Unconfined).launch{vm.service.events.collect{events.add(it)}}
        try {
            val generation=vm.service.snapshot.value.generation
            compose.runOnIdle{vm.service.selectAuto()}
            compose.waitUntil(15000){vm.service.snapshot.value.state==2 && vm.service.snapshot.value.generation>generation && vm.store.snapshot().bool("homeAutoSelect")}
            compose.runOnIdle{vm.setting("serviceMode","vpn")}
            compose.waitUntil(7000){!vm.busy.value && vm.service.snapshot.value.pendingManual}
            val c=URL("http://127.0.0.1:9090/proxies/proxy").openConnection(Proxy.NO_PROXY) as HttpURLConnection
            try {
                c.requestMethod="PUT";c.doOutput=true;c.connectTimeout=5000;c.readTimeout=5000
                c.setRequestProperty("Authorization","Bearer "+File(compose.activity.noBackupFilesDir,"clash-api.secret").readText())
                c.setRequestProperty("Content-Type","application/json")
                c.outputStream.use{it.write("""{"name":"node-2"}""".toByteArray())};assertEquals(204,c.responseCode)
            } finally {c.disconnect()}
            compose.waitUntil(15000){vm.store.snapshot().selectedNodeId==2L && !vm.store.snapshot().bool("homeAutoSelect") && vm.service.snapshot.value.state==2 && vm.service.snapshot.value.generation>generation+2}
            assertExit("EXIT_B");assertEquals("vpn",vm.store.snapshot().setting("serviceMode"))
            compose.runOnIdle{vm.setting("serviceMode","proxy")}
            compose.waitUntil(7000){!vm.busy.value && !vm.service.snapshot.value.pendingManual}
            ServerSocket(2081).use {
                compose.runOnIdle{vm.setting("mixedPort","2081")}
                compose.waitUntil(7000){!vm.busy.value && vm.store.snapshot().setting("mixedPort")=="2081"}
                compose.runOnIdle{vm.service.reload()}
                compose.waitUntil(15000){vm.service.snapshot.value.state==2 && events.any{it.contains("恢复")}}
                assertExit("EXIT_B");assertEquals(2L,vm.store.snapshot().selectedNodeId);assertEquals("2081",vm.store.snapshot().setting("mixedPort"))
            }
        } finally {observer.cancel();stop()}
    }
}
