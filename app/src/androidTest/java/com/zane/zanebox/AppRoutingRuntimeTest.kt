package com.zane.zanebox

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.data.*
import com.zane.zanebox.runtime.ServiceClient
import org.junit.Assert.*
import org.junit.Test

class AppRoutingRuntimeTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private fun shell(command:String)=ins.uiAutomation.executeShellCommand(command).use{p->java.io.FileInputStream(p.fileDescriptor).bufferedReader().readText()}
    private fun waitFor(label:String,predicate:()->Boolean) {val deadline=SystemClock.elapsedRealtime()+30000;while(!predicate()){if(SystemClock.elapsedRealtime()>deadline)fail("超时：$label");Thread.sleep(100)}}
    @Test fun whitelistBlacklistAndDisabledScopeSelectTheCorrectRealUidExit() {
        val ten=(0..9).map{"com.google.android.linksfixture.p$it"}.toSet()
        val probe="com.zane.probe"
        val stored="$probe\ncom.example.linkslocal.one"
        val base=AppData(nodes=listOf(Node(1,1,"A","""{"type":"socks","server":"10.0.2.2","server_port":19081}"""),Node(2,1,"B","""{"type":"socks","server":"10.0.2.2","server_port":19082}""")),groups=listOf(Group(1,"应用范围验收")),settings=mapOf("selectedNodeId" to "1","appLanguage" to "zh-CN","serviceMode" to "vpn","dnsRemote" to "local","dnsDirect" to "local","sniff" to "false","statsEnabled" to "false","bypassLan" to "false","bypassLanInCore" to "false","smartRules.ai" to "","smart.ai.target" to "node:2","smartCustom.ai.packages" to stored)+com.zane.zanebox.config.builtinSmartRuleFiles.keys.filter{it!="ai"}.associate{"smart.$it.target" to "off"})
        val store=ZaneStore(context);store.replace(base)
        val client=ServiceClient(context);client.connect();shell("appops set com.zane.zanebox ACTIVATE_VPN allow")
        fun request(expected:String) {
            val nonce="scope"+System.nanoTime();shell("am start -W -n com.zane.probe/.ProbeActivity --es url http://10.0.2.2:19080/probe --es nonce $nonce")
            var logs="";waitFor("不同 UID 出口 $expected"){logs=shell("logcat -d -s ZaneProbe:I");logs.contains("$nonce=")}
            assertTrue(logs,logs.contains("$nonce=$expected uid="))
            assertEquals(stored,store.snapshot().setting("smartCustom.ai.packages"))
        }
        try {
            client.start();waitFor("初次连接 ${client.snapshot.value.error}"){client.snapshot.value.state==2 || client.snapshot.value.state==4};assertEquals(client.snapshot.value.error,2,client.snapshot.value.state)
            val cases=listOf(
                Triple("include",ten+probe,"EXIT_B"),
                Triple("include",ten,"DIRECT"),
                Triple("exclude",ten+probe,"DIRECT"),
                Triple("exclude",ten,"EXIT_B"))
            for((mode,packages,expected) in cases) {
                store.replace(base.copy(settings=base.settings+mapOf("perAppEnabled" to "true","perAppMode" to mode,"perAppPackages" to packages.joinToString("\n"))))
                val generation=client.snapshot.value.generation;client.reload();waitFor("$mode 重载 ${client.snapshot.value.error}"){client.snapshot.value.state==2 && client.snapshot.value.generation>generation}
                request(expected)
            }
            store.replace(base);var generation=client.snapshot.value.generation;client.reload();waitFor("分应用关闭"){client.snapshot.value.state==2 && client.snapshot.value.generation>generation};request("EXIT_B")
            store.replace(base.copy(settings=base.settings+("smart.ai.target" to "off")));generation=client.snapshot.value.generation;client.reload();waitFor("智能策略关闭"){client.snapshot.value.state==2 && client.snapshot.value.generation>generation};request("EXIT_A")
        } finally {client.stop();Thread.sleep(500);client.close();store.close()}
    }
}
