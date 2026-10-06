package com.zane.zanebox

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.data.*
import com.zane.zanebox.runtime.ServiceClient
import org.junit.Assert.*
import org.junit.Test
import java.io.FileInputStream

class LinkRouteTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private fun shell(command:String)=ins.uiAutomation.executeShellCommand(command).use{FileInputStream(it.fileDescriptor).bufferedReader().readText()}
    private fun waitFor(label:String,check:()->Boolean) { val until=SystemClock.elapsedRealtime()+30000;while(!check()){assertTrue(label,SystemClock.elapsedRealtime()<until);Thread.sleep(100)} }
    @Test fun regionsPackagesDirectAndThreeRealProxyHopsChooseTheExpectedExit() {
        val store=ZaneStore(context)
        fun node(id:Long,name:String,port:Int)=Node(id,1,name,"""{"type":"socks","server":"10.0.2.2","server_port":$port}""")
        val base=AppData(nodes=listOf(node(1,"香港 A",19081),node(2,"日本 B",19082),node(3,"前置",19085),node(4,"中间",19086)),groups=listOf(Group(1,"链路")),settings=mapOf("selectedNodeId" to "1","appLanguage" to "zh-CN","serviceMode" to "vpn","dnsRemote" to "local","sniff" to "false","testUrl" to "http://10.0.2.2:19080/test","statsEnabled" to "false"))
        store.replace(base);val client=ServiceClient(context);client.connect();shell("appops set com.zane.zanebox ACTIVATE_VPN allow")
        fun apply(data:AppData) {store.replace(data);val before=client.snapshot.value.generation;client.reload();waitFor("reload ${client.snapshot.value.error}"){client.snapshot.value.state==2 && client.snapshot.value.generation>before}}
        fun probe(expected:String,url:String="http://203.0.113.9:19080/probe",dnsAddress:String="") {
            val nonce="link"+System.nanoTime();shell("am start -W -n com.zane.probe/.ProbeActivity --es url $url --es nonce $nonce")
            var logs="";waitFor("probe $expected"){logs=shell("logcat -d -s ZaneProbe:I");logs.contains("$nonce=")};assertTrue(logs,logs.contains("$nonce=$expected uid="));if(dnsAddress.isNotBlank())assertTrue(logs,logs.lineSequence().any{it.contains("$nonce DNS=") && it.contains(dnsAddress)})
        }
        try {
            client.start();waitFor("start"){client.snapshot.value.state==2};Thread.sleep(5000);probe("EXIT_A")
            apply(base.copy(settings=base.settings+mapOf("smart.youtube.target" to "region:jp","smartRules.youtube" to "IP-CIDR,203.0.113.9/32")));probe("EXIT_B")
            apply(base.copy(settings=base.settings+mapOf("smart.youtube.target" to "node:2","smartCustom.youtube.packages" to "com.zane.probe","smartRules.youtube" to "")));probe("EXIT_B")
            apply(base.copy(settings=base.settings+mapOf("smart.youtube.target" to "direct","smartCustom.youtube.packages" to "com.zane.probe","smartRules.youtube" to "")));probe("DIRECT","http://10.0.2.2:19080/probe")
            val dnsPolicy=base.copy(settings=base.settings+mapOf("dnsDirect" to "tcp://10.0.2.2:19087","dnsRemote" to "tcp://10.0.2.2:19087","smart.speed.target" to "node:2","smart.netflix.target" to "direct"))
            apply(dnsPolicy);probe("EXIT_B","http://one.fast.com:19080/probe","10.0.2.3")
            apply(dnsPolicy.copy(rules=listOf(RouteRule(21,"前置例外",domains="two.fast.com",outbound="direct",prioritize=true))))
            probe("DIRECT","http://two.fast.com:19080/probe","10.0.2.2")
            apply(dnsPolicy.copy(rules=listOf(RouteRule(22,"后置",domains="fast.com",outbound="node:1"))))
            probe("EXIT_B","http://three.fast.com:19080/probe","10.0.2.3")
            apply(dnsPolicy.copy(settings=dnsPolicy.settings+("smartPolicyOrder" to "netflix\nspeed")))
            probe("DIRECT","http://four.fast.com:19080/probe","10.0.2.2")
            apply(dnsPolicy.copy(rules=listOf(RouteRule(23,"后置",domains="fast.com",outbound="node:1")),settings=dnsPolicy.settings+mapOf("smart.speed.target" to "off","smart.netflix.target" to "off")))
            probe("EXIT_A","http://five.fast.com:19080/probe","10.0.2.4")
            val chain=Node(5,1,"链式测试","""{"type":"chain","node_ids":[2,3]}""")
            apply(base.copy(nodes=base.nodes+chain,settings=base.settings+("selectedNodeId" to "5")));probe("EXIT_B")
            apply(base.copy(groups=listOf(Group(1,"三跳",frontProxy=3,landingProxy=2)),settings=base.settings+("selectedNodeId" to "4")));probe("EXIT_B")
        } finally {client.stop();Thread.sleep(500);client.close();store.close()}
    }
}
