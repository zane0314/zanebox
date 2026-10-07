package com.zane.zanebox

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.data.*
import com.zane.zanebox.runtime.ServiceClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.*
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class OptimizationRuntimeTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context=ins.targetContext
    private fun waitFor(label:String,timeout:Long=30000,condition:()->Boolean) {val end=SystemClock.elapsedRealtime()+timeout;while(!condition()){if(SystemClock.elapsedRealtime()>end)fail(label);Thread.sleep(50)}}
    private fun base()=AppData(nodes=listOf(Node(1,1,"A","""{"type":"socks","server":"10.0.2.2","server_port":19081}"""),Node(2,1,"B","""{"type":"socks","server":"10.0.2.2","server_port":19082}""")),groups=listOf(Group(1,"g")),settings=mapOf("serviceMode" to "proxy","selectedNodeId" to "1","factoryRouteDefaultsVersion" to "1","dnsRemote" to "local","sniff" to "false","rulesUpdateInterval" to "off"))
    private fun exit():String {val c=URL("http://203.0.113.9:19080/probe").openConnection(Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",2080))) as HttpURLConnection;return try{c.connectTimeout=3000;c.readTimeout=3000;c.inputStream.bufferedReader().use{it.readText().trim()}}finally{c.disconnect()}}
    @Test fun selectionCacheDoesNotHideReloadAndNetworkReadsDoNotQueueStop() {
        ZaneStore(context).use {store ->
            store.replace(base());val client=ServiceClient(context)
            try {
                client.start();waitFor("connect"){client.snapshot.value.state==2};assertEquals("EXIT_A",exit())
                client.selectNode(2);waitFor("select B"){store.snapshot().selectedNodeId==2L};assertEquals("EXIT_B",exit())
                val generation=client.snapshot.value.generation
                store.update{it.copy(settings=it.settings+("selectedNodeId" to "1"))};client.autoReload()
                waitFor("selection reload"){client.snapshot.value.state==2 && client.snapshot.value.generation>generation};assertEquals("EXIT_A",exit())
                store.putSetting("exitProbeUrl","http://203.0.113.9:19080/hang")
                client.queryExitIp();Thread.sleep(250);val start=SystemClock.elapsedRealtime();client.stop();waitFor("nonblocking stop",2500){client.snapshot.value.state==0}
                assertTrue(SystemClock.elapsedRealtime()-start<2500)
            } finally {client.stop();client.close()}
        }
    }
    @Test fun realtimeRuleScheduleResumesAfterOff() {
        val server=ServerSocket(0,10,InetAddress.getByName("127.0.0.1"))
        val responder=thread(isDaemon=true) {try {while(!server.isClosed)server.accept().use {socket ->
            socket.soTimeout=3000;val reader=socket.getInputStream().bufferedReader();while(reader.readLine()?.isNotEmpty()==true){}
            val body="DOMAIN,updated.fixture\n";socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray())
        }}catch(_:Exception){}}
        ZaneStore(context).use{store ->
            store.replace(base().copy(settings=base().settings+mapOf("rulesUpdateDelay" to "0s","smartUrl.custom" to "http://127.0.0.1:${server.localPort}/rules.list","smartRules.custom" to "")))
            val client=ServiceClient(context)
            try {
                client.start();waitFor("connect"){client.snapshot.value.state==2};Thread.sleep(500);assertEquals("",store.snapshot().setting("smartRules.custom"))
                store.putSetting("rulesUpdateInterval","6h");client.refreshSettings()
                waitFor("off to live schedule"){store.snapshot().setting("smartRules.custom").contains("updated.fixture")}
            } finally {client.stop();client.close();server.close();responder.join(1000)}
        }
    }
}
