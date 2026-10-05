package com.zane.zanebox

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.data.*
import com.zane.zanebox.runtime.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.*

@RunWith(AndroidJUnit4::class)
class RuntimeRegressionTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private fun shell(command:String)=ins.uiAutomation.executeShellCommand(command).use { p->java.io.FileInputStream(p.fileDescriptor).bufferedReader().readText() }
    private fun waitFor(label:String,timeout:Long=20000,predicate:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+timeout
        while(!predicate()) { if(SystemClock.elapsedRealtime()>until)fail("超时 $label");Thread.sleep(100) }
    }
    private fun request():String {
        val c=URL("http://203.0.113.9:19080/probe").openConnection(Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",2080))) as HttpURLConnection
        return try { c.connectTimeout=4000;c.readTimeout=4000;c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }
    @Test fun proxyModeQueueRollbackAndBackgroundLifetime() {
        val store=ZaneStore(context)
        store.replace(AppData(nodes=listOf(Node(1,1,"fixture A","{\"type\":\"socks\",\"server\":\"10.0.2.2\",\"server_port\":19081}")),groups=listOf(Group(1,"fixture")),settings=mapOf("serviceMode" to "proxy","selectedNodeId" to "1","selectedGroupId" to "1","autoStart" to "true","sniff" to "false","dnsRemote" to "local","exitProbeUrl" to "http://203.0.113.9:19080/trace")))
        var scenario:ActivityScenario<MainActivity>?=ActivityScenario.launch(MainActivity::class.java)
        var client=ServiceClient(context);client.connect()
        val evidence=JSONObject()
        try {
            shell("appops set com.zane.zanebox ACTIVATE_VPN deny")
            ins.runOnMainSync { StartReceiver().onReceive(context,Intent(Intent.ACTION_BOOT_COMPLETED)) }
            waitFor("仅代理自动启动，无VPN权限") { if(client.snapshot.value.state==4)fail(client.snapshot.value.error);client.snapshot.value.state==2 }
            assertEquals("EXIT_A",request());evidence.put("proxyAutoStart",true)
            client.queryExitIp();waitFor("经内核出口IP") { client.exitIp.value=="203.0.113.10" };evidence.put("exitIp",client.exitIp.value)
            client.openPanel();waitFor("YACD地址") { client.panelUrl.value.contains("/ui/") }
            val panel=URL(client.panelUrl.value.substringBefore('#')).openConnection(Proxy.NO_PROXY) as HttpURLConnection
            try { panel.connectTimeout=4000;panel.readTimeout=4000;assertEquals(200,panel.responseCode);assertTrue(panel.inputStream.bufferedReader().use { it.readText() }.contains("<html",ignoreCase=true)) } finally { panel.disconnect() }
            evidence.put("yacdAssetServed",true)
            client.refreshConnections();waitFor("连接监控实际API") { JSONObject(client.connections.value).has("connections") };evidence.put("connectionsApi",true)
            store.update { it.copy(settings=it.settings+mapOf("speedTimeout" to "2000","speedDownloadUrl" to "http://10.0.2.2:19080/speed")) }
            client.speedTest(1,"simple")
            waitFor("原生实际吞吐测速",30000) { client.speedResult.value.isNotBlank() && JSONObject(client.speedResult.value).optBoolean("done") }
            val speed=JSONObject(client.speedResult.value);assertTrue(speed.toString(),speed.optString("error").isBlank());assertTrue(speed.toString(),speed.optDouble("download")>0);assertEquals(1L,store.snapshot().selectedNodeId);evidence.put("nativeSpeedDownload",true)

            // Port opens during start, rather than decoder validation. Restoration must roll back both store and counters.
            val before=store.snapshot();val generation=client.snapshot.value.generation
            ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { occupied ->
                val incoming=before.copy(nodes=before.nodes.map { it.copy(name="不应保留的恢复节点") },settings=before.settings+mapOf("apiPort" to occupied.localPort.toString(),"trafficData" to "{\"apps\":{},\"domains\":{},\"nodes\":{\"1\":{\"tx\":987654321,\"rx\":987654321}}}"))
                client.restore(incoming)
                waitFor("恢复失败后旧服务重启",30000) { client.snapshot.value.state==2 && client.snapshot.value.generation>generation }
                assertEquals("fixture A",store.snapshot().nodes.single().name)
                assertEquals("9090",store.snapshot().setting("apiPort","9090"))
                assertEquals("EXIT_A",request())
                client.refreshTraffic();Thread.sleep(400)
                assertFalse(client.traffic.value.contains("987654321"))
            }
            evidence.put("restoreRollback",true)

            var previous=client.snapshot.value.generation
            client.reload();waitFor("重载") { client.snapshot.value.state==2 && client.snapshot.value.generation>previous }
            client.close();scenario?.close();scenario=null;Thread.sleep(700)
            client=ServiceClient(context);client.connect();waitFor("页面关闭后服务仍连接") { client.snapshot.value.state==2 }
            assertEquals("EXIT_A",request());evidence.put("reloadBackgroundLifetime",true)
            previous=client.snapshot.value.generation
            client.restore(store.snapshot().copy(nodes=store.snapshot().nodes.map { it.copy(name="恢复成功节点") }))
            waitFor("同模式恢复重启") { client.snapshot.value.state==2 && client.snapshot.value.generation>previous && store.snapshot().nodes.single().name=="恢复成功节点" }
            client.close();Thread.sleep(700);client=ServiceClient(context);client.connect();waitFor("恢复后解绑仍连接") { client.snapshot.value.state==2 }
            assertEquals("EXIT_A",request());evidence.put("restoreBackgroundLifetime",true)

            // This query must time out without terminating the service command consumer.
            store.update { it.copy(settings=it.settings+("exitProbeUrl" to "http://203.0.113.9:19080/hang")) }
            client.queryExitIp();client.stop();waitFor("查询超时后stop仍处理",16000) { client.snapshot.value.state==0 }
            assertTrue("停止丢失流量",client.snapshot.value.rxTotal>0)
            val traffic=JSONObject(store.snapshot().setting("trafficData"));assertTrue(traffic.getJSONObject("nodes").getJSONObject("1").getLong("rx")>0)
            assertFalse(traffic.toString().contains("987654321"));evidence.put("stopTailTraffic",client.snapshot.value.rxTotal)
            repeat(3) {
                previous=client.snapshot.value.generation;client.start();client.stop()
                waitFor("快速start-stop顺序") { client.snapshot.value.state==0 && client.snapshot.value.generation>=previous+2 }
            }
            evidence.put("startStopFifo",true)
            scenario=ActivityScenario.launch(MainActivity::class.java)
            client.start();waitFor("通知验证前连接") { client.snapshot.value.state==2 }
            val notifications=context.getSystemService(android.app.NotificationManager::class.java)
            waitFor("代理状态通知") { notifications.activeNotifications.any { it.id==1 } }
            val notification=notifications.activeNotifications.first { it.id==1 }.notification
            assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT!=0)
            notification.actions.first { it.title.toString()=="断开" }.actionIntent.send()
            waitFor("通知断开实际服务") { client.snapshot.value.state==0 };evidence.put("notificationStop",true)
            client.start();waitFor("磁贴验证前连接") { client.snapshot.value.state==2 }
            shell("cmd statusbar add-tile com.zane.zanebox/.runtime.QuickTileService")
            Thread.sleep(500)
            val tileResult=shell("cmd statusbar click-tile com.zane.zanebox/.runtime.QuickTileService")
            assertFalse(tileResult,tileResult.contains("Unknown command"))
            waitFor("仅代理磁贴停止") { client.snapshot.value.state==0 };evidence.put("proxyTileStop",true)
            repeat(2) { androidx.core.content.ContextCompat.startForegroundService(context,Intent(context,ZaneProxyService::class.java).setAction("stop")) }
            Thread.sleep(6000);assertEquals(0,client.snapshot.value.state);evidence.put("duplicateOsStop",true)
            client.stun("127.0.0.1:invalid")
            waitFor("STUN失败反馈") { client.stunResult.value.contains("Discover Error:") }
            assertFalse(JSONObject(client.stunResult.value).optBoolean("success"))
            client.stun("10.0.2.2:19083")
            waitFor("STUN真实UDP夹具",30000) { JSONObject(client.stunResult.value).optBoolean("success") }
            assertTrue(client.stunResult.value.contains("203.0.113.55"));evidence.put("stunUdpSuccess",true)
            client.stun("10.0.2.2:19089");client.cancelStun()
            waitFor("STUN取消反馈") { client.stunResult.value.contains("已取消") }
            evidence.put("stunCancel",true)
        } finally {
            File(context.getExternalFilesDir(null),"runtime-regression-result.json").writeText(evidence.toString(2))
            client.stop();Thread.sleep(500);client.close();scenario?.close();store.close()
        }
    }
}
