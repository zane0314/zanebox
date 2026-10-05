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

@RunWith(AndroidJUnit4::class)
class SubscriptionBackgroundTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun waitFor(label:String,predicate:()->Boolean) { val until=android.os.SystemClock.elapsedRealtime()+30000;while(!predicate()) { if(android.os.SystemClock.elapsedRealtime()>until)fail("超时 $label");Thread.sleep(100) } }
    @Test fun durableWorkConsumesOptionsWithoutConnectedService() = runBlocking {
        val store=ZaneStore(context);val work=WorkManager.getInstance(context)
        val options=JSONObject().put("autoUpdate",true).put("autoUpdateDelay",1).put("updateWhenConnectedOnly",true).put("deduplication",true).put("filterMode",1).put("filterRegex","fixture A").put("customUserAgent","Zane-auto-fixture/1")
        store.replace(AppData(nodes=listOf(Node(20,1,"保留直到更新","{\"type\":\"socks\",\"server\":\"10.0.2.2\",\"server_port\":19082}")),groups=listOf(Group(1,"自动订阅","http://10.0.2.2:19080/options-subscription",options=options.toString())),settings=mapOf("selectedNodeId" to "20","selectedGroupId" to "1","serviceMode" to "proxy")))
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
            val next=work.getWorkInfosForUniqueWork("zanebox-subscription-1").get(5,TimeUnit.SECONDS)
            assertTrue("成功后没有续排",next.any { !it.state.isFinished });evidence.put("nextWorkPersisted",true)
            File(context.getExternalFilesDir(null),"subscription-background-result.json").writeText(evidence.toString(2))
        } finally {
            store.update { it.copy(groups=it.groups.map { group->group.copy(options=JSONObject(group.options).put("autoUpdate",false).toString()) }) }
            SubscriptionScheduler.reconcile(context,store.snapshot());store.close()
        }
    }
}
