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
