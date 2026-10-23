package com.zane.zanebox

import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.config.ConfigBuilder
import com.zane.zanebox.config.Purpose
import com.zane.zanebox.core.NativePlatform
import com.zane.zanebox.core.SingBoxEngine
import com.zane.zanebox.data.AppData
import com.zane.zanebox.data.Group
import com.zane.zanebox.data.Node
import com.zane.zanebox.data.ZaneStore
import com.zane.zanebox.runtime.ServiceClient
import com.zane.zanebox.subscription.SubscriptionParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Root-injected private fixtures live only in the app files dir:
 * ninja-source.yaml (full source subscription) and ninja-fixture.json
 * ({expectedExitIp, nodes:[{name, outbound}]}). Nothing here prints either file.
 */
class NinjaCompatibilityTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val baseSettings=mapOf(
        "appLanguage" to "zh-CN",
        "serviceMode" to "vpn",
        "routeMode" to "rule",
        "selectedNodeId" to "1",
        "dnsRemote" to "local",
        "dnsDirect" to "local",
        "sniff" to "false",
        "statsEnabled" to "false",
        "bypassLan" to "false",
        "bypassLanInCore" to "false"
    )
    private fun shell(command:String)=ins.uiAutomation.executeShellCommand(command).use{p->java.io.FileInputStream(p.fileDescriptor).bufferedReader().readText()}
    private fun waitFor(label:String,timeout:Long=45000,condition:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+timeout
        while(!condition()) {if(SystemClock.elapsedRealtime()>deadline)fail("超时：$label");Thread.sleep(100)}
    }
    private fun readInjected(name:String):String {
        val file=File(context.filesDir,name)
        assertTrue("缺少 root 注入的 $name",file.isFile)
        return file.readText()
    }
    private fun fixture()=JSONObject(readInjected("ninja-fixture.json"))
    private fun fixtureNode(fixture:JSONObject,name:String):JSONObject {
        val nodes=fixture.getJSONArray("nodes")
        return (0 until nodes.length()).map{nodes.getJSONObject(it)}.firstOrNull{it.optString("name")==name}
            ?: error("fixture 缺少节点 $name")
    }
    private fun testConfig(outbound:String)=ConfigBuilder.build(
        AppData(nodes=listOf(Node(1,1,"fixture",outbound)),groups=listOf(Group(1,"fixture")),settings=baseSettings),
        Purpose.TEST,1
    )
    private fun summary(name:String,value:JSONObject) {
        val dir=requireNotNull(context.getExternalFilesDir(null)){"外部文件目录不可用"}
        File(dir,name).writeText(value.toString(2))
    }
    private fun packageUid(name:String):Int {
        val output=shell("cmd package list packages -U $name")
        val line=output.lineSequence().map{it.trim()}.firstOrNull{it.startsWith("package:$name ")}
            ?: error("无法读取 $name 的 UID")
        return Regex("\\buid:(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
            ?: error("无法读取 $name 的 UID")
    }

    @Test
    fun sourceYamlImportsAllNodesAndOneNativeConfigCheck() {
        val report=SubscriptionParser.parseReport(readInjected("ninja-source.yaml"))
        assertEquals(50,report.nodes.size)
        assertEquals(0,report.skipped)
        assertNull(report.firstError)
        var ws=0
        report.nodes.forEachIndexed { index,node ->
            val o=JSONObject(node.outbound)
            val label="节点 ${index+1}"
            assertEquals(label,"ninja",o.optString("type"))
            assertTrue("$label 缺少服务器",o.optString("server").isNotBlank())
            assertTrue("$label 端口无效",o.optInt("server_port") in 1..65535)
            assertTrue("$label 缺少加密方法",o.optString("method").isNotBlank())
            assertTrue("$label 缺少密码",o.optString("password").isNotBlank())
            assertTrue("$label 缺少节点密码",o.optString("node_password").isNotBlank())
            assertTrue("$label pass 非布尔或未启用",o.opt("pass") is Boolean && o.optBoolean("pass"))
            val pass=o.getJSONObject("pass_options")
            assertTrue("$label pass 缺少方法",pass.optString("method").isNotBlank())
            assertTrue("$label pass 缺少密码",pass.optString("password").isNotBlank())
            assertTrue("$label 填充最小值无效",pass.optInt("padding_min") in 0..65535)
            assertTrue("$label 填充最大值无效",pass.optInt("padding_random_max") in 0..65535)
            assertTrue("$label 填充总量超限",pass.optInt("padding_min")+pass.optInt("padding_random_max")<=65535)
            assertEquals("$label 填充模式无效","min_random",pass.optString("padding_mode"))
            assertTrue("$label TLS 未启用",o.optJSONObject("tls")?.optBoolean("enabled")==true)
            val transport=o.optJSONObject("transport")
            if(transport!=null) {
                ws++
                assertEquals("$label transport 非 ws","ws",transport.optString("type"))
                assertTrue("$label ws 路径无效",transport.optString("path").isNotBlank())
                assertTrue("$label ws Host 无效",transport.optJSONObject("headers")?.optString("Host").orEmpty().isNotBlank())
            }
            assertTrue("$label UoT 未启用",o.optJSONObject("udp_over_tcp")?.optBoolean("enabled")==true)
            assertEquals("$label UoT 版本非 2",2,o.getJSONObject("udp_over_tcp").optInt("version"))
            assertFalse("$label 不应带 network",o.has("network"))
        }
        assertEquals(47,report.nodes.size-ws)
        assertEquals(3,ws)
        val data=AppData(nodes=report.nodes.mapIndexed{i,n->Node((i+1).toLong(),1,n.name,n.outbound)},groups=listOf(Group(1,"ninja-source")),settings=baseSettings)
        assertTrue("不继承源规则",data.rules.isEmpty())
        assertEquals("不继承源分组",1,data.groups.size)
        val config=ConfigBuilder.build(data,Purpose.MAIN,1)
        val outbounds=JSONObject(config).getJSONArray("outbounds")
        assertEquals(50,(0 until outbounds.length()).count{outbounds.getJSONObject(it).optString("type")=="ninja"})
        assertEquals(2,(0 until outbounds.length()).count{outbounds.getJSONObject(it).optString("type")=="selector"})
        val client=ServiceClient(context)
        client.connect()
        try {
            val key="ninja-all-source"
            client.validateConfig(key,config)
            waitFor("50 节点原生配置校验",60000){key in client.validationResults.value}
            assertEquals("原生拒绝 50 节点配置", "",client.validationResults.value[key])
        } finally {client.close()}
        summary("ninja-import-summary.json",JSONObject().put("sourceNodes",report.nodes.size).put("skipped",report.skipped)
            .put("tcp",report.nodes.size-ws).put("ws",ws).put("ninjaOutbounds",50).put("nativeValidation",""))
    }

    @Test
    fun realNinjaTcpAndControlledWsLatencyAndTamperedNodePassword() {
        val fixture=fixture()
        val platform=NativePlatform(context,null){_,_->}
        platform.initialize()
        val engine=SingBoxEngine(platform)
        val results=JSONObject()
        try {
            listOf("TCP real","WS controlled").forEach { name ->
                val outbound=fixtureNode(fixture,name).getJSONObject("outbound")
                assertEquals("$name 不是 Ninja", "ninja",outbound.optString("type"))
                assertTrue("$name fixture 未启用 pass",outbound.optBoolean("pass"))
                assertTrue("$name fixture 缺少节点密码",outbound.optString("node_password").isNotBlank())
                val latency=engine.test(testConfig(outbound.toString()),GENERATE_204,20000)
                assertTrue("$name 真实延迟无效：$latency",latency>0)
                val tampered=JSONObject(outbound.toString()).put("node_password","tampered.invalid").toString()
                val failed=runCatching{engine.test(testConfig(tampered),GENERATE_204,8000)}
                assertTrue("$name 篡改 NodePassword 仍成功：${failed.getOrNull()}",failed.isFailure||failed.getOrNull()==null||failed.getOrNull()!!<=0)
                val restored=engine.test(testConfig(outbound.toString()),GENERATE_204,20000)
                assertTrue("$name 恢复原值后延迟无效：$restored",restored>0)
                results.put(name,JSONObject().put("latency",latency).put("restored",restored).put("tamperedRejected",true))
            }
        } finally {engine.cancelTests();platform.close()}
        summary("ninja-engine-summary.json",results)
    }

    @Test
    fun vpnExitSwitchUdpDnsAndLegacySocksRecovery() {
        val fixture=fixture()
        val expectedExitIp=fixture.getString("expectedExitIp")
        val tcp=fixtureNode(fixture,"TCP real");val ws=fixtureNode(fixture,"WS controlled");val legacy=fixtureNode(fixture,"legacy SOCKS")
        val runtimeSettings=baseSettings+mapOf("routeMode" to "global","dnsRemote" to "udp://1.1.1.1:53","fakeDns" to "false","sniff" to "true","logLevel" to "debug")
        val data=AppData(
            nodes=listOf(
                Node(1,1,tcp.optString("name"),tcp.getJSONObject("outbound").toString()),
                Node(2,1,ws.optString("name"),ws.getJSONObject("outbound").toString()),
                Node(3,1,legacy.optString("name"),legacy.getJSONObject("outbound").toString())
            ),
            groups=listOf(Group(1,"ninja fixture")),settings=runtimeSettings
        )
        val store=ZaneStore(context);store.replace(data)
        val client=ServiceClient(context);client.connect()
        var probeUid=0
        var previousVpnNetwork=""
        var expectNewNetwork=true
        val summary=JSONObject()
        fun launch(url:String,nonce:String,dns:String?=null,expectedVpn:Boolean=true) {
            val dnsExtra=if(dns.isNullOrBlank())"" else " --es dns_server $dns"
            val previous=if(expectedVpn&&expectNewNetwork&&previousVpnNetwork.isNotBlank())" --es previous_vpn_network $previousVpnNetwork" else ""
            if(expectedVpn) expectNewNetwork=false
            shell("am start -W -n com.zane.probe/.ProbeActivity --es url $url --es nonce $nonce$dnsExtra --ez expected_vpn $expectedVpn$previous")
        }
        fun await(nonce:String,label:String,done:(String)->Boolean):String {
            val deadline=SystemClock.elapsedRealtime()+45000;var logs=""
            while(SystemClock.elapsedRealtime()<deadline) {
                logs=shell("logcat -d -s ZaneProbe:I")
                if(done(logs))return logs
                val errorLine=logs.lineSequence().firstOrNull{it.contains("$nonce=ERROR:")}
                if(errorLine!=null) {
                    val detail=errorLine.substringAfter("ZaneProbe:",errorLine).trim()
                    throw AssertionError("Probe 失败（$label）：$detail")
                }
                Thread.sleep(200)
            }
            error("超时：$label（$nonce 未产生预期日志）")
        }
        fun rememberNetwork(logs:String,nonce:String) {
            val handle=Regex(Regex.escape(nonce)+" READY network=(\\d+) vpn=true").find(logs)?.groupValues?.get(1)
            assertNotNull("Probe 未确认默认 VPN 网络：$nonce",handle)
            previousVpnNetwork=handle!!
        }
        fun switchTo(id:Long,label:String) {
            store.replace(data.copy(settings=runtimeSettings+("selectedNodeId" to id.toString())))
            expectNewNetwork=true
            val generation=client.snapshot.value.generation
            client.reload()
            waitFor("$label 重载"){client.snapshot.value.state==2&&client.snapshot.value.generation>generation}
        }
        try {
            shell("appops set ${context.packageName} ACTIVATE_VPN allow")
            probeUid=packageUid("com.zane.probe")
            assertTrue("Probe 必须运行在独立 UID",probeUid!=Process.myUid())
            val configRoot=JSONObject(ConfigBuilder.build(data,Purpose.MAIN,1))
            val dns=configRoot.getJSONObject("dns")
            val dnsServers=(0 until dns.getJSONArray("servers").length()).map{dns.getJSONArray("servers").getJSONObject(it)}
            val remote=dnsServers.firstOrNull{it.optString("tag")=="dns-remote"} ?: error("缺少 dns-remote")
            assertEquals("DNS remote 类型","udp",remote.optString("type"))
            assertEquals("DNS remote 服务器","1.1.1.1",remote.optString("server"))
            assertEquals("DNS remote 端口",53,remote.optInt("server_port"))
            assertEquals("DNS remote detour","proxy",remote.optString("detour"))
            assertEquals("DNS final","dns-remote",dns.optString("final"))
            assertEquals("路由 final","proxy",configRoot.getJSONObject("route").optString("final"))
            val routeRules=configRoot.getJSONObject("route").getJSONArray("rules")
            val sniffIndex=(0 until routeRules.length()).firstOrNull{routeRules.getJSONObject(it).optString("action")=="sniff"} ?: error("缺少 sniff 规则")
            val hijackIndex=(0 until routeRules.length()).firstOrNull{routeRules.getJSONObject(it).optString("protocol")=="dns"&&routeRules.getJSONObject(it).optString("action")=="hijack-dns"} ?: error("缺少 DNS hijack 规则")
            assertTrue("sniff 必须在 DNS hijack 前",sniffIndex<hijackIndex)
            assertTrue("FakeIP 服务器仍启用",dnsServers.none{it.optString("type")=="fakeip"})
            assertFalse("DNS 规则仍引用 FakeIP",dns.getJSONArray("rules").toString().contains("dns-fake"))
            val wsRoot=JSONObject(ConfigBuilder.build(data.copy(settings=runtimeSettings+("selectedNodeId" to "2")),Purpose.MAIN,2))
            val wsOutbounds=(0 until wsRoot.getJSONArray("outbounds").length()).map{wsRoot.getJSONArray("outbounds").getJSONObject(it)}
            val proxy=wsOutbounds.firstOrNull{it.optString("tag")=="proxy"} ?: error("缺少 proxy")
            assertEquals("DNS 代理出口不是 WS 节点","node-2",proxy.optString("default"))
            val wsNode=wsOutbounds.firstOrNull{it.optString("tag")=="node-2"} ?: error("缺少 node-2")
            assertEquals("DNS 代理出口不是 Ninja","ninja",wsNode.optString("type"))
            assertTrue("Ninja 出口未启用 UoT",wsNode.optJSONObject("udp_over_tcp")?.optBoolean("enabled")==true)
            client.start()
            waitFor("VPN 启动",60000){client.snapshot.value.state==2||client.snapshot.value.state==4}
            assertEquals(client.snapshot.value.error,2,client.snapshot.value.state)
            for ((id,label) in listOf(1L to "TCP real",2L to "WS controlled")) {
                switchTo(id,label)
                val traceNonce="trace"+id+System.nanoTime()
                launch(TRACE,traceNonce)
                val traceLogs=await(traceNonce,"$label trace"){it.contains("$traceNonce HTTP=")&&Regex(Regex.escape(traceNonce)+"=[\\s\\S]*?uid=").containsMatchIn(it)}
                assertTrue("$label trace HTTP 非 200",traceLogs.contains("$traceNonce HTTP=200"))
                val body=Regex(Regex.escape(traceNonce)+"=([\\s\\S]*?) uid=").find(traceLogs)?.groupValues?.get(1).orEmpty()
                assertEquals("$label 实际出口不符",expectedExitIp,Regex("ip=([^\\s]+)").find(body)?.groupValues?.get(1))
                rememberNetwork(traceLogs,traceNonce)
                val codeNonce="code"+id+System.nanoTime()
                launch(GENERATE_204,codeNonce)
                val codeLogs=await(codeNonce,"$label Google 204"){it.contains("$codeNonce HTTP=")&&it.contains("$codeNonce= uid=$probeUid")}
                assertTrue("$label Google 204 实际 code 不符",codeLogs.contains("$codeNonce HTTP=204"))
                rememberNetwork(codeLogs,codeNonce)
                summary.put(label,true)
            }
            val dnsNonce="dns"+System.nanoTime()
            launch(GENERATE_204,dnsNonce,dns="1.1.1.1")
            val dnsLogs=await(dnsNonce,"UDP DNS 经 VPN"){it.contains("$dnsNonce=DNS_OK uid=$probeUid")||it.contains("$dnsNonce=ERROR:")}
            assertTrue("独立 UID UDP DNS 未经 VPN 成功",dnsLogs.contains("$dnsNonce=DNS_OK uid=$probeUid"))
            rememberNetwork(dnsLogs,dnsNonce)
            summary.put("udpDns",true)
            switchTo(3,"legacy SOCKS")
            val exitNonce="exit"+System.nanoTime()
            launch(FIXTURE_PROBE,exitNonce)
            val exitLogs=await(exitNonce,"legacy SOCKS 出口"){it.contains("$exitNonce=EXIT_A uid=$probeUid")||it.contains("$exitNonce=ERROR:")}
            assertTrue("legacy SOCKS 未经 VPN 出口",exitLogs.contains("$exitNonce=EXIT_A uid=$probeUid"))
            rememberNetwork(exitLogs,exitNonce)
            summary.put("legacySocks",true)
            client.stop()
            waitFor("VPN 停止",30000){client.snapshot.value.state==0}
            val directNonce="direct"+System.nanoTime()
            launch(FIXTURE_PROBE,directNonce,expectedVpn=false)
            val directLogs=await(directNonce,"DIRECT 回退"){it.contains("$directNonce=DIRECT uid=$probeUid")||it.contains("$directNonce=ERROR:")}
            assertTrue("服务停止后未回到 DIRECT",directLogs.contains("$directNonce=DIRECT uid=$probeUid"))
            summary.put("directAfterStop",true)
            summary("ninja-vpn-summary.json",summary.put("google204",true).put("expectedExitMatch",true))
        } finally {client.stop();Thread.sleep(500);client.close();store.close()}
    }

    companion object {
        private const val GENERATE_204="https://www.gstatic.com/generate_204"
        private const val TRACE="https://www.cloudflare.com/cdn-cgi/trace"
        private const val FIXTURE_PROBE="http://10.0.2.2:19080/probe"
    }
}
