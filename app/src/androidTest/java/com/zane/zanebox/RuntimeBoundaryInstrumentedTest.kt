package com.zane.zanebox

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.*
import android.os.RemoteException
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.data.*
import com.zane.zanebox.runtime.*
import com.zane.zanebox.subscription.SubscriptionScheduler
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Same-UID FGS entries and controlled Binder failures; only the disposable API35 emulator. */
class RuntimeBoundaryInstrumentedTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun waitFor(label:String,timeout:Long=15000,test:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+timeout
        while(!test()) {if(SystemClock.elapsedRealtime()>until)fail(label);Thread.sleep(20)}
    }
    private fun shell(command:String)=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText().trim()}
    private fun fixture(count:Int=1)=AppData(nodes=(1L..count.toLong()).map {Node(it,1,"boundary $it","""{"type":"socks","server":"10.0.2.2","server_port":19081}""")},
        groups=listOf(Group(1,"boundary")),settings=mapOf("serviceMode" to "proxy","dnsRemote" to "local","sniff" to "false","rulesUpdateInterval" to "off","testUrl" to "http://203.0.113.9:19080/hang","testConcurrency" to "1"))

    @Test fun coldStopDuringBlockedInitializationHotStopAndRapidRestartMeetFgsContract() {
        val manager=context.getSystemService(NotificationManager::class.java)
        fun notice()=manager.activeNotifications.firstOrNull {it.id==1}
        @Suppress("DEPRECATION") fun present()=context.getSystemService(ActivityManager::class.java).getRunningServices(100).any {it.service.className==ZaneProxyService::class.java.name}
        context.stopService(Intent(context,ZaneProxyService::class.java));waitFor("必须从未绑定的冷服务开始") {!present()}
        val store=ZaneStore(context);val before=store.snapshot();val client=ServiceClient(context,ZaneProxyService::class.java)
        try {
            store.replace(fixture())
            // snapshot starts a write transaction; hold the other process's initialization beyond Android's FGS window.
            val coldBinding=ServiceClient(context,ZaneProxyService::class.java)
            val db=store.writableDatabase;db.beginTransactionNonExclusive()
            try {
                ContextCompat.startForegroundService(context,Intent(context,ZaneProxyService::class.java).setAction("stop"))
                waitFor("SQLite阻塞时也先发布前台通知") {notice()!=null}
                // A bind keeps the process observable, while STOP may normally finish or initialization may fail.
                runBlocking {coldBinding.readSnapshot()}
                val pid=shell("pidof ${context.packageName}:bg");assertTrue(pid.matches(Regex("[0-9]+")))
                Thread.sleep(6200)
                assertEquals("越过原FGS截止未因FGS合约崩溃重启",pid,shell("pidof ${context.packageName}:bg"))
            } finally {db.endTransaction();coldBinding.close()}
            context.stopService(Intent(context,ZaneProxyService::class.java))
            waitFor("冷STOP允许正常退出并撤销通知") {!present() && notice()==null}
            runBlocking {client.readSnapshot()}
            repeat(2) {ContextCompat.startForegroundService(context,Intent(context,ZaneProxyService::class.java).setAction("stop"))}
            waitFor("热绑定重复STOP移除通知") {notice()==null}
            val generation=client.snapshot.value.generation
            repeat(3) {client.start();client.stop()}
            waitFor("快速启停全部顺序执行",40000) {client.snapshot.value.generation>=generation+6 && client.snapshot.value.state==0 && notice()==null}
            Thread.sleep(6200)
            assertEquals(0,client.snapshot.value.state);assertNull("延迟FGS入口不得复活通知",notice())
            client.start();waitFor("bound普通STOP后仍可START",40000) {client.snapshot.value.state==2}
            client.stop();waitFor("重启后正常停止") {client.snapshot.value.state==0 && notice()==null}
            assertTrue("普通停止已落盘流量",store.readSetting("trafficData").isNotBlank())
        } finally {client.stop();waitFor("测试清理STOP") {client.snapshot.value.state==0 && notice()==null};client.close();store.replace(before);store.close()}
    }

    private inner class Binding(context:Context):ContextWrapper(context) {
        @Volatile var connection:ServiceConnection?=null
        @Volatile var autoConnect=true
        var runtime=Remote("first")
        override fun getApplicationContext():Context=this
        override fun bindService(intent:Intent,conn:ServiceConnection,flags:Int):Boolean {
            connection=conn;if(autoConnect)reconnect(runtime);return true
        }
        override fun unbindService(conn:ServiceConnection) {}
        override fun startForegroundService(intent:Intent):ComponentName? {
            runtime.callback?.onEvent("foregroundReady",intent.getStringExtra("request").orEmpty());return intent.component
        }
        fun disconnect() {connection!!.onServiceDisconnected(ComponentName(this,ZaneProxyService::class.java))}
        fun reconnect(next:Remote) {runtime=next;connection!!.onServiceConnected(ComponentName(this,ZaneProxyService::class.java),next)}
        inner class Remote(val epoch:String):IRuntime.Stub() {
            var callback:IRuntimeCallback?=null
            val actions=CopyOnWriteArrayList<String>()
            var failure:String=""
            var lastRequest=""
            var batch=0L
            var sendInitialTesting=true
            fun initialTesting() {callback!!.onEvent("testing",JSONObject().put("epoch",epoch).put("batchId",batch).put("request","").put("nodes",JSONArray()).put("total",0).put("completed",0).put("running",false).put("begin",true).toString())}
            override fun getSnapshot():String=RuntimeSnapshot().json()
            override fun registerCallback(value:IRuntimeCallback) {callback=value;value.onSnapshot(getSnapshot());if(sendInitialTesting)initialTesting()}
            override fun unregisterCallback(value:IRuntimeCallback) {if(callback===value)callback=null}
            override fun command(action:String,payload:String) {
                actions.add(action)
                if(action==failure) {disconnect();throw RemoteException("controlled disconnect")}
                when(action) {
                    "select" -> callback?.onEvent("selected",JSONObject(payload).put("changed",false).toString())
                    "validate" -> {
                        val args=JSONObject(payload);java.io.File(context.noBackupFilesDir,args.getString("file")).delete()
                        callback?.onEvent("validation",args.put("error","").toString())
                    }
                    "subscriptionUpdated" -> {
                        val response=JSONObject(payload).put("error","").toString()
                        callback?.onEvent("subscriptionApplied",response);callback?.onEvent("subscriptionMeasured",response)
                    }
                    "test" -> {val args=JSONObject(payload);lastRequest=args.getString("request");val node=args.getJSONArray("ids").getLong(0);begin(++batch,lastRequest,node);result(batch,lastRequest,node)}
                }
            }
            fun begin(id:Long,request:String,node:Long) {callback!!.onEvent("testing",JSONObject().put("epoch",epoch).put("batchId",id).put("request",request).put("nodes",JSONArray().put(node)).put("total",1).put("completed",0).put("running",true).put("begin",true).toString())}
            fun result(id:Long,request:String,node:Long) {callback!!.onEvent("test",JSONObject().put("epoch",epoch).put("batchId",id).put("request",request).put("id",node).put("ping",12).toString())}
        }
    }

    @Test fun binderDisconnectRecoversWithoutRepeatingSideEffectsOrAcceptingOldCallbacks() = runBlocking {
        val binding=Binding(context);val client=ServiceClient(binding,ZaneProxyService::class.java)
        try {
            client.readSnapshot();val old=binding.runtime
            old.failure="select";client.selectNode(1)
            waitFor("选择时真实断开回调") {old.actions.count {it=="select"}==1 && client.snapshot.value.error.isNotBlank()}
            val next=binding.Remote("second");binding.reconnect(next);client.readSnapshot()
            client.selectNode(2);waitFor("新选择可恢复") {next.actions.count {it=="select"}==1}
            assertEquals(1,old.actions.count {it=="select"})
            client.checkConfig("{}");assertEquals(1,next.actions.count {it=="validate"})
            next.failure="subscriptionUpdated"
            try {withTimeout(3000) {client.applySubscription(1)};fail("配置尚未确认，断开必须报错")}
            catch(e:RemoteException) {assertEquals("controlled disconnect",e.message)}
            catch(e:IllegalStateException) {assertTrue(e.message.orEmpty().contains("断开"))}
            assertEquals("断开失败不能重复发送订阅应用",1,next.actions.count {it=="subscriptionUpdated"})
            val latest=binding.Remote("third");binding.reconnect(latest);client.readSnapshot()
            var applied=false;client.applySubscription(1) {applied=true};assertTrue(applied)
            client.testNodes(listOf(1));waitFor("有效批次结果") {1L in client.testResults.value}
            val stale=latest.callback!!;val request=latest.lastRequest
            binding.disconnect();val restarted=binding.Remote("restart");binding.reconnect(restarted);client.readSnapshot()
            stale.onEvent("testing",JSONObject().put("epoch","third").put("batchId",1000).put("request",request).put("nodes",JSONArray().put(99)).put("total",1).put("completed",0).put("running",true).put("begin",true).toString())
            assertFalse(99L in client.testingNodes.value)
            restarted.begin(1,"background subscription",2);restarted.result(1,"background subscription",2)
            assertEquals("新进程从batch1开始，后台自动测速仍可见，旧结果已清空",setOf(2L),client.testResults.value.keys)
        } finally {client.close()}
        val waiting=Binding(context).apply {autoConnect=false};val queued=ServiceClient(waiting,ZaneProxyService::class.java)
        try {
            queued.testNodes(listOf(1));queued.cancelTests()
            waitFor("等待首次绑定") {waiting.connection!=null};waiting.reconnect(waiting.runtime)
            waitFor("取消命令已送达") {"cancelTests" in waiting.runtime.actions}
            assertFalse("排队但未发送的测试不应发送", "test" in waiting.runtime.actions)
        } finally {queued.close()}
    }

    @Test fun twoIdleServiceBindingsCanStartBothModesWithoutWaitingForTheOtherToUnbind() = runBlocking {
        val store=ZaneStore(context);val before=store.snapshot()
        val proxy=ServiceClient(context,ZaneProxyService::class.java);val vpn=ServiceClient(context,ZaneVpnService::class.java)
        try {
            store.replace(fixture().copy(settings=fixture().settings+mapOf("dnsDirect" to "local","perAppEnabled" to "true","perAppMode" to "include","perAppPackages" to "com.zane.probe","uiSkin" to "claude")))
            proxy.readSnapshot();vpn.readSnapshot()
            proxy.start();waitFor("另一个IDLE VPN仍绑定时Proxy必须启动",40000) {proxy.snapshot.value.state==2}
            proxy.stop();waitFor("Proxy停止但保留两个绑定") {proxy.snapshot.value.state==0}
            assertEquals(0,vpn.snapshot.value.state)
            store.putSetting("serviceMode","vpn")
            shell("appops set ${context.packageName} ACTIVATE_VPN allow")
            assertNull(android.net.VpnService.prepare(context))
            vpn.start();waitFor("IDLE Proxy仍绑定时VPN必须启动",40000) {vpn.snapshot.value.state==2}
            vpn.stop();waitFor("VPN正常停止") {vpn.snapshot.value.state==0}
            store.putSetting("serviceMode","proxy")
            proxy.start();waitFor("两个idle绑定重复往返仍可启动",40000) {proxy.snapshot.value.state==2}
            proxy.stop();waitFor("往返后Proxy正常退出") {proxy.snapshot.value.state==0}
        } finally {
            proxy.stop();vpn.stop();waitFor("双服务清理") {proxy.snapshot.value.state==0 && vpn.snapshot.value.state==0}
            proxy.close();vpn.close();store.replace(before);store.close()
        }
    }

    @Test fun bindingWaitsForInitialTestingSnapshotBeforePublishingItsBatchBaseline() = runBlocking {
        val binding=Binding(context)
        binding.runtime.apply {batch=4;sendInitialTesting=false}
        val client=ServiceClient(binding,ZaneProxyService::class.java)
        try {
            val read=async(Dispatchers.IO) {client.readSnapshot()}
            waitFor("服务已注册但初始oneway尚未送达") {binding.runtime.callback!=null}
            delay(50);assertFalse("同步snapshot不得提前发布binding ready",read.isCompleted)
            binding.runtime.initialTesting()
            withTimeout(3000) {read.await()};assertEquals(4L,client.testProgress.value.batchId)
            client.testNodes(listOf(2));waitFor("新批次以真实初值4为基线") {client.testProgress.value.batchId==5L && client.testResults.value.keys==setOf(2L)}
        } finally {client.close()}
    }

    @Test fun appliedSubscriptionIsSuccessBeforeMeasurementsAndRemainsSuccessWhenTheyAreCancelled() = runBlocking {
        val store=ZaneStore(context);val before=store.snapshot();val client=ServiceClient(context,ZaneProxyService::class.java)
        try {
            store.replace(fixture(24).copy(groups=listOf(Group(1,"boundary",updatedAt=System.currentTimeMillis(),options=JSONObject().put("subscriptionRuntime",JSONObject().put("request","boundary").put("state","applying")).toString()))))
            client.readSnapshot()
            val updated=store.snapshot().groups.single()
            val apply=async(Dispatchers.IO) {SubscriptionScheduler.apply(context,store,updated,automatic=false,schedule=false)}
            fun state()=JSONObject(store.snapshot().groups.single().options).getJSONObject("subscriptionRuntime")
            waitFor("测速结束前配置成功已落盘") {state().optString("state")=="success" && client.testProgress.value.running}
            assertFalse("绑定保持到可选测速终态",apply.isCompleted)
            client.cancelTests();withTimeout(10000) {apply.await()}
            assertEquals("success",state().getString("state"));assertEquals(0L,state().optLong("nextAttempt"))
            assertEquals("",state().optString("error"))
            store.update {it.copy(groups=it.groups.map {g->g.copy(options=JSONObject(g.options).put("subscriptionRuntime",JSONObject().put("request","cancel-worker").put("state","applying")).toString())})}
            val worker=async(Dispatchers.IO) {SubscriptionScheduler.apply(context,store,store.snapshot().groups.single(),automatic=false,schedule=false)}
            waitFor("新Worker已确认应用") {state().optString("state")=="success" && client.testProgress.value.running}
            worker.cancelAndJoin();assertTrue("Worker取消继续传播",worker.isCancelled)
            client.cancelTests();waitFor("取消Worker后的测速收尾") {!client.testProgress.value.running}
            assertEquals("success",state().getString("state"));assertEquals(0L,state().optLong("nextAttempt"))
        } finally {client.cancelTests();client.close();store.replace(before);store.close()}
    }
}
