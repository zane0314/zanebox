package com.zane.zanebox

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.data.*
import com.zane.zanebox.runtime.ServiceClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Uses only APIs that also exist in 1.0.0 so the same test APK can measure both builds.
 * Emulator CPU ticks are a relative comparison only; they say nothing about real-device battery use.
 */
@RunWith(AndroidJUnit4::class)
class IdleCpuProbeTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private fun shell(command:String)=ins.uiAutomation.executeShellCommand(command).use { p->java.io.FileInputStream(p.fileDescriptor).bufferedReader().readText() }
    private fun ticks(pid:String):Long { val fields=File("/proc/$pid/stat").readText().substringAfterLast(')').trim().split(' ');return fields[11].toLong()+fields[12].toLong() }
    private fun waitFor(label:String,timeout:Long,predicate:()->Boolean) { val until=SystemClock.elapsedRealtime()+timeout;while(!predicate()) { if(SystemClock.elapsedRealtime()>until) fail("超时 $label");Thread.sleep(200) } }
    @Test fun idleConnectedCpuAndFullLatencyTestWith2000Nodes() {
        val label=InstrumentationRegistry.getArguments().getString("perfLabel","current")
        val idleSeconds=InstrumentationRegistry.getArguments().getString("idleSeconds","60").toLong()
        val store=ZaneStore(context)
        val nodes=(1..2000).map { Node(it.toLong(),1,"fixture $it","{\"type\":\"socks\",\"server\":\"10.0.2.2\",\"server_port\":${if(it%2==0) 19082 else 19081}}",order=it) }
        store.replace(AppData(nodes=nodes,groups=listOf(Group(1,"perf")),settings=mapOf("serviceMode" to "proxy","selectedNodeId" to "1","selectedGroupId" to "1","sniff" to "false","dnsRemote" to "local","testUrl" to "http://203.0.113.9:19080/probe","testTimeout" to "3000","testConcurrency" to "8")))
        val client=ServiceClient(context);client.connect()
        val result=JSONObject().put("label",label).put("nodes",2000).put("idleSeconds",idleSeconds)
        try {
            val connectStart=SystemClock.elapsedRealtime()
            client.start();waitFor("连接",60000) { if(client.snapshot.value.state==4) fail(client.snapshot.value.error);client.snapshot.value.state==2 }
            result.put("connectMs",SystemClock.elapsedRealtime()-connectStart)
            val pid=shell("pidof com.zane.zanebox:bg").trim();assertTrue("后台进程不存在",pid.isNotBlank())
            Thread.sleep(10000)
            val hz=100.0
            val idleStart=ticks(pid);Thread.sleep(idleSeconds*1000);val idleTicks=ticks(pid)-idleStart
            result.put("idleCpuTicks",idleTicks).put("idleCpuPercent",idleTicks/hz/idleSeconds*100)
            val testStart=SystemClock.elapsedRealtime();val testTicks=ticks(pid)
            client.testNodes(nodes.map { it.id })
            waitFor("2000节点测速落盘",900000) { store.snapshot().nodes.count { it.status!=0 }==2000 }
            result.put("latencyTestMs",SystemClock.elapsedRealtime()-testStart).put("latencyTestCpuTicks",ticks(pid)-testTicks)
            result.put("latencyOk",store.snapshot().nodes.count { it.ping>0 })
        } finally {
            File(context.getExternalFilesDir(null),"idle-cpu-$label.json").writeText(result.toString(2))
            client.stop();Thread.sleep(800);client.close();store.close()
        }
    }
}
