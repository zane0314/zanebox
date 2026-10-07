package com.zane.zanebox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import com.zane.zanebox.data.*
import com.zane.zanebox.runtime.ServiceClient
import com.zane.zanebox.subscription.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.net.*

@RunWith(AndroidJUnit4::class)
class SubscriptionBackgroundTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun waitFor(label:String,predicate:()->Boolean) { val until=android.os.SystemClock.elapsedRealtime()+30000;while(!predicate()) { if(android.os.SystemClock.elapsedRealtime()>until)fail("超时 $label");Thread.sleep(100) } }
    private fun exit():String {
        val c=URL("http://203.0.113.9:19080/probe").openConnection(Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",2080))) as HttpURLConnection
        c.connectTimeout=5000;c.readTimeout=5000
        return try {c.inputStream.bufferedReader().use{it.readText()}.trim()} finally {c.disconnect()}
    }
    @Test fun backgroundUpdateAppliesNewEndpointAndTestsWithoutActivity() = backgroundUpdate(false)
    @Test fun backgroundUpdateRebuildsAutoSelectionWithoutActivity() = backgroundUpdate(true)
    @Test fun parallelGroupsFinishTheirOwnLatencyTests() = runBlocking {
        val store=ZaneStore(context);val work=WorkManager.getInstance(context)
        val groups=(1L..2L).map{id->Group(id,"并行组 $id","http://10.0.2.2:19080/options-subscription",options=JSONObject().put("autoUpdate",false).put("autoUpdateDelay",60).put("filterMode",1).put("filterRegex",if(id==1L)"fixture A$" else "fixture B$").toString())}
        store.replace(AppData(groups=groups,settings=mapOf("serviceMode" to "proxy","testUrl" to "http://203.0.113.9:19080/test","dnsRemote" to "local","rulesUpdateInterval" to "off")))
        try {
            store.update{it.copy(groups=it.groups.map{g->g.copy(options=JSONObject(g.options).put("autoUpdate",true).toString())})}
            SubscriptionScheduler.reconcile(context,store.snapshot())
            val requests=groups.map {g->OneTimeWorkRequestBuilder<SubscriptionUpdateWorker>().setInputData(workDataOf("groupId" to g.id)).build().also{work.enqueueUniqueWork("zanebox-subscription-${g.id}",ExistingWorkPolicy.REPLACE,it).result.get(5,TimeUnit.SECONDS)}}
            waitFor("并行订阅完整完成"){requests.all{work.getWorkInfoById(it.id).get(5,TimeUnit.SECONDS)?.state?.isFinished==true}}
            val data=store.snapshot();assertEquals(2,data.nodes.size);assertTrue(data.nodes.all{it.ping>0 && it.status==3});assertTrue(data.groups.all{JSONObject(it.options).getJSONObject("subscriptionRuntime").getString("state")=="success"});assertFalse(ServiceClient.isConnected(context))
            File(context.getExternalFilesDir(null),"subscription-parallel-result.json").writeText(JSONObject().put("bothWorkersSucceeded",true).put("bothGroupsTested",true).toString(2))
        } finally {store.update{it.copy(groups=it.groups.map{g->g.copy(options=JSONObject(g.options).put("autoUpdate",false).toString())})};SubscriptionScheduler.reconcile(context,store.snapshot());store.close()}
    }
    private fun backgroundUpdate(auto:Boolean) = runBlocking {
        val store=ZaneStore(context);val client=ServiceClient(context);val work=WorkManager.getInstance(context)
        val options=JSONObject().put("autoUpdate",false).put("autoUpdateDelay",60).put("filterMode",1).put("filterRegex",if(auto)"fixture [AB]$" else "fixture A$")
        store.replace(AppData(nodes=listOf(Node(20,1,"旧地址","""{"type":"socks","server":"127.0.0.1","server_port":19082}""")),groups=listOf(Group(1,"自动订阅","http://10.0.2.2:19080/options-subscription",options=options.toString())),settings=mapOf("appLanguage" to "zh-CN","selectedNodeId" to "20","selectedGroupId" to "1","serviceMode" to "proxy","homeAutoSelect" to auto.toString(),"statsEnabled" to "false","dnsRemote" to "local","testUrl" to "http://203.0.113.9:19080/test","rulesUpdateInterval" to "off")))
        try {
            client.start();waitFor("旧内核连接"){client.snapshot.value.state==2};assertEquals("EXIT_B",exit())
            val generation=client.snapshot.value.generation
            // No Activity or ViewModel; even pending manual mode/listener edits must not block subscription refresh.
            options.put("autoUpdate",true)
            store.update{it.copy(groups=it.groups.map{g->g.copy(options=options.toString())},settings=it.settings+("serviceMode" to "vpn")+("mixedPort" to "2081"))}
            client.close()
            val request=OneTimeWorkRequestBuilder<SubscriptionUpdateWorker>().setInputData(workDataOf("groupId" to 1L)).build()
            work.enqueueUniqueWork("zanebox-subscription-1",ExistingWorkPolicy.REPLACE,request).result.get(5,TimeUnit.SECONDS)
            waitFor("后台更新完成"){work.getWorkInfoById(request.id).get(5,TimeUnit.SECONDS)?.state?.isFinished==true}
            assertEquals(WorkInfo.State.SUCCEEDED,work.getWorkInfoById(request.id).get().state)
            waitFor("新地址实际出口"){exit()=="EXIT_A"}
            val observer=ServiceClient(context)
            try {val active=observer.readSnapshot();assertEquals(2,active.state);assertTrue(active.generation>generation);assertEquals(2080,active.mixedPort);assertTrue(active.pendingManual);if(auto)waitFor("自动选择新候选"){observer.autoNodeId.value==store.snapshot().nodes.single{it.name=="fixture A"}.id}} finally {observer.close()}
            val fresh=store.snapshot();assertEquals("vpn",fresh.setting("serviceMode"));assertEquals("2081",fresh.setting("mixedPort"));assertEquals(auto,fresh.bool("homeAutoSelect"));assertTrue(fresh.nodes.all{it.ping>0 && it.status==3});assertTrue(fresh.nodes.all{JSONObject(it.outbound).getString("server")=="10.0.2.2"})
            File(context.getExternalFilesDir(null),"subscription-background-${if(auto)"auto" else "live"}-result.json").writeText(JSONObject().put("noActivity",true).put("oldServer","127.0.0.1").put("newServer","10.0.2.2").put("actualExitBefore","EXIT_B").put("actualExitAfter","EXIT_A").put("autoSelection",auto).put("allLatenciesMeasured",true).put("manualSettingsPreserved",true).toString(2))
            suspend fun repeatUpdate(invalid:Boolean=false):String {
                store.update { it.copy(groups=it.groups.map{g->g.copy(updatedAt=0,options=JSONObject(g.options).apply{remove("subscriptionLastUpdated")}.toString())},settings=if(invalid)it.settings+("dnsRemote" to "https://") else it.settings) }
                val again=OneTimeWorkRequestBuilder<SubscriptionUpdateWorker>().setInputData(workDataOf("groupId" to 1L)).build()
                work.enqueueUniqueWork("zanebox-subscription-1",ExistingWorkPolicy.REPLACE,again).result.get(5,TimeUnit.SECONDS)
                waitFor("重复后台任务完成"){work.getWorkInfoById(again.id).get(5,TimeUnit.SECONDS)?.state?.isFinished==true}
                return JSONObject(store.snapshot().groups.single().options).getJSONObject("subscriptionRuntime").getString("state")
            }
            val active=ServiceClient(context)
            try {
                val applied=active.readSnapshot().generation
                assertEquals("success",repeatUpdate());assertEquals(applied,active.readSnapshot().generation);assertEquals("EXIT_A",exit())
                if(!auto) {
                    assertEquals("apply-error",repeatUpdate(invalid=true));assertEquals(applied,active.readSnapshot().generation);assertEquals("EXIT_A",exit())
                    val savedAt=store.snapshot().groups.single().updatedAt
                    store.putSetting("dnsRemote","local")
                    val retry=OneTimeWorkRequestBuilder<SubscriptionUpdateWorker>().setInputData(workDataOf("groupId" to 1L)).build()
                    work.enqueueUniqueWork("zanebox-subscription-1",ExistingWorkPolicy.REPLACE,retry).result.get(5,TimeUnit.SECONDS)
                    waitFor("无需重抓订阅的后台应用重试"){work.getWorkInfoById(retry.id).get(5,TimeUnit.SECONDS)?.state?.isFinished==true}
                    assertEquals("success",JSONObject(store.snapshot().groups.single().options).getJSONObject("subscriptionRuntime").getString("state"));assertEquals(savedAt,store.snapshot().groups.single().updatedAt);assertEquals("EXIT_A",exit())
                }
                File(context.getExternalFilesDir(null),"subscription-repeat-${auto}-result.json").writeText(JSONObject().put("unchangedNoRestart",true).put("invalidKeepsConnection",!auto).toString(2))
            } finally {active.close()}
        } finally {
            val cleanup=ServiceClient(context);cleanup.stop();waitFor("断开清理"){!runBlocking{ServiceClient.isConnected(context)}};cleanup.close();client.close()
            store.update{it.copy(groups=it.groups.map{g->g.copy(options=JSONObject(g.options).put("autoUpdate",false).toString())},settings=it.settings+("serviceMode" to "proxy")+("mixedPort" to "2080")+("dnsRemote" to "local"))};SubscriptionScheduler.reconcile(context,store.snapshot());store.close()
        }
    }
    @Test fun durableWorkConsumesOptionsWithoutConnectedService() = runBlocking {
        val store=ZaneStore(context);val work=WorkManager.getInstance(context)
        val options=JSONObject().put("autoUpdate",true).put("autoUpdateDelay",1).put("updateWhenConnectedOnly",true).put("deduplication",true).put("filterMode",1).put("filterRegex","fixture A").put("customUserAgent","Zane-auto-fixture/1")
        store.replace(AppData(nodes=listOf(Node(20,1,"保留直到更新","{\"type\":\"socks\",\"server\":\"10.0.2.2\",\"server_port\":19082}")),groups=listOf(Group(1,"自动订阅","http://10.0.2.2:19080/options-subscription",options=options.toString())),settings=mapOf("appLanguage" to "zh-CN","selectedNodeId" to "20","selectedGroupId" to "1","serviceMode" to "proxy","testUrl" to "http://203.0.113.9:19080/test","dnsRemote" to "local","rulesUpdateInterval" to "off")))
        assertFalse(ServiceClient.isConnected(context))
        val evidence=JSONObject()
        try {
            SubscriptionScheduler.reconcile(context,store.snapshot())
            fun state()=JSONObject(store.snapshot().groups.single().options).optJSONObject("subscriptionRuntime")?.optString("state")
            waitFor("仅连接任务在断开时等待") { state()=="waiting-connection" }
            assertEquals(20L,store.snapshot().nodes.single().id);assertEquals(0L,store.snapshot().groups.single().updatedAt)
            val pending=work.getWorkInfosForUniqueWork("zanebox-subscription-1").get(5,TimeUnit.SECONDS)
            assertTrue("没有持久化后续任务",pending.any { !it.state.isFinished });evidence.put("connectedOnlyPostponed",true)
            // Run the actual Worker immediately; no UI, fake connection status, or replacement updater.
            options.put("updateWhenConnectedOnly",false)
            store.update { it.copy(groups=it.groups.map { group->group.copy(options=options.toString()) }) }
            val request=OneTimeWorkRequestBuilder<SubscriptionUpdateWorker>().setInputData(workDataOf("groupId" to 1L)).build()
            work.enqueueUniqueWork("zanebox-subscription-1",ExistingWorkPolicy.REPLACE,request).result.get(5,TimeUnit.SECONDS)
            waitFor("允许断开更新的真实后台Worker") { state()=="success" && store.snapshot().groups.single().updatedAt>0 }
            val data=store.snapshot();assertEquals(1,data.nodes.size);assertEquals("fixture A",data.nodes.single().name);assertEquals(data.nodes.single().id,data.selectedNodeId);assertEquals(1L,data.selectedGroupId)
            assertFalse(ServiceClient.isConnected(context));evidence.put("backgroundUpdate",true).put("filterDedup",true).put("defaultSelection",true)
            assertTrue(data.nodes.single().ping>0);evidence.put("latencyTestedWhileDisconnected",true)
            val next=work.getWorkInfosForUniqueWork("zanebox-subscription-1").get(5,TimeUnit.SECONDS)
            assertTrue("成功后没有续排",next.any { !it.state.isFinished });evidence.put("nextWorkPersisted",true)
            File(context.getExternalFilesDir(null),"subscription-background-result.json").writeText(evidence.toString(2))
        } finally {
            store.update { it.copy(groups=it.groups.map { group->group.copy(options=JSONObject(group.options).put("autoUpdate",false).toString()) }) }
            SubscriptionScheduler.reconcile(context,store.snapshot());store.close()
        }
    }
}
