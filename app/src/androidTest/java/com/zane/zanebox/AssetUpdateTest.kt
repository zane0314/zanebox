package com.zane.zanebox

import androidx.test.ext.junit.rules.ActivityScenarioRule
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import androidx.lifecycle.ViewModelProvider
import com.zane.zanebox.data.*
import com.zane.zanebox.ui.AppViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.*
import java.security.MessageDigest

class AssetUpdateTest {
    @get:Rule val activity=ActivityScenarioRule(MainActivity::class.java)
    private fun waitFor(timeout:Long=30000,predicate:()->Boolean) {val until=SystemClock.elapsedRealtime()+timeout;while(!predicate()){assertTrue("等待资源操作超时",SystemClock.elapsedRealtime()<until);Thread.sleep(50)}}
    @Test fun connectedDownloadAppliesAndRestartsWithRollbackOnFailure() {
        lateinit var screen:MainActivity
        activity.scenario.onActivity{screen=it}
        val vm=ViewModelProvider(screen)[AppViewModel::class.java]
        val root=File(screen.filesDir,"core-assets")
        fun url(path:String)="http://203.0.113.9:19080/asset/$path.db"
        val base=AppData(nodes=listOf(Node(901,901,"资源代理 A","""{"type":"socks","server":"10.0.2.2","server_port":19081}"""),Node(902,901,"资源代理 B","""{"type":"socks","server":"10.0.2.2","server_port":19082}""")),groups=listOf(Group(901,"资源更新测试")),rules=listOf(RouteRule(903,"新资源路由",domains="geosite:cn",outbound="node:902",prioritize=true)),settings=mapOf("appLanguage" to "zh-CN","factoryRouteDefaultsVersion" to "1","selectedNodeId" to "901","serviceMode" to "proxy","dnsRemote" to "local","sniff" to "false","rulesProvider" to "4","rulesGeositeUrl" to url("geosite"),"rulesGeoipUrl" to url("geoip")))
        activity.scenario.onActivity{vm.edit{base}}
        waitFor(10000){!vm.busy.value && vm.data.value.selectedNodeId==901L}
        val client=vm.service
        val errors=java.util.concurrent.CopyOnWriteArrayList<String>()
        val observer=CoroutineScope(Dispatchers.Unconfined).launch(start=CoroutineStart.UNDISPATCHED){client.events.collect{errors.add(it)}}
        fun connected()=waitFor(30000){if(client.snapshot.value.state==4)fail(client.snapshot.value.error);client.snapshot.value.state==2}
        fun hash(kind:String)=MessageDigest.getInstance("SHA-256").digest(File(root,"$kind.db").readBytes()).toList()
        fun exit():String {
            val c=URL("http://asset-route.test:19080/probe").openConnection(Proxy(Proxy.Type.HTTP,InetSocketAddress(client.snapshot.value.mixedHost,client.snapshot.value.mixedPort))) as HttpURLConnection
            return try{c.connectTimeout=4000;c.readTimeout=4000;c.inputStream.bufferedReader().use{it.readText()}}finally{c.disconnect()}
        }
        fun source(path:String) {activity.scenario.onActivity{vm.setting("rulesGeositeUrl",url(path))};waitFor(10000){!vm.busy.value && vm.data.value.setting("rulesGeositeUrl")==url(path)}}
        fun failedDownload(path:String,restarts:Boolean) {
            source(path);val before=hash("geosite");val generation=client.snapshot.value.generation
            val events=client.assetRevision.value;val errorIndex=errors.size
            activity.scenario.onActivity{vm.message.value="";vm.downloadAsset("geosite")}
            waitFor(30000){!vm.busy.value}
            if(restarts)waitFor(30000){client.snapshot.value.state==2 && client.snapshot.value.generation>generation && errors.drop(errorIndex).any{it.contains("资源更新失败")}}
            else assertEquals(generation,client.snapshot.value.generation)
            assertEquals(before,hash("geosite"));assertEquals(events,client.assetRevision.value);assertEquals("EXIT_B",exit())
        }
        try {
            client.stop();waitFor(30000){client.snapshot.value.state==0}
            val seed=File(screen.cacheDir,"asset-seed-test.db")
            seed.writeBytes(byteArrayOf(0,1,2,99,110,0,1,0,18)+"baseline-only.test".toByteArray())
            try {val before=client.assetRevision.value;activity.scenario.onActivity{vm.importAsset("geosite",android.net.Uri.fromFile(seed))};waitFor(30000){client.assetRevision.value>before}}finally{seed.delete()}
            client.start();connected();assertEquals("EXIT_A",exit())
            val original=hash("geosite");val generation=client.snapshot.value.generation;val revision=client.assetRevision.value
            activity.scenario.onActivity{vm.downloadAsset("geosite")}
            waitFor(30000){client.assetRevision.value>revision}
            assertTrue("连接时更新被拒绝：${vm.message.value}",client.assetRevision.value>revision)
            connected();assertTrue(client.snapshot.value.generation>generation);assertNotEquals(original,hash("geosite"));assertEquals("EXIT_B",exit())
            val geoip=hash("geoip");val geoRevision=client.assetRevision.value
            activity.scenario.onActivity{vm.edit{it.copy(settings=it.settings+mapOf("serviceMode" to "vpn","mixedPort" to "2081","disableMixedInbound" to "true"))}}
            waitFor(10000){!vm.busy.value && vm.data.value.setting("mixedPort")=="2081"}
            assertEquals(2080,client.snapshot.value.mixedPort)
            activity.scenario.onActivity{vm.downloadAsset("geoip")};waitFor(30000){client.assetRevision.value>geoRevision};connected();assertEquals(geoip,hash("geoip"));assertEquals("EXIT_B",exit())
            assertEquals(2080,client.snapshot.value.mixedPort)
            assertEquals("vpn",vm.store.snapshot().setting("serviceMode"));assertTrue(client.snapshot.value.pendingManual)
            val imported=File(screen.cacheDir,"asset-import-test.db");imported.writeBytes(File(root,"geoip.db").readBytes())
            try {val before=client.assetRevision.value;activity.scenario.onActivity{vm.importAsset("geoip",android.net.Uri.fromFile(imported))};waitFor(30000){client.assetRevision.value>before};connected();assertEquals(geoip,hash("geoip"));assertEquals("EXIT_B",exit())}finally{imported.delete()}
            failedDownload("fail",false);failedDownload("header",false)
            failedDownload("broken",true);failedDownload("missing",true)
            source("geosite")
            client.stop();waitFor(30000){client.snapshot.value.state==0}
            val offline=client.assetRevision.value
            activity.scenario.onActivity{vm.setting("rulesGeositeUrl","http://10.0.2.2:19080/asset/geosite.db")}
            waitFor(10000){!vm.busy.value && vm.data.value.setting("rulesGeositeUrl").startsWith("http://10.0.2.2")}
            activity.scenario.onActivity{vm.downloadAsset("geosite")};waitFor(30000){client.assetRevision.value>offline};assertEquals(0,client.snapshot.value.state)
            source("geosite");assertEquals(901L,vm.store.snapshot().selectedNodeId)
            File(screen.getExternalFilesDir(null),"asset-update-result.json").writeText("""{"connectedGeoip":true,"connectedGeosite":true,"realRoutingChanged":true,"httpAndHeaderFailureNoDisconnect":true,"nativeAndRestartFailureRollback":true,"offlineStaysStopped":true}""")
        }finally{client.stop();waitFor(30000){client.snapshot.value.state !in 1..3};observer.cancel()}
    }
    @Test fun vpnUpdateRestartsAndKeepsTrafficWorking() {
        lateinit var screen:MainActivity;activity.scenario.onActivity{screen=it}
        val vm=ViewModelProvider(screen)[AppViewModel::class.java]
        activity.scenario.onActivity{vm.edit{it.copy(settings=it.settings+mapOf("serviceMode" to "vpn","mixedPort" to "2080","disableMixedInbound" to "false"))}}
        waitFor(10000){!vm.busy.value && vm.data.value.setting("serviceMode")=="vpn"}
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("appops set com.zane.zanebox ACTIVATE_VPN allow").use{java.io.FileInputStream(it.fileDescriptor).readBytes()}
        val client=vm.service
        try {
            client.start();waitFor{if(client.snapshot.value.state==4)fail(client.snapshot.value.error);client.snapshot.value.state==2}
            val revision=client.assetRevision.value;val generation=client.snapshot.value.generation
            activity.scenario.onActivity{vm.downloadAsset("geoip")}
            waitFor{client.assetRevision.value>revision && client.snapshot.value.state==2 && client.snapshot.value.generation>generation}
            val request=URL("http://203.0.113.9:19080/probe").openConnection(Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",client.snapshot.value.mixedPort))) as HttpURLConnection
            try{request.connectTimeout=4000;request.readTimeout=4000;assertEquals("EXIT_A",request.inputStream.bufferedReader().use{it.readText()})}finally{request.disconnect()}
            File(screen.getExternalFilesDir(null),"vpn-asset-update-result.json").writeText("""{"vpnRestart":true,"trafficAfterRestart":"EXIT_A"}""")
        }finally{client.stop();waitFor{client.snapshot.value.state !in 1..3}}
    }
}
