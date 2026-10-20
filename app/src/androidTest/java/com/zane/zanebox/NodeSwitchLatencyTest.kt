package com.zane.zanebox

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.data.*
import com.zane.zanebox.runtime.ServiceClient
import com.zane.zanebox.ui.nodeComparator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Real runtime + batch pipeline: each effective home switch starts exactly one quiet single-node test. Needs the emulator latency fixture. */
@RunWith(AndroidJUnit4::class)
class NodeSwitchLatencyTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun waitFor(label:String,timeout:Long=20000,predicate:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+timeout
        while(!predicate()) { if(SystemClock.elapsedRealtime()>until)fail("超时 $label");Thread.sleep(20) }
    }
    private fun node(id:Long,port:Int)=Node(id,7101,"switch $id","""{"type":"socks","server":"10.0.2.2","server_port":$port}""")
    private val data=AppData(
        nodes=listOf(node(7101,19081),node(7102,19082),node(7103,19999)),groups=listOf(Group(7101,"switch")),
        settings=mapOf("serviceMode" to "proxy","dnsRemote" to "local","sniff" to "false","homeAutoSelect" to "false",
            "selectedNodeId" to "7101","selectedGroupId" to "7101","testUrl" to "http://203.0.113.9:19080/test","testTimeout" to "4000","testConcurrency" to "12"))

    @Test fun disabledSwitchStartsNoTestButManualTestsStillWork() {
        ZaneStore(context).use { store ->
            val original=store.snapshot();val client=ServiceClient(context)
            try {
                store.replace(data.copy(settings=data.settings+("autoTestSelectedNode" to "false")));client.connect()
                kotlinx.coroutines.runBlocking {client.readSnapshot()}
                val base=client.testProgress.value.batchId
                client.selectNode(7102)
                waitFor("关闭开关仍能选择节点") {store.snapshot().selectedNodeId==7102L}
                Thread.sleep(1200)
                assertEquals(base,client.testProgress.value.batchId)
                assertEquals(-1,store.snapshot().nodes.first {it.id==7102L}.ping)
                client.testNodes(listOf(7102))
                waitFor("关闭自动测速不影响手动测速") {client.testProgress.value.batchId>base && !client.testProgress.value.running && client.testResults.value[7102L]?.let {it>0}==true}
                store.putSetting("autoTestSelectedNode","true")
                val manual=client.testProgress.value.batchId
                client.selectNode(7101)
                waitFor("重新开启立即生效") {client.testProgress.value.batchId> manual && !client.testProgress.value.running && client.testResults.value[7101L]?.let {it>0}==true}
            } finally {client.cancelTests();client.close();store.replace(original)}
        }
    }

    @Test fun automaticSwitchUsesUnifiedDelayAndPreservesRankAcrossReloads() {
        ZaneStore(context).use { store ->
            val original=store.snapshot();val client=ServiceClient(context)
            val results=org.json.JSONObject()
            try {
                client.connect();kotlinx.coroutines.runBlocking {client.readSnapshot()}
                for(unified in listOf(false,true)) {
                    val source=data.copy(nodes=listOf(node(7101,19081).copy(ping=100,order=0,status=3),node(7102,19092).copy(ping=2000,order=1,status=3)),
                        settings=data.settings+("autoTestSelectedNode" to "true")+("unifiedDelay" to unified.toString())+("sort_group_7101" to "true")+("testUrl" to "http://203.0.113.9:19093/measure"))
                    store.replace(source)
                    val base=client.testProgress.value.batchId
                    client.selectNode(7102)
                    waitFor("自动测速统一延迟=$unified") {client.testProgress.value.batchId>base && !client.testProgress.value.running && client.testResults.value[7102L]?.let {it>0}==true}
                    val updated=store.snapshot();val ping=updated.nodes.last().ping
                    results.put(unified.toString(),ping)
                    if(unified)assertTrue("开启后排除首次握手 $ping",ping in 1..300)
                    else assertTrue("关闭后保留首次握手 $ping",ping>=350)
                    assertEquals("保持延迟升序模式", "true",updated.setting("sort_group_7101"))
                    fun order(d:AppData)=d.nodes.sortedWith(nodeComparator(d,d.groups.single())).map {it.id}
                    assertEquals(listOf(7101L,7102L),order(updated))
                    ZaneStore(context).use {reopened->assertEquals("重开仍保持原顺序",listOf(7101L,7102L),order(reopened.snapshot()))}
                    if(unified) {
                        val round=client.testProgress.value.batchId
                        client.testNodes(listOf(7102))
                        waitFor("手动重测可更新排序") {client.testProgress.value.batchId>round && !client.testProgress.value.running}
                        assertEquals(listOf(7102L,7101L),order(store.snapshot()))
                    }
                }
                java.io.File(context.getExternalFilesDir(null),"switch-unified-delay.json").writeText(results.toString())
            } finally {client.cancelTests();client.close();store.replace(original)}
        }
    }

    @Test fun everyEffectiveSwitchTestsOnceAndResultsStayWithTheirRound() {
        val store=ZaneStore(context);val original=store.snapshot();val client=ServiceClient(context)
        val messages=CopyOnWriteArrayList<String>()
        val scope=CoroutineScope(Dispatchers.Default)
        try {
            store.replace(data);client.connect()
            kotlinx.coroutines.runBlocking { client.readSnapshot() }
            scope.launch { client.events.collect { messages.add(it) } }
            val base=client.testProgress.value.batchId
            fun settled(batches:Long)=client.testProgress.value.batchId==base+batches && !client.testProgress.value.running && client.testingNodes.value.isEmpty()

            // Re-clicking the selected node is not a switch; the next real switch is then the first batch.
            client.selectNode(7101);client.selectNode(7102)
            waitFor("A→B 一轮完成") { settled(1) && client.testResults.value.keys==setOf(7102L) }
            assertEquals(7102L,store.snapshot().selectedNodeId)
            assertTrue(client.testResults.value.getValue(7102)>0)

            // B→A and back: returning to a node tests it again.
            client.selectNode(7101)
            waitFor("B→A 一轮完成") { settled(2) && client.testResults.value.keys==setOf(7101L) }
            assertTrue(client.testResults.value.getValue(7101)>0)

            // Fast A→B→A: both successful switches run a round; the earlier round's results are dropped, only A remains.
            client.selectNode(7102);client.selectNode(7101)
            waitFor("快速 A→B→A 两轮") { settled(4) }
            assertEquals(setOf(7101L),client.testResults.value.keys)
            assertEquals(7101L,store.snapshot().selectedNodeId)

            // An unreachable node reports a failure for itself.
            client.selectNode(7103)
            waitFor("不可达节点失败") { settled(5) && client.testResults.value.keys==setOf(7103L) }
            assertTrue(client.testResults.value.getValue(7103)<=0)
            assertEquals(1,store.snapshot().nodes.first { it.id==7103L }.status)

            // A failed selection (unknown node) starts nothing and releases its token; the next real switch still gets exactly one round.
            client.selectNode(9999);client.selectNode(7101)
            waitFor("失败选择后正常切换") { settled(6) && client.testResults.value.keys==setOf(7101L) }
            assertEquals(7101L,store.snapshot().selectedNodeId)
            assertTrue(client.testResults.value.getValue(7101)>0)

            // No test summary toast is added by the automatic round.
            assertTrue(messages.toString(),messages.none { it.contains("测速完成") || it.contains("正在测试") })
        } finally {
            scope.cancel();client.cancelTests();client.close();store.replace(original);store.close()
        }
    }
}
