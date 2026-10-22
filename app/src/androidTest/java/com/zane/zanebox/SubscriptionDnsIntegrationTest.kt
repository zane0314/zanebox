package com.zane.zanebox

import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.core.SubscriptionDns
import com.zane.zanebox.data.*
import com.zane.zanebox.runtime.ServiceClient
import com.zane.zanebox.subscription.SubscriptionUpdater
import com.zane.zanebox.subscription.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL

/** Uses the existing controlled DNS/SOCKS fixture; never queries a personal subscription. */
class SubscriptionDnsIntegrationTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private fun data(mode:String="proxy",server:String="localhost")=AppData(
        nodes=listOf(Node(1,1,"DNS fixture","""{"type":"socks","server":"$server","server_port":19081}""")),
        groups=listOf(Group(1,"DNS fixture",options="""{"serverDnsResolver":"tcp://10.0.2.2:19087"}""")),
        settings=mapOf("serviceMode" to mode,"selectedNodeId" to "1","selectedGroupId" to "1",
            "factoryRouteDefaultsVersion" to "1","dnsRemote" to "local","dnsDirect" to "tcp://10.0.2.2:19087",
            "fakeDns" to "false","sniff" to "false","statsEnabled" to "false","bypassLan" to "false"))
    private suspend fun connected(client:ServiceClient)=withTimeout(30000) {
        while(client.snapshot.value.state!=2) {
            check(client.snapshot.value.state!=4) {client.snapshot.value.error}
            delay(50)
        }
    }
    private suspend fun stopped(client:ServiceClient)=withTimeout(30000) {
        client.stop()
        while(client.snapshot.value.state in 1..3)delay(50)
    }
    private fun subscriptionServer():ServerSocket {
        val server=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
        val body="""{"outbounds":[{"type":"socks","tag":"resolved","server":"localhost","server_port":19081}]}""".toByteArray()
        Thread({
            while(!server.isClosed)try {
                server.accept().use {socket ->
                    socket.soTimeout=5000
                    val input=socket.getInputStream().bufferedReader()
                    while(true) {val line=input.readLine() ?: break;if(line.isEmpty())break}
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body);flush()
                    }
                }
            } catch(e:java.io.IOException) {if(!server.isClosed)throw e}
        },"subscription-repair-fixture").apply {isDaemon=true;start()}
        return server
    }
    @Test fun forcedUpdateUsesSpecifiedDnsAndFailurePreservesTheSavedNodes()=runBlocking {
        assertTrue(InetAddress.getByName("localhost").isLoopbackAddress)
        subscriptionServer().use {server -> ZaneStore(context).use {store ->
            val group=data().groups.single().copy(subscriptionUrl="http://127.0.0.1:${server.localPort}/subscription",
                options="""{"forceResolve":true,"serverDnsResolver":"tcp://10.0.2.2:19087"}""")
            store.replace(data(server="192.0.2.1").copy(groups=listOf(group)))
            SubscriptionUpdater.update(store,group,context,connected=false)
            val resolved=store.snapshot()
            assertEquals("10.0.2.2",JSONObject(resolved.nodes.single().outbound).getString("server"))
            assertEquals("localhost",JSONObject(resolved.nodes.single().metadata).getString("subscriptionOriginalServer"))
            val bad=resolved.groups.single().copy(options=JSONObject(resolved.groups.single().options)
                .put("serverDnsResolver","tcp://10.0.2.2:9").toString())
            store.update {it.copy(groups=listOf(bad))}
            try {SubscriptionUpdater.update(store,bad,context,connected=false);fail("指定 DNS 失败不得回退系统解析")}
            catch(_:Exception) { }
            val after=store.snapshot()
            assertEquals(resolved.nodes,after.nodes)
            assertEquals(resolved.selectedNodeId,after.selectedNodeId)
        } }
    }
    @Test fun customDnsGroupStartsTheCoreAndReachesItsControlledExit()=runBlocking {
        ZaneStore(context).use {store ->
            store.replace(data())
            val client=ServiceClient(context)
            try {
                client.start();connected(client)
                val request=URL("http://203.0.113.9:19080/probe").openConnection(
                    Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",2080))) as HttpURLConnection
                try {request.connectTimeout=5000;request.readTimeout=5000;assertEquals("EXIT_A",request.inputStream.bufferedReader().use {it.readText()})}
                finally {request.disconnect()}
            } finally {stopped(client);client.close()}
        }
    }
    @Test fun specifiedResolverBootstrapUsesThePhysicalNetworkWhileVpnIsActive()=runBlocking {
        ins.uiAutomation.executeShellCommand("appops set com.zane.zanebox ACTIVATE_VPN allow").use {java.io.FileInputStream(it.fileDescriptor).readBytes()}
        ZaneStore(context).use {store ->
            store.replace(data(mode="vpn",server="10.0.2.2"))
            val client=ServiceClient(context)
            try {
                client.start();connected(client)
                val addresses=SubscriptionDns.lookup(context,"localhost","tcp://dns.fixture.test:19087",store.snapshot())
                assertEquals(listOf("10.0.2.2"),addresses)
                assertTrue("查询不应停止 VPN",client.snapshot.value.state==2)
            } finally {stopped(client);client.close()}
        }
    }
}
