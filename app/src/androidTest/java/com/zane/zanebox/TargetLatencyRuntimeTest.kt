package com.zane.zanebox

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.config.builtinSmartRuleFiles
import com.zane.zanebox.data.*
import com.zane.zanebox.runtime.ServiceClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

internal object TargetLatencyFixture {
    const val A=6101L
    const val B=6102L
    const val GROUP=6101L
    const val MANUAL=6105L
    const val AUTO=6106L
    fun data(mode:String="vpn")=AppData(
        nodes=listOf(
            Node(A,GROUP,"出口 A","""{"type":"socks","server":"10.0.2.2","server_port":19081}""",ping=900,status=3),
            Node(B,GROUP,"出口 B","""{"type":"socks","server":"10.0.2.2","server_port":19082}""",ping=5,status=3),
            Node(6103,6103,"停用节点","""{"type":"socks","server":"10.0.2.2","server_port":19999}""")),
        groups=listOf(Group(GROUP,"延迟验收订阅","https://example.test/latency"),Group(6103,"停用分组",enabled=false)),
        merges=listOf(MergeGroup(MANUAL,"手动汇总",nodeIds=listOf(A,B,6103),groupIds=listOf(GROUP),selectedId=B),
            MergeGroup(AUTO,"自动汇总",groupIds=listOf(GROUP),mode="urltest",selectedId=B)),
        settings=builtinSmartRuleFiles.keys.associate { "smart.$it.target" to "off" }+mapOf(
            "appLanguage" to "zh-CN","serviceMode" to mode,"selectedNodeId" to "$B","selectedGroupId" to "$GROUP",
            "browseGroupId" to "$GROUP","homeAutoSelect" to "false","dnsRemote" to "local","dnsDirect" to "local",
            "sniff" to "false","bypassLan" to "false","bypassLanInCore" to "false","statsEnabled" to "false",
            "testUrl" to "http://203.0.113.9:19080/test","testTimeout" to "4000","testConcurrency" to "12",
            "urlTestInterval" to "10m","urlTestTolerance" to "60000","rulesUpdateInterval" to "off",
            "smart.youtube.target" to "group:$GROUP","smartRules.youtube" to "IP-CIDR,203.0.113.9/32",
            "smart.telegram.target" to "merge:$MANUAL","smartRules.telegram" to "IP-CIDR,203.0.113.10/32",
            "smart.netflix.target" to "merge:$AUTO","smartRules.netflix" to "IP-CIDR,203.0.113.11/32",
            "smart.ai.target" to "auto","smartRules.ai" to "IP-CIDR,203.0.113.12/32"))
}

class TargetLatencyRuntimeTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private fun shell(command:String)=ins.uiAutomation.executeShellCommand(command).use {
        java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText()
    }
    private fun waitFor(label:String,timeout:Long=15000,predicate:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+timeout
        while(!predicate()) { if(SystemClock.elapsedRealtime()>end)fail("超时：$label");Thread.sleep(50) }
    }
    private fun get(url:String,proxied:Boolean=false):String {
        val proxy=if(proxied)Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",2080)) else Proxy.NO_PROXY
        val connection=URL(url).openConnection(proxy) as HttpURLConnection
        try {connection.connectTimeout=4000;connection.readTimeout=5000;return connection.inputStream.bufferedReader().use{it.readText()}}
        finally {connection.disconnect()}
    }
    private fun delays(a:Int,b:Int) {
        val result=JSONObject(get("http://10.0.2.2:19080/fixture-delay?a=$a&b=$b"))
        assertEquals(a,result.getInt("a"));assertEquals(b,result.getInt("b"))
    }
    private fun measure(client:ServiceClient,target:String,key:String="",ids:Set<Long> = setOf(TargetLatencyFixture.A,TargetLatencyFixture.B)) {
        val batch=client.testProgress.value.batchId
        client.testTarget(target,key)
        waitFor("$target 本轮完整结果",20000) {client.testProgress.value.batchId>batch && !client.testProgress.value.running && client.testResults.value.keys==ids}
        assertEquals(ids.size,client.testProgress.value.total)
        assertEquals(ids.size,client.testProgress.value.completed)
        assertTrue(client.testingNodes.value.isEmpty())
        val observed=JSONObject().put("target",target).put("results",JSONObject(client.testResults.value.mapKeys{it.key.toString()}))
        java.io.File(context.cacheDir,"target-latency-rounds.jsonl").appendText(observed.toString()+"\n")
    }
    private fun winner(client:ServiceClient):Long = client.testResults.value.entries.filter{it.value>0}.sortedWith(compareBy<Map.Entry<Long,Int>>{it.value}.thenBy{it.key}).first().key
    private fun exit(id:Long)=if(id==TargetLatencyFixture.A)"EXIT_A" else "EXIT_B"
    private fun realUid(ip:String,expected:String):String {
        val nonce="targetLatency"+System.nanoTime()
        shell("am start -W -n com.zane.probe/.ProbeActivity --es url http://$ip:19080/probe --es nonce $nonce --ez expected_vpn true")
        var log=""
        waitFor("独立UID出口 $expected") {log=shell("logcat -d -s ZaneProbe:I");log.contains("$nonce=")}
        assertTrue(log,log.contains("$nonce=$expected uid="))
        return Regex("${Regex.escape(nonce)} READY network=(\\d+) vpn=true").find(log)?.groupValues?.get(1)
            ?: error("缺少独立UID VPN网络证据：$log")
    }
    private fun evidence(name:String,value:JSONObject) {
        java.io.File(context.cacheDir,"target-latency-$name.json").writeText(value.toString(2))
    }

    @Test fun runningTargetsUseThisRoundsFastestWithoutRestartingVpnOrChangingHome() {
        val store=ZaneStore(context);val original=store.snapshot();val client=ServiceClient(context)
        val facts=JSONObject()
        try {
            delays(500,40);store.replace(TargetLatencyFixture.data());client.connect()
            shell("appops set com.zane.zanebox ACTIVATE_VPN allow")
            client.start();waitFor("VPN连接",30000){client.snapshot.value.state==2 || client.snapshot.value.state==4}
            assertEquals(client.snapshot.value.error,2,client.snapshot.value.state)
            waitFor("自动组初始选中慢慢交换前B",20000) {runCatching{get("http://203.0.113.11:19080/probe",true)=="EXIT_B"}.getOrDefault(false)}
            val generation=client.snapshot.value.generation
            val pid=shell("pidof com.zane.zanebox:bg").trim()
            val network=realUid("203.0.113.8","EXIT_B")
            delays(40,500)
            for((target,key,ip) in listOf(Triple("group:6101","youtube","203.0.113.9"),Triple("merge:6105","telegram","203.0.113.10"),
                Triple("merge:6106","netflix","203.0.113.11"),Triple("auto","ai","203.0.113.12"))) {
                measure(client,target,key)
                val expected=exit(winner(client))
                waitFor("$target 内核实际最低成功延迟出口 $expected",10000){get("http://$ip:19080/probe",true)==expected}
                assertEquals(network,realUid(ip,expected))
                assertEquals(generation,client.snapshot.value.generation)
                assertEquals(pid,shell("pidof com.zane.zanebox:bg").trim())
                assertEquals(6102L,store.snapshot().selectedNodeId)
                assertEquals(TargetLatencyFixture.data().setting("smart.$key.target"),store.snapshot().setting("smart.$key.target"))
                facts.put(target,JSONObject().put("winner",winner(client)).put("latency",client.testResults.value.getValue(winner(client))).put("exit",expected))
            }
            assertEquals("selector",store.snapshot().merges.first{it.id==6105L}.mode)
            assertEquals("urltest",store.snapshot().merges.first{it.id==6106L}.mode)
            delays(500,40);measure(client,"merge:6106","netflix")
            assertEquals(network,realUid("203.0.113.11",exit(winner(client))))
            measure(client,"node:6102",ids=setOf(6102L))
            assertEquals(6102L,store.snapshot().selectedNodeId)
            assertEquals(network,realUid("203.0.113.8","EXIT_B"))
            facts.put("oppositeRound",true).put("singleNodeOnly",true).put("homeUnchanged",true)
                .put("sameVpnNetwork",network).put("sameBgPid",pid).put("sameGeneration",generation)
            evidence("runtime",facts)
        } finally {client.cancelTests();client.stop();waitFor("VPN停止",30000){client.snapshot.value.state==0};client.close();store.replace(original);store.close();delays(40,500)}
    }

    @Test fun offlineSelectionSurvivesConnectAndRestart() {
        val store=ZaneStore(context);val original=store.snapshot();val client=ServiceClient(context)
        try {
            delays(40,500);store.replace(TargetLatencyFixture.data("proxy"));client.connect()
            val expected=linkedMapOf<String,String>()
            for((target,key,ip) in listOf(Triple("group:6101","youtube","203.0.113.9"),Triple("merge:6105","telegram","203.0.113.10"),
                Triple("merge:6106","netflix","203.0.113.11"),Triple("auto","ai","203.0.113.12"))) {
                measure(client,target,key);expected[ip]=exit(winner(client))
                if(target=="merge:6105")assertEquals(winner(client),store.snapshot().merges.first{it.id==6105L}.selectedId)
            }
            val saved=store.snapshot()
            evidence("offline-before",JSONObject().put("choices",JSONObject(saved.settings.filterKeys{it.startsWith("groupSelection")}))
                .put("pings",JSONObject(saved.nodes.associate{it.id.toString() to it.ping})))
            repeat(2) {
                client.start();waitFor("离线择优连接",30000){client.snapshot.value.state==2 || client.snapshot.value.state==4}
                assertEquals(client.snapshot.value.error,2,client.snapshot.value.state)
                for((ip,route) in expected)assertEquals("离线启动第${it+1}轮，目标 $ip，已保存="+saved.settings.filterKeys{key->key.startsWith("groupSelection")},route,get("http://$ip:19080/probe",true))
                assertEquals("EXIT_B",get("http://203.0.113.8:19080/probe",true))
                client.stop();waitFor("离线重启间停止",30000){client.snapshot.value.state==0}
            }
            assertEquals(6102L,store.snapshot().selectedNodeId)
            evidence("offline",JSONObject().put("groupMergeAutoConnectAndRestart",true).put("expectedMeasuredExits",JSONObject(expected)).put("homeUnchanged",true))
        } finally {client.cancelTests();client.stop();client.close();store.replace(original);store.close()}
    }

    @Test fun allFailedCancelledAndChangedTargetsCannotApplyOldChoice() {
        val store=ZaneStore(context);val original=store.snapshot();val client=ServiceClient(context)
        val base=TargetLatencyFixture.data("proxy")
        try {
            delays(500,40);store.replace(base);client.connect()
            measure(client,"merge:6106")
            val beforeFailure=winner(client)
            store.putSetting("testUrl","http://203.0.113.9:19080/hang")
            measure(client,"merge:6106")
            assertTrue(client.testResults.value.values.all{it<=0})
            client.start();waitFor("全失败后恢复原URLTest选择",30000){client.snapshot.value.state==2 || client.snapshot.value.state==4}
            assertEquals(client.snapshot.value.error,2,client.snapshot.value.state)
            assertEquals(exit(beforeFailure),get("http://203.0.113.11:19080/probe",true))
            client.stop();waitFor("失败恢复检查后停止",30000){client.snapshot.value.state==0}

            store.replace(base.copy(settings=base.settings+("testUrl" to "http://203.0.113.9:19080/hang")));client.connect()
            measure(client,"merge:6105")
            assertTrue(client.testResults.value.values.all{it<=0})
            assertEquals(6102L,store.snapshot().merges.first{it.id==6105L}.selectedId)

            store.replace(base.copy(settings=base.settings+("testUrl" to "http://203.0.113.9:19080/hang")))
            client.testTarget("merge:6105");waitFor("取消前批次开始"){client.testingNodes.value.isNotEmpty()}
            client.cancelTests()
            store.update{it.copy(settings=it.settings+("testUrl" to base.setting("testUrl")))}
            measure(client,"node:6102",ids=setOf(6102L))
            Thread.sleep(4300)
            assertEquals(setOf(6102L),client.testResults.value.keys)
            assertEquals(6102L,store.snapshot().merges.first{it.id==6105L}.selectedId)

            delays(500,700);store.replace(base)
            client.testTarget("merge:6105","telegram");waitFor("改目标前批次开始"){client.testingNodes.value.isNotEmpty()}
            store.putSetting("smart.telegram.target","node:6102")
            waitFor("改目标后批次结束",15000){!client.testProgress.value.running}
            assertEquals("node:6102",store.snapshot().setting("smart.telegram.target"))
            assertEquals(6102L,store.snapshot().merges.first{it.id==6105L}.selectedId)

            store.replace(base);client.testTarget("merge:6105");waitFor("改成员前批次开始"){client.testingNodes.value.isNotEmpty()}
            store.update{it.copy(merges=it.merges.map{m->if(m.id==6105L)m.copy(nodeIds=listOf(6102),groupIds=emptyList())else m})}
            waitFor("改成员后批次结束",15000){!client.testProgress.value.running}
            assertEquals(6102L,store.snapshot().merges.first{it.id==6105L}.selectedId)
            assertEquals(listOf(6102L),store.snapshot().merges.first{it.id==6105L}.nodeIds)
            evidence("isolation",JSONObject().put("allFailedKeepsSelection",true).put("cancelThenSingleRound",true)
                .put("changedPolicyKeepsNewTarget",true).put("changedMembersRejectOldChoice",true))
        } finally {client.cancelTests();client.stop();waitFor("隔离检查后服务停止",30000){client.snapshot.value.state==0};client.close();store.replace(original);store.close();delays(40,500)}
    }

    @Test fun seedForMaestro() {
        val store=ZaneStore(context)
        try {store.replace(TargetLatencyFixture.data("proxy"))} finally {store.close()}
    }
}
