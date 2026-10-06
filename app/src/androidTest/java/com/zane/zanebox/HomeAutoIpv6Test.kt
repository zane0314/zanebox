package com.zane.zanebox

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.config.ConfigBuilder
import com.zane.zanebox.data.*
import com.zane.zanebox.runtime.ServiceClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.*

class HomeAutoIpv6Test {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context=ins.targetContext
    private fun waitFor(label:String,predicate:()->Boolean) {val until=SystemClock.elapsedRealtime()+30000;while(!predicate()) {if(SystemClock.elapsedRealtime()>until)fail("超时 $label");Thread.sleep(100)}}
    private fun base()=AppData(nodes=listOf(Node(1101,1101,"慢 A","""{"type":"socks","server":"10.0.2.2","server_port":19081}"""),Node(1102,1102,"快 B","""{"type":"socks","server":"10.0.2.2","server_port":19082}"""),Node(1103,1103,"停用","""{"type":"socks","server":"10.0.2.2","server_port":19081}""")),groups=listOf(Group(1101,"本地"),Group(1102,"订阅","https://example.test/sub"),Group(1103,"停用",enabled=false)),settings=mapOf("selectedNodeId" to "1101","selectedGroupId" to "1101","serviceMode" to "proxy","appLanguage" to "zh-CN","sniff" to "false","dnsRemote" to "local","testUrl" to "http://10.0.2.2:19080/test","statsEnabled" to "false"))
    private fun exit():String {val c=URL("http://203.0.113.9:19080/probe").openConnection(Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",2080))) as HttpURLConnection;return try {c.connectTimeout=4000;c.readTimeout=4000;c.inputStream.bufferedReader().readText()}finally{c.disconnect()}}
    @Test fun homeAutoUsesActualFastestExitPersistsAndManualSelectionStopsIt() {
        val store=ZaneStore(context);store.replace(base())
        val scenario=ActivityScenario.launch(MainActivity::class.java);val client=ServiceClient(context);client.connect()
        try {
            client.selectAuto();waitFor("自动选择保存"){store.snapshot().bool("homeAutoSelect")}
            client.start();waitFor("自动连接"){if(client.snapshot.value.state==4)fail(client.snapshot.value.error);client.snapshot.value.state==2}
            waitFor("实际低延迟 B"){client.autoNodeId.value==1102L}
            assertEquals("EXIT_B",exit());assertEquals(1101L,store.snapshot().selectedNodeId)
            scenario.recreate();assertTrue(store.snapshot().bool("homeAutoSelect"))
            client.selectNode(1101);waitFor("手动恢复 A"){client.snapshot.value.state==2 && !store.snapshot().bool("homeAutoSelect") && runCatching{exit()=="EXIT_A"}.getOrDefault(false)}
            assertFalse(JSONObject(ConfigBuilder.build(store.snapshot())).getJSONArray("outbounds").toString().contains("home-auto"))
            client.selectAuto();waitFor("连接中再次启用自动"){client.snapshot.value.state==2 && store.snapshot().bool("homeAutoSelect") && client.autoNodeId.value==1102L}
            assertEquals("EXIT_B",exit())
            val rebound=ServiceClient(context);rebound.connect()
            try {waitFor("重绑客户端恢复自动当前节点"){rebound.autoNodeId.value==1102L}} finally {rebound.close()}
            val request=URL("http://127.0.0.1:9090/proxies/proxy").openConnection(Proxy.NO_PROXY) as HttpURLConnection
            try {
                request.requestMethod="PUT";request.doOutput=true;request.setRequestProperty("Authorization","Bearer "+File(context.noBackupFilesDir,"clash-api.secret").readText());request.setRequestProperty("Content-Type","application/json")
                request.outputStream.use {it.write("""{"name":"node-1101"}""".toByteArray())};assertTrue(request.responseCode in 200..299)
            } finally {request.disconnect()}
            waitFor("面板切回同一手动节点退出自动"){client.snapshot.value.state==2 && !store.snapshot().bool("homeAutoSelect") && runCatching{exit()=="EXIT_A"}.getOrDefault(false)}
            assertFalse(JSONObject(ConfigBuilder.build(store.snapshot())).getJSONArray("outbounds").toString().contains("home-auto"))
        } finally {client.stop();waitFor("停止"){client.snapshot.value.state !in 1..3};client.close();scenario.close();store.close()}
    }
    @Test fun ipv6FakeDnsAndLiteralTrafficReachProxyWithLanBypass() {
        val store=ZaneStore(context);store.replace(base().copy(settings=base().settings+mapOf("serviceMode" to "vpn","ipv6" to "true","dnsStrategy" to "ipv6_only","bypassLan" to "true","dnsRemote" to "tcp://10.0.2.2:19087","dnsDirect" to "tcp://10.0.2.2:19087")))
        ins.uiAutomation.executeShellCommand("appops set com.zane.zanebox ACTIVATE_VPN allow").close()
        val scenario=ActivityScenario.launch(MainActivity::class.java);val client=ServiceClient(context);client.connect()
        try {
            client.start();waitFor("IPv6 VPN连接"){if(client.snapshot.value.state==4)fail(client.snapshot.value.error);client.snapshot.value.state==2}
            val addresses=InetAddress.getAllByName("ipv6-round110.zanebox.test")
            val fake=addresses.filterIsInstance<Inet6Address>().first()
            fun request(address:InetAddress):String = Socket().use { socket ->socket.connect(InetSocketAddress(address,19080),5000);socket.soTimeout=5000;socket.getOutputStream().write("GET /probe HTTP/1.1\r\nHost: ipv6-round110.zanebox.test\r\nConnection: close\r\n\r\n".toByteArray());socket.getInputStream().bufferedReader().readText()}
            assertTrue("AAAA FakeDNS流量未进入代理",request(fake).contains("EXIT_A"))
            assertTrue("IPv6地址流量未进入代理",request(InetAddress.getByName("2001:db8::110")).contains("EXIT_A"))
        } finally {client.stop();waitFor("停止IPv6"){client.snapshot.value.state !in 1..3};client.close();scenario.close();store.close()}
    }
}
