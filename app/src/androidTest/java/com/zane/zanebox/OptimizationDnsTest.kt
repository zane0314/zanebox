package com.zane.zanebox

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.data.*
import com.zane.zanebox.runtime.ServiceClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OptimizationDnsTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context=ins.targetContext
    private fun shell(command:String)=ins.uiAutomation.executeShellCommand(command).use {java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText()}
    private fun waitFor(condition:()->Boolean) {val end=SystemClock.elapsedRealtime()+30000;while(!condition()){assertTrue("DNS runtime timeout",SystemClock.elapsedRealtime()<end);Thread.sleep(50)}}
    private fun probe(expected:String):String {
        val nonce="dns"+System.nanoTime();shell("am start -W -n com.zane.probe/.ProbeActivity --es url http://smart.zanebox.test:19080/probe --es nonce $nonce")
        var log="";waitFor {log=shell("logcat -d -s ZaneProbe:I");log.contains("$nonce=")}
        assertTrue(log,log.contains("$nonce=$expected"));return log.lines().filter {it.contains(nonce)}.joinToString("\n")
    }
    @Test fun serviceFakeIpAndOffPreserveOrdinaryAndPriorityRejectOrder() {
        val nodes=(1L..2L).map {Node(it,1,"fixture $it","""{"type":"socks","server":"10.0.2.2","server_port":${19080+it}}""")}
        ZaneStore(context).use{store ->
            store.replace(AppData(nodes=nodes,groups=listOf(Group(1,"g")),rules=listOf(RouteRule(1,"ordinary reject","smart.zanebox.test",outbound="block")),settings=mapOf("selectedNodeId" to "1","factoryRouteDefaultsVersion" to "1","serviceMode" to "vpn","fakeDns" to "true","sniff" to "false","dnsDirect" to "tcp://10.0.2.2:19087","dnsRemote" to "tcp://10.0.2.2:19087","smart.google.target" to "node:2","smartRules.google" to "DOMAIN-SUFFIX,smart.zanebox.test","rulesUpdateInterval" to "off")))
            shell("appops set com.zane.zanebox ACTIVATE_VPN allow")
            val client=ServiceClient(context)
            try {
                client.start();waitFor {client.snapshot.value.state==2}
                val live=probe("EXIT_B");assertTrue(live,live.contains("198.18."))
                fun apply(transform:(AppData)->AppData){store.update(transform);val old=client.snapshot.value.generation;client.reload();waitFor {client.snapshot.value.state==2 && client.snapshot.value.generation>old}}
                apply{it.copy(settings=it.settings+("smart.google.target" to "off"))};val off=probe("ERROR:")
                apply{it.copy(rules=emptyList())};val fallback=probe("EXIT_A")
                apply{it.copy(rules=listOf(RouteRule(1,"priority reject","smart.zanebox.test",outbound="block",prioritize=true)),settings=it.settings+("smart.google.target" to "node:2"))};val priority=probe("ERROR:")
                File(context.getExternalFilesDir(null),"optimization-dns-result.txt").writeText(listOf(live,off,fallback,priority).joinToString("\n"))
            } finally {client.stop();waitFor {client.snapshot.value.state==0};client.close()}
        }
    }
}
