package com.zane.zanebox

import android.app.Application
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.config.*
import com.zane.zanebox.data.*
import com.zane.zanebox.ui.AppViewModel
import org.junit.Assert.*
import org.junit.Test

class SmartOrderRuntimeTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private fun shell(command:String)=ins.uiAutomation.executeShellCommand(command).use{p->java.io.FileInputStream(p.fileDescriptor).bufferedReader().readText()}
    private fun waitFor(label:String,test:()->Boolean){val end=SystemClock.elapsedRealtime()+30000;while(!test()){if(SystemClock.elapsedRealtime()>end)fail(label);Thread.sleep(100)}}
    @Test fun releasingOneOrderAutomaticallyChangesOverlappingApplicationRulePriority() {
        val store=ZaneStore(context)
        val settings=mapOf("selectedNodeId" to "1","appLanguage" to "zh-CN","serviceMode" to "vpn","statsEnabled" to "false","sniff" to "false","bypassLan" to "false","bypassLanInCore" to "false","dnsRemote" to "local","dnsDirect" to "local","perAppEnabled" to "true","perAppMode" to "include","perAppPackages" to "com.zane.probe","smart.youtube.target" to "proxy","smart.ai.target" to "direct","smartRules.youtube" to "","smartRules.ai" to "","smartCustom.youtube.packages" to "com.zane.probe","smartCustom.ai.packages" to "com.zane.probe","smartPolicyOrder" to "youtube\nai")+builtinSmartRuleFiles.keys.filter{it !in listOf("youtube","ai")}.associate{"smart.$it.target" to "off"}
        store.replace(AppData(nodes=listOf(Node(1,1,"A","""{"type":"socks","server":"10.0.2.2","server_port":19081}""")),groups=listOf(Group(1,"runtime")),settings=settings))
        val holder=androidx.lifecycle.ViewModelStore();lateinit var vm:AppViewModel
        ins.runOnMainSync{vm=AppViewModel(context.applicationContext as Application);holder.put("smart-order",vm)}
        waitFor("VM ready"){!vm.busy.value && vm.data.value.selectedNodeId==1L};shell("appops set com.zane.zanebox ACTIVATE_VPN allow")
        fun probe(expected:String) {
            val nonce="order"+System.nanoTime();shell("am start -W -n com.zane.probe/.ProbeActivity --es url http://10.0.2.2:19080/probe --es nonce $nonce")
            var logs="";waitFor("UID出口 $expected"){logs=shell("logcat -d -s ZaneProbe:I");logs.contains("$nonce=")};assertTrue(logs,logs.contains("$nonce=$expected uid="))
        }
        try {
            vm.service.start();waitFor("connected"){vm.service.snapshot.value.state==2 || vm.service.snapshot.value.state==4};assertEquals(vm.service.snapshot.value.error,2,vm.service.snapshot.value.state);probe("EXIT_A")
            val keys=smartPolicyKeys(vm.store.snapshot());val generation=vm.service.snapshot.value.generation
            ins.runOnMainSync{vm.reorderSmartPolicies(listOf("ai")+keys.filter{it!="ai"})}
            waitFor("自动应用排序"){vm.service.snapshot.value.state==2 && vm.service.snapshot.value.generation>generation};probe("DIRECT")
            assertEquals("com.zane.probe",vm.store.snapshot().setting("smartCustom.youtube.packages"));assertEquals("com.zane.probe",vm.store.snapshot().setting("perAppPackages"))
        } finally {vm.service.stop();Thread.sleep(500);ins.runOnMainSync{holder.clear()};store.close()}
    }
}
