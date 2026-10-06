package com.zane.zanebox

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.data.*
import com.zane.zanebox.runtime.ServiceClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import java.io.File
import java.net.*

@RunWith(AndroidJUnit4::class)
class SelectionCacheTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun waitFor(label:String,predicate:()->Boolean) { val until=android.os.SystemClock.elapsedRealtime()+30000;while(!predicate()) { if(android.os.SystemClock.elapsedRealtime()>until)fail("超时 $label");Thread.sleep(100) } }
    private fun exit():String {
        val c=URL("http://203.0.113.9:19080/probe").openConnection(Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",2080))) as HttpURLConnection
        return try { c.connectTimeout=4000;c.readTimeout=4000;c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }
    private fun apiSelect(id:Long) {
        val secret=File(context.noBackupFilesDir,"clash-api.secret").readText()
        val c=URL("http://127.0.0.1:9090/proxies/proxy").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try { c.connectTimeout=2000;c.readTimeout=2000;c.requestMethod="PUT";c.doOutput=true;c.setRequestProperty("Authorization","Bearer $secret");c.setRequestProperty("Content-Type","application/json");c.outputStream.use { it.write(JSONObject().put("name","node-$id").toString().toByteArray()) };assertTrue("面板切换失败",c.responseCode in 200..299) } finally { c.disconnect() }
    }
    @Test fun automaticPolicyUsesAllEnabledSubscriptionsAndActualFasterExitWithoutChangingHomeSelection() {
        fun node(id:Long,group:Long,port:Int,ping:Int)=Node(id,group,"节点 $id","""{"type":"socks","server":"10.0.2.2","server_port":$port}""",ping=ping)
        val base=AppData(nodes=listOf(node(701,701,19081,1),node(702,702,19082,9999),node(703,703,19081,0),node(704,704,19082,0)),groups=listOf(Group(701,"订阅 A","https://example.test/a"),Group(702,"订阅 B","https://example.test/b"),Group(703,"本地"),Group(704,"停用订阅","https://example.test/d",enabled=false)),merges=listOf(MergeGroup(705,"旧来源",nodeIds=listOf(703))),settings=mapOf("serviceMode" to "proxy","selectedNodeId" to "701","browseGroupId" to "703","appLanguage" to "zh-CN","smartSourceGroupId" to "701","smartSourceMergeId" to "705","smart.youtube.target" to "auto","smartRules.youtube" to "IP-CIDR,203.0.113.9/32","sniff" to "false","dnsRemote" to "local","testUrl" to "http://10.0.2.2:19080/test","urlTestInterval" to "1s"))
        val store=ZaneStore(context);store.replace(base)
        val scenario=ActivityScenario.launch(MainActivity::class.java);val client=ServiceClient(context);client.connect()
        fun api():JSONObject {
            val request=URL("http://127.0.0.1:9090/proxies/smart-youtube").openConnection(Proxy.NO_PROXY) as HttpURLConnection
            try {request.connectTimeout=3000;request.readTimeout=3000;request.setRequestProperty("Authorization","Bearer "+File(context.noBackupFilesDir,"clash-api.secret").readText());return JSONObject(request.inputStream.bufferedReader().use{it.readText()})}finally{request.disconnect()}
        }
        fun apply(data:AppData) {store.replace(data);val generation=client.snapshot.value.generation;client.reload();waitFor("自动策略重载") {if(client.snapshot.value.state==4)fail(client.snapshot.value.error);client.snapshot.value.state==2 && client.snapshot.value.generation>generation}}
        val evidence=JSONObject()
        try {
            client.start();waitFor("自动策略启动") {if(client.snapshot.value.state==4)fail(client.snapshot.value.error);client.snapshot.value.state==2}
            var group=JSONObject();waitFor("实测更快的订阅 B") {group=api();group.optString("now")=="node-702"}
            val candidates=group.getJSONArray("all");assertEquals(setOf("node-701","node-702"),(0 until candidates.length()).map{candidates.getString(it)}.toSet())
            assertEquals("EXIT_B",exit());assertEquals(701L,store.snapshot().selectedNodeId)
            evidence.put("autoAcrossSubscriptions",true).put("candidates",candidates).put("selected","node-702").put("homeSelected",701)
            for(legacy in listOf("off","region:jp")) {apply(base.copy(settings=base.settings+("smart.youtube.target" to legacy)));assertEquals("EXIT_A",exit())}
            val events=java.util.concurrent.CopyOnWriteArrayList<String>()
            val scope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
            val observer=scope.launch {client.events.collect{events.add(it)}}
            try {
                apply(base.copy(groups=base.groups.map{if(it.subscriptionUrl.isNotBlank())it.copy(enabled=false)else it},settings=base.settings+("selectedNodeId" to "703")))
                assertEquals("EXIT_A",exit());waitFor("空订阅池告知代理回退") {events.any{it.contains("没有可用节点")}}
            }finally{observer.cancel()}
            evidence.put("legacyTargetsUseProxy",true).put("emptySubscriptionsFallback",true)
            File(context.getExternalFilesDir(null),"automatic-subscription-result.json").writeText(evidence.toString())
        } finally {client.stop();waitFor("自动测试停止") {client.snapshot.value.state !in 1..3};client.close();scenario.close();store.replace(base);store.close()}
    }
    @Test fun restoreWinsCachedSelectionAndPanelSelectionUpdatesDatabase() {
        val store=ZaneStore(context)
        store.replace(AppData(nodes=listOf(Node(201,101,"A","{\"type\":\"socks\",\"server\":\"10.0.2.2\",\"server_port\":19081}"),Node(202,101,"B","{\"type\":\"socks\",\"server\":\"10.0.2.2\",\"server_port\":19082}")),groups=listOf(Group(101,"选择回归")),settings=mapOf("serviceMode" to "proxy","selectedNodeId" to "201","selectedGroupId" to "101","sniff" to "false","dnsRemote" to "local")))
        val scenario=ActivityScenario.launch(MainActivity::class.java);val client=ServiceClient(context);client.connect()
        try {
            client.start();waitFor("连接") { if(client.snapshot.value.state==4)fail(client.snapshot.value.error);client.snapshot.value.state==2 };assertEquals("EXIT_A",exit())
            apiSelect(202);assertEquals("EXIT_B",exit())
            val desired=store.snapshot().copy(settings=store.snapshot().settings+("selectedNodeId" to "201"))
            val previous=client.snapshot.value.generation;client.restore(desired)
            waitFor("恢复重连") { client.snapshot.value.state==2 && client.snapshot.value.generation>previous }
            assertEquals(201L,store.snapshot().selectedNodeId);assertEquals("恢复后的默认节点被旧缓存覆盖","EXIT_A",exit())
            apiSelect(202);waitFor("面板切换同步默认节点") { store.snapshot().selectedNodeId==202L };assertEquals(101L,store.snapshot().selectedGroupId)
            File(context.getExternalFilesDir(null),"selection-cache-result.json").writeText("{\"restoreDefaultOverridesCache\":true,\"panelSelectionPersisted\":true}")
        } finally { client.stop();Thread.sleep(500);client.close();scenario.close();store.close() }
    }
}
