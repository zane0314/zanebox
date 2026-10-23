package com.zane.zanebox

import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.config.*
import com.zane.zanebox.core.NativePlatform
import com.zane.zanebox.core.SingBoxEngine
import com.zane.zanebox.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** All destinations and SOCKS hops are owned loopback fixtures; no Internet or device state is needed. */
class ChainRuntimeTest {
    private class Socks(private val label:String="",bindHost:String="127.0.0.1"):Closeable {
        val listener=ServerSocket(0,32,InetAddress.getByName(bindHost))
        val port get()=listener.localPort
        val enabled=AtomicBoolean(true)
        val observed=java.util.concurrent.ConcurrentLinkedQueue<String>()
        val delays=java.util.concurrent.ConcurrentHashMap<Int,Long>()
        private val jobs=Executors.newCachedThreadPool()
        private val sockets=java.util.Collections.synchronizedSet(mutableSetOf<Socket>())
        init {jobs.execute {while(!listener.isClosed)runCatching {listener.accept()}.getOrNull()?.let {s->sockets.add(s);jobs.execute {runCatching {handle(s)};sockets.remove(s);s.close()}}}}
        private fun handle(client:Socket) {
            client.soTimeout=5000
            val input=java.io.DataInputStream(client.getInputStream());val output=client.getOutputStream()
            check(input.readUnsignedByte()==5);val methods=ByteArray(input.readUnsignedByte());input.readFully(methods);output.write(byteArrayOf(5,0))
            check(input.readUnsignedByte()==5 && input.readUnsignedByte()==1);input.readUnsignedByte()
            val host=when(input.readUnsignedByte()) {1->ByteArray(4).also {input.readFully(it)}.let {InetAddress.getByAddress(it).hostAddress!!};3->ByteArray(input.readUnsignedByte()).also {input.readFully(it)}.toString(Charsets.UTF_8);else->error("非夹具地址")}
            val destination=input.readUnsignedShort()
            check(enabled.get() && host in listOf("127.0.0.1","127.0.0.2","127.0.0.3","203.0.113.9","203.0.113.10","203.0.113.11")) {"夹具拒绝非loopback或已禁用连接"}
            Thread.sleep(delays[destination] ?: 5)
            if(host.startsWith("203.0.113.") && label.isNotBlank()) {
                output.write(byteArrayOf(5,0,0,1,127,0,0,1,0,0));output.flush()
                val reader=input.bufferedReader();val request=StringBuilder()
                while(true) {val line=reader.readLine() ?: break;if(line.isEmpty())break;request.append(line)}
                observed.add(request.toString())
                output.write("HTTP/1.1 200 OK\r\nContent-Length: ${label.length}\r\nConnection: close\r\n\r\n$label".toByteArray());return
            }
            Socket(if(host.startsWith("127."))host else "127.0.0.1",destination).use { upstream->
                output.write(byteArrayOf(5,0,0,1,127,0,0,1,0,0));output.flush()
                val upstreamCopy=jobs.submit {runCatching {val buffer=ByteArray(8192);while(true) {val size=input.read(buffer);if(size<0)break;observed.add(String(buffer,0,size,Charsets.ISO_8859_1));upstream.getOutputStream().write(buffer,0,size)}};runCatching {upstream.shutdownOutput()}}
                runCatching {upstream.getInputStream().copyTo(output)};upstreamCopy.cancel(true)
            }
        }
        override fun close() {listener.close();synchronized(sockets) {sockets.toList().forEach {runCatching {it.close()}}};jobs.shutdownNow()}
    }
    @Test fun landingWinnerUsesBodyPathAndFailureRecoversWithoutSkippingHop() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val platform=NativePlatform(context,null) {_,_->};platform.initialize()
        val engine=SingBoxEngine(platform)
        val origin=ServerSocket(0,32,InetAddress.getByName("127.0.0.1"));val originJobs=Executors.newCachedThreadPool()
        originJobs.execute {while(!origin.isClosed)runCatching {origin.accept()}.getOrNull()?.let {s->originJobs.execute {s.use {runCatching {it.soTimeout=3000;val r=it.getInputStream().bufferedReader();while(!r.readLine().isNullOrEmpty()){};it.getOutputStream().write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())}}}}}
        val front1=Socks();val front2=Socks();val body=Socks();val landing1=Socks();val landing2=Socks()
        try {
            front1.delays[origin.localPort]=5;front2.delays[origin.localPort]=120
            body.delays[landing1.port]=220;body.delays[landing2.port]=5
            fun node(id:Long,group:Long,s:Socks)=Node(id,group,"fixture-$id","""{"type":"socks","server":"127.0.0.1","server_port":${s.port}}""")
            val data=AppData(nodes=listOf(node(1,1,body),node(2,2,front1),node(3,2,front2),node(4,3,landing1),node(5,3,landing2)),groups=listOf(Group(1,"主体").withChainTargets(ChainTargets("group:2","group:3")),Group(2,"前置"),Group(3,"落地")),settings=mapOf("dnsRemote" to "local","unifiedDelay" to "false"))
            val config=ConfigBuilder.build(data,Purpose.TEST,1);val url="http://127.0.0.1:${origin.localPort}/204"
            fun defaults():Map<String,String> {val a=JSONObject(engine.resolvedTestConfig(config,url,1200)).getJSONArray("outbounds");return (0 until a.length()).map {a.getJSONObject(it)}.associate {it.optString("tag") to it.optString("default")}}
            val first=defaults()
            assertEquals("chain-1-front-2",first["chain-1-front"])
            assertEquals("chain-1-landing-5",first["node-1"])
            landing2.enabled.set(false)
            assertEquals("chain-1-landing-4",defaults()["node-1"])
            landing1.enabled.set(false)
            assertTrue(runCatching {engine.resolvedTestConfig(config,url,500)}.isFailure)
            landing2.enabled.set(true)
            assertEquals("chain-1-landing-5",defaults()["node-1"])
        } finally {listOf(front1,front2,body,landing1,landing2).forEach {it.close()};origin.close();originJobs.shutdownNow();engine.cancelTests();platform.close()}
    }
    @Test fun liveVpnPublishesRealWinnersAndRecoversWithoutRestart() {
        val ins=InstrumentationRegistry.getInstrumentation();val context=ins.targetContext
        fun shell(cmd:String)=ins.uiAutomation.executeShellCommand(cmd).use {java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText()}
        fun waitFor(label:String,timeout:Long=30000,predicate:()->Boolean) {val end=android.os.SystemClock.elapsedRealtime()+timeout;while(!predicate()) {if(android.os.SystemClock.elapsedRealtime()>end)fail("超时：$label");Thread.sleep(50)}}
        val origin=ServerSocket(0,32,InetAddress.getByName("127.0.0.1"));val jobs=Executors.newCachedThreadPool()
        jobs.execute {while(!origin.isClosed)runCatching {origin.accept()}.getOrNull()?.let {socket->jobs.execute {socket.use {runCatching {it.soTimeout=3000;val reader=it.getInputStream().bufferedReader();while(!reader.readLine().isNullOrEmpty()){};it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 8\r\nConnection: close\r\n\r\nCHAIN_OK".toByteArray())}}}}}
        val front1=Socks();val front2=Socks();val body=Socks("BODY_1");val landing1=Socks("EXIT_4");val landing2=Socks("EXIT_5");val body2=Socks("BODY_6")
        val store=ZaneStore(context);val original=store.snapshot();val client=com.zane.zanebox.runtime.ServiceClient(context)
        val replacementFixtures=mutableListOf<Socks>()
        try {
            front1.delays[origin.localPort]=5;front2.delays[origin.localPort]=180
            body.delays[landing1.port]=250;body.delays[landing2.port]=5
            fun node(id:Long,g:Long,s:Socks)=Node(id,g,"fixture-$id","""{"type":"socks","server":"127.0.0.1","server_port":${s.port}}""")
            body2.delays[landing1.port]=65;body2.delays[landing2.port]=350
            val settings=com.zane.zanebox.config.builtinSmartRuleFiles.keys.associate {"smart.$it.target" to "off"}+mapOf("serviceMode" to "vpn","selectedNodeId" to "1","selectedGroupId" to "1","dnsRemote" to "local","dnsDirect" to "local","sniff" to "false","bypassLan" to "false","bypassLanInCore" to "false","statsEnabled" to "false","clashApi" to "false","testUrl" to "http://203.0.113.9:${origin.localPort}/test","testTimeout" to "1000","unifiedDelay" to "false","urlTestInterval" to "2s","rulesUpdateInterval" to "off","smart.youtube.target" to "merge:10","smartRules.youtube" to "IP-CIDR,203.0.113.10/32","smart.telegram.target" to "merge:11","smartRules.telegram" to "IP-CIDR,203.0.113.11/32")
            val sources=AppData(nodes=listOf(node(6,1,body2),node(1,1,body),node(2,2,front1),node(3,2,front2),node(4,3,landing1),node(5,3,landing2)),groups=listOf(Group(1,"原始机场 A","https://fixture.invalid/source-a"),Group(2,"前置"),Group(3,"落地 B","https://fixture.invalid/landing-b")),settings=settings)
            val derived=sources.createDerivedChainGroup(1,"派生分组 C",ChainTargets("group:2","group:3"))
            val groupC=derived.groups.first {derivedGroupSourceId(it)==1L}
            val c1=derived.nodes.first {it.groupId==groupC.id && derivedNodeSourceId(it)==1L}.id
            val c6=derived.nodes.first {it.groupId==groupC.id && derivedNodeSourceId(it)==6L}.id
            val withSingle=derived.createDerivedChainNode(1,"独立 A → B",ChainTargets("none","node:5"))
            val singleC=withSingle.nodes.single {it.id !in derived.nodes.map {n->n.id}}.id
            val configured=withSingle.copy(merges=listOf(MergeGroup(10,"手动链汇总",groupIds=listOf(groupC.id),selectedId=c6),MergeGroup(11,"自动链汇总",groupIds=listOf(groupC.id),mode="urltest")),settings=withSingle.settings+("selectedNodeId" to "$c1")+("selectedGroupId" to "${groupC.id}"))
            store.replace(configured)
            fun assertSourcesUnchanged() {
                val current=store.snapshot()
                sources.nodes.forEach {source->val actual=current.nodes.first {it.id==source.id};assertEquals(source.outbound,actual.outbound);assertEquals(source.metadata,actual.metadata)}
                sources.groups.forEach {source->assertEquals(source,current.groups.first {it.id==source.id})}
            }
            assertSourcesUnchanged()
            client.connect();shell("appops set com.zane.zanebox ACTIVATE_VPN allow");client.start()
            waitFor("VPN连接") {client.snapshot.value.state in listOf(2,4)}
            assertEquals(client.snapshot.value.error,2,client.snapshot.value.state)
            fun winner(id:Long)=JSONObject(client.chainSelections.value).optString("node-$c1")=="chain-$c1-landing-$id"
            waitFor("接入位置真实winner") {winner(5)}
            assertEquals("chain-$c1-front-2",JSONObject(client.chainSelections.value).optString("chain-$c1-front"))
            val generation=client.snapshot.value.generation
            fun actualPath(expected:String,front:Socks,landing:Socks,viaBody:Socks=body,ip:String="203.0.113.9") {
                val nonce="chainRuntime"+System.nanoTime()
                shell("am start -W -n com.zane.probe/.ProbeActivity --es url http://$ip:${origin.localPort}/probe?nonce=$nonce --es nonce $nonce --ez expected_vpn true")
                waitFor("独立UID真实VPN出口 $expected") {shell("logcat -d -s ZaneProbe:I").contains("$nonce=$expected uid=")}
                assertTrue("实际流量未经过选中前置",front.observed.any {it.contains(nonce)})
                assertTrue("实际流量未经过主体",viaBody.observed.any {it.contains(nonce)})
                assertTrue("实际流量未经过选中落地",landing.observed.any {it.contains(nonce)})
            }
            actualPath("EXIT_5",front1,landing2)
            waitFor("第二条链初始化") {JSONObject(client.chainSelections.value).optString("node-$c6")=="chain-$c6-landing-4"}
            actualPath("EXIT_4",front1,landing1,body2,"203.0.113.10")
            waitFor("自动汇总选择最快完整链") {JSONObject(client.chainSelections.value).optString("merge-11")=="node-$c1"}
            actualPath("EXIT_5",front1,landing2,body,"203.0.113.11")
            front1.delays[origin.localPort]=300;front2.delays[origin.localPort]=5
            waitFor("前置重新选优",60000) {JSONObject(client.chainSelections.value).optString("chain-$c1-front")=="chain-$c1-front-3" && winner(5)}
            actualPath("EXIT_5",front2,landing2)
            landing2.enabled.set(false);waitFor("失败切换另一落地",60000) {winner(4)}
            actualPath("EXIT_4",front2,landing1)
            waitFor("自动汇总比较新完整链",60000) {JSONObject(client.chainSelections.value).optString("merge-11")=="node-$c6" && JSONObject(client.chainSelections.value).optString("chain-$c6-front")=="chain-$c6-front-3"}
            actualPath("EXIT_4",front2,landing1,body2,"203.0.113.10")
            actualPath("EXIT_4",front2,landing1,body2,"203.0.113.11")
            landing1.enabled.set(false);waitFor("落地全败阻断",60000) {JSONObject(client.chainSelections.value).optString("node-$c1")=="chain-$c1-unavailable"}
            val proxy=java.net.Proxy(java.net.Proxy.Type.HTTP,java.net.InetSocketAddress("127.0.0.1",2080))
            assertTrue("落地全败仍能访问",runCatching {val c=java.net.URL("http://203.0.113.9:${origin.localPort}/probe").openConnection(proxy) as java.net.HttpURLConnection;try {c.connectTimeout=1500;c.readTimeout=1500;c.inputStream.use {it.readBytes()}}finally {c.disconnect()}}.isFailure)
            landing2.enabled.set(true);waitFor("落地恢复",60000) {winner(5)}
            actualPath("EXIT_5",front2,landing2)
            waitFor("自动汇总恢复最快完整链",60000) {JSONObject(client.chainSelections.value).optString("merge-11")=="node-$c1"}
            actualPath("EXIT_5",front2,landing2,body,"203.0.113.11")
            assertEquals(c6,store.snapshot().merges.first {it.id==10L}.selectedId)
            assertEquals(generation,client.snapshot.value.generation)
            assertSourcesUnchanged()
            fun selectAndProbe(id:Long,expected:String,hops:List<Socks>,forbidden:List<Socks>) {
                client.selectNode(id);waitFor("切换到节点 $id") {store.snapshot().selectedNodeId==id}
                val nonce="sourceIndependence"+System.nanoTime()
                shell("am start -W -n com.zane.probe/.ProbeActivity --es url http://203.0.113.9:${origin.localPort}/probe?nonce=$nonce --es nonce $nonce --ez expected_vpn true")
                waitFor("源或独立 C 实际出口 $expected") {shell("logcat -d -s ZaneProbe:I").contains("$nonce=$expected uid=")}
                hops.forEach {assertTrue("缺少实际经过的源/独立链节点",it.observed.any {line->line.contains(nonce)})}
                forbidden.forEach {assertFalse("源节点被派生链影响",it.observed.any {line->line.contains(nonce)})}
            }
            selectAndProbe(1,"BODY_1",listOf(body),listOf(front1,front2,landing1,landing2))
            selectAndProbe(5,"EXIT_5",listOf(landing2),listOf(front1,front2,body,body2))
            selectAndProbe(singleC,"EXIT_5",listOf(body,landing2),listOf(front1,front2,body2))
            assertSourcesUnchanged()
            // This phase intentionally replaces source subscriptions; only creation above must preserve their records.
            var updated=store.snapshot().let {d->d.copy(settings=d.settings+("selectedNodeId" to "$c1")+("selectedGroupId" to "${groupC.id}"))}
            for(round in 1..2) {
                val oldSourceIds=updated.nodes.filter {it.groupId in setOf(1L,3L)}.map {it.id}.toSet()
                val bodyIp=if(round==1)"127.0.0.2" else "127.0.0.3"
                val landingIp=if(round==1)"127.0.0.3" else "127.0.0.2"
                val newBodyA=Socks("BODY_A_R$round",bodyIp);val newBodyB=Socks("BODY_B_R$round",bodyIp)
                val newLandingA=Socks("EXIT_A_R$round",landingIp);val newLandingB=Socks("EXIT_B_R$round",landingIp)
                replacementFixtures.addAll(listOf(newBodyA,newBodyB,newLandingA,newLandingB))
                newBodyA.delays[newLandingA.port]=220;newBodyA.delays[newLandingB.port]=5
                newBodyB.delays[newLandingA.port]=65;newBodyB.delays[newLandingB.port]=350
                fun parsed(name:String,host:String,fixture:Socks)=com.zane.zanebox.subscription.ParsedNode(name,"""{"type":"socks","server":"$host","server_port":${fixture.port}}""")
                fun replaceSubscription(groupId:Long,nodes:List<com.zane.zanebox.subscription.ParsedNode>) {
                    var nextId=(updated.nodes.map {it.id}+updated.groups.map {it.id}+updated.merges.map {it.id}).maxOrNull()!!+1
                    val expected=updated.groups.first {it.id==groupId};val now=System.currentTimeMillis()
                    updated=com.zane.zanebox.subscription.SubscriptionUpdater.begin(updated,expected,"chain-fixture-$round-$groupId",now)
                    val baseline=updated.groups.first {it.id==groupId}
                    updated=com.zane.zanebox.subscription.SubscriptionUpdater.apply(updated,baseline,nodes,"",now+1) {nextId++}
                }
                replaceSubscription(1,listOf(parsed("全新机场甲-$round",bodyIp,newBodyA),parsed("全新机场乙-$round",bodyIp,newBodyB)))
                replaceSubscription(3,listOf(parsed("全新落地甲-$round",landingIp,newLandingA),parsed("全新落地乙-$round",landingIp,newLandingB)))
                assertEquals(setOf("全新机场甲-$round","全新机场乙-$round"),updated.nodes.filter {it.groupId==1L}.map {it.name}.toSet())
                assertEquals(setOf("全新落地甲-$round","全新落地乙-$round"),updated.nodes.filter {it.groupId==3L}.map {it.name}.toSet())
                assertTrue(updated.nodes.filter {it.groupId in setOf(1L,3L)}.none {it.id in oldSourceIds})
                val actualGroup=updated.groups.first {it.id==groupC.id}
                assertEquals(groupC.name,actualGroup.name);assertEquals(1L,derivedGroupSourceId(actualGroup))
                assertEquals(ChainTargets("group:2","group:3"),groupChainTargets(actualGroup))
                assertEquals(setOf(groupC.id),updated.merges.flatMap {it.groupIds}.toSet())
                val sourceA=updated.nodes.first {it.groupId==1L && it.name=="全新机场甲-$round"}
                val landingB=updated.nodes.first {it.groupId==3L && it.name=="全新落地乙-$round"}
                val currentC=updated.nodes.first {it.groupId==groupC.id && derivedNodeSourceId(it)==sourceA.id}
                assertEquals(sourceA.id,JSONObject(currentC.outbound).getJSONArray("node_ids").getLong(0))
                assertEquals(bodyIp,JSONObject(sourceA.outbound).getString("server"));assertEquals(landingIp,JSONObject(landingB.outbound).getString("server"))
                updated=updated.copy(settings=updated.settings+("selectedNodeId" to "${currentC.id}")+("selectedGroupId" to "${groupC.id}"))
                val before=client.snapshot.value.generation
                store.replace(updated);client.autoReload()
                waitFor("第 $round 轮订阅自动应用",60000) {client.snapshot.value.state==2 && client.snapshot.value.generation>before}
                waitFor("第 $round 轮 C 使用全新落地",60000) {JSONObject(client.chainSelections.value).optString("node-${currentC.id}")=="chain-${currentC.id}-landing-${landingB.id}"}
                actualPath("EXIT_B_R$round",front2,newLandingB,newBodyA)
                waitFor("第 $round 轮汇总仍关联 C",60000) {JSONObject(client.chainSelections.value).optString("merge-11")=="node-${currentC.id}"}
                actualPath("EXIT_B_R$round",front2,newLandingB,newBodyA,"203.0.113.11")
                assertTrue("固定单节点 C 被静默删除",store.snapshot().nodes.any {it.id==singleC})
                client.selectNode(singleC);waitFor("选择失效固定 C") {store.snapshot().selectedNodeId==singleC}
                assertTrue("固定 C 丢失源后静默变短链",runCatching {val c=java.net.URL("http://203.0.113.9:${origin.localPort}/orphan").openConnection(proxy) as java.net.HttpURLConnection;try {c.connectTimeout=1500;c.readTimeout=1500;c.inputStream.use {it.readBytes()}}finally {c.disconnect()}}.isFailure)
                selectAndProbe(sourceA.id,"BODY_A_R$round",listOf(newBodyA),listOf(front1,front2,newLandingA,newLandingB))
                assertEquals("{}",store.snapshot().nodes.first {it.id==sourceA.id}.metadata)
                updated=store.snapshot().copy(settings=store.snapshot().settings+("selectedNodeId" to "${currentC.id}")+("selectedGroupId" to "${groupC.id}"))
            }
        } finally {
            client.stop();waitFor("VPN停止") {client.snapshot.value.state==0};client.close();store.replace(original);store.close()
            (listOf(front1,front2,body,body2,landing1,landing2)+replacementFixtures).forEach {it.close()};origin.close();jobs.shutdownNow()
        }
    }

}
