package com.zane.zanebox

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.data.*
import com.zane.zanebox.runtime.ServiceClient
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Fixture A=20ms/B=180ms, /hang=12s. Only runs inside the disposable emulator. */
@RunWith(AndroidJUnit4::class)
class LatencyBatchRegressionTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private fun waitFor(label:String,timeout:Long=15000,predicate:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+timeout
        while(!predicate()) { if(SystemClock.elapsedRealtime()>until)fail("超时 $label");Thread.sleep(20) }
    }
    private fun fixtureConnections():Int {
        val sockets=ins.uiAutomation.executeShellCommand("ss -tn").use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().readText()
        }
        assertTrue("模拟器ss证据不可用",sockets.contains("State"))
        return sockets.lineSequence().count { it.contains("ESTAB") && it.contains("10.0.2.2") && it.contains(":19081") }
    }
    private fun node(id:Long,port:Int=19081)=Node(id,1,"fixture $id","""{"type":"socks","server":"10.0.2.2","server_port":$port}""")
    private fun fixture(nodes:List<Node>,path:String="test",concurrency:Int=12)=AppData(
        nodes=nodes,groups=listOf(Group(1,"fixture")),settings=mapOf("serviceMode" to "proxy","dnsRemote" to "local","sniff" to "false",
            "testUrl" to "http://203.0.113.9:19080/$path","testConcurrency" to concurrency.toString(),"testTimeout" to "10000"))
    @Test fun completionProgressDeadlineCancelAndMutation() {
        val store=ZaneStore(context);val original=store.snapshot();val client=ServiceClient(context)
        val evidence=JSONObject()
        fun snapshot():JSONObject {
            val progress=client.testProgress.value
            return JSONObject().put("results",JSONArray(client.testResults.value.entries.sortedBy { it.key }.map {
                JSONObject().put("id",it.key).put("ping",it.value)
            })).put("testingNodes",JSONArray(client.testingNodes.value.sorted()))
                .put("progress",JSONObject().put("batchId",progress.batchId).put("total",progress.total)
                    .put("completed",progress.completed).put("running",progress.running))
                .put("storeStatus",JSONArray(store.snapshot().nodes.map {
                    JSONObject().put("id",it.id).put("ping",it.ping).put("status",it.status)
                }))
        }
        try {
            assertEquals("12",AppData().setting("testConcurrency"))
            assertEquals("4000",AppData().setting("testTimeout"))
            val mixed=(1L..24L).map { node(it,if(it%2L==0L)19082 else 19081) }
            store.replace(fixture(mixed,concurrency=4));client.connect()
            kotlinx.coroutines.runBlocking { client.readSnapshot() }
            val mixedStarted=SystemClock.elapsedRealtime()
            evidence.put("mixedConcurrency",4)
            client.testNodes(mixed.map { it.id })
            waitFor("批次开始") { client.testingNodes.value.isNotEmpty() }
            waitFor("首批部分完成") {
                val completed=client.testResults.value.keys
                completed.isNotEmpty() && client.testingNodes.value.isNotEmpty() &&
                    completed.none { it in client.testingNodes.value } && client.testProgress.value.completed in 1..23
            }
            evidence.put("firstPartial",snapshot())
            val partial=client.testResults.value.keys
            assertTrue("完成节点仍显示测试中",partial.none { it in client.testingNodes.value })
            val progress=client.testProgress.value
            assertEquals(24,progress.total)
            assertTrue("进度未推进",progress.completed in 1..23)
            assertTrue(progress.running)
            waitFor("混合批次结束",20000) { client.testResults.value.size==24 && client.testingNodes.value.isEmpty() && !client.testProgress.value.running && client.testProgress.value.completed==24 }
            evidence.put("mixedBatchMs",SystemClock.elapsedRealtime()-mixedStarted)
            assertEquals(24,client.testProgress.value.completed)
            assertFalse(client.testProgress.value.running)
            evidence.put("partialResultsAndProgress",true)

            store.replace(fixture(listOf(node(31)),"hang"))
            val started=SystemClock.elapsedRealtime();client.testNodes(listOf(31))
            waitFor("保存10秒值的实际4秒截止",7000) { client.testResults.value.containsKey(31) && client.testingNodes.value.isEmpty() }
            val elapsed=SystemClock.elapsedRealtime()-started
            assertTrue("配置/清理/反馈含合理余量: $elapsed",elapsed<6500)
            assertTrue(client.testResults.value.getValue(31)<0)
            assertEquals("10000",store.snapshot().setting("testTimeout"))
            evidence.put("legacyTimeoutBatchMs",elapsed)

            store.replace(fixture(listOf(node(41),node(42)),"hang"))
            client.testNodes(listOf(41));waitFor("取消前已启动") { 41L in client.testingNodes.value }
            waitFor("挂起请求真实TCP连接") { fixtureConnections()>0 }
            client.cancelTests()
            store.update { it.copy(settings=it.settings+("testUrl" to "http://203.0.113.9:19080/probe")) }
            client.testNodes(listOf(42))
            waitFor("立即重测完成") { client.testResults.value.keys==setOf(42L) && client.testingNodes.value.isEmpty() && !client.testProgress.value.running && client.testProgress.value.completed==1 }
            waitFor("取消及重测后网络请求停止",2500) { fixtureConnections()==0 }
            evidence.put("cancelClosedFixtureSockets",true)
            Thread.sleep(4500) // Cross the superseded batch's deadline; late writes must stay rejected.
            assertEquals(setOf(42L),client.testResults.value.keys)
            assertEquals(0,store.snapshot().nodes.first { it.id==41L }.status)
            assertTrue(client.testResults.value.getValue(42)>0)
            assertFalse(client.testProgress.value.running)
            assertEquals(1,client.testProgress.value.total)
            assertEquals(1,client.testProgress.value.completed)
            evidence.put("cancelImmediateRetest",true)

            store.replace(fixture(listOf(node(61),node(62)),"hang"))
            client.testNodes(listOf(61));waitFor("双取消前A批次启动") { 61L in client.testingNodes.value }
            waitFor("双取消前A网络探测开始") { fixtureConnections()>0 }
            client.testNodes(listOf(62));client.cancelTests()
            waitFor("active及pending均取消",5000) {
                client.testingNodes.value.isEmpty() && !client.testProgress.value.running && fixtureConnections()==0
            }
            Thread.sleep(500)
            assertTrue("已取消两批次不得回流结果",client.testResults.value.isEmpty())
            assertTrue(client.testingNodes.value.isEmpty())
            assertFalse(client.testProgress.value.running)
            assertTrue(store.snapshot().nodes.all { it.status==0 })
            evidence.put("activeAndPendingCancel",true)

            store.replace(fixture(listOf(node(51),node(52)),"hang"))
            client.testNodes(listOf(51,52));waitFor("修改前测速开始") { client.testingNodes.value.containsAll(listOf(51L,52L)) }
            Thread.sleep(250)
            val changed=node(52,19082).copy(ping=777,status=3)
            store.update { it.copy(nodes=listOf(changed)) }
            waitFor("旧配置批次结束") { client.testingNodes.value.isEmpty() }
            val remaining=store.snapshot().nodes.single()
            assertEquals(changed.outbound,remaining.outbound)
            assertEquals(777,remaining.ping)
            assertEquals(3,remaining.status)
            assertFalse(store.snapshot().nodes.any { it.id==51L })
            evidence.put("deletedAndModifiedResultsRejected",true).put("passed",true)
        } catch(failure:Throwable) {
            evidence.put("passed",false).put("failureClass",failure.javaClass.name).put("failureSnapshot",snapshot())
            throw failure
        } finally {
            File(context.getExternalFilesDir(null),"latency-batch-regression.json").writeText(evidence.toString(2))
            client.cancelTests();client.close();store.replace(original);store.close()
        }
    }
    /** Synthetic fixture setup for the disposable emulator's UI/release acceptance. */
    @Test fun seedHomeUi() {
        val skin=InstrumentationRegistry.getArguments().getString("uiSkin","prism")
        val data=AppData(nodes=(1001L..1100L).map { node(it) },groups=listOf(Group(1,"延迟验收")),
            settings=mapOf("selectedNodeId" to "1001","selectedGroupId" to "1","serviceMode" to "proxy",
                "dnsRemote" to "local","sniff" to "false","testUrl" to "http://203.0.113.9:19080/hang",
                "testTimeout" to "10000","appLanguage" to "zh-CN","uiSkin" to skin))
        val store=ZaneStore(context)
        try { store.replace(data) } finally { store.close() }
    }

}
