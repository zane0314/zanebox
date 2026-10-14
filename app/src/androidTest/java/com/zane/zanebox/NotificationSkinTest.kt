package com.zane.zanebox

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.data.*
import com.zane.zanebox.runtime.*
import com.zane.zanebox.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.io.File

class NotificationSkinTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get()=context.getSystemService(NotificationManager::class.java)
    private fun notification()=manager.activeNotifications.firstOrNull {it.id==1}?.notification
    private fun await(label:String,timeout:Long=15000,condition:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+timeout
        while(!condition() && SystemClock.elapsedRealtime()<until)Thread.sleep(10)
        assertTrue(label,condition())
    }
    private fun probe() {
        val connection=URL("http://203.0.113.9:19080/probe").openConnection(Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",2080))) as HttpURLConnection
        try {connection.connectTimeout=4000;connection.readTimeout=4000;assertEquals("EXIT_A",connection.inputStream.bufferedReader().use {it.readText()}.trim())}finally{connection.disconnect()}
    }
    @Test fun fiveSmallIconsRefreshWithoutRestartAndSurviveBackgroundAndReopen() {
        assertEquals(uiSkinChoices.map {it.first}.toSet(),skinNotificationIcons.keys)
        assertEquals(5,skinNotificationIcons.values.toSet().size)
        val store=ZaneStore(context);val before=store.snapshot()
        var scenario:ActivityScenario<MainActivity>?=null
        val asset=File(context.filesDir,"core-assets/yacd/index.html")
        val heldAsset=File(asset.parentFile,"index.notification-skin-${java.util.UUID.randomUUID()}.html")
        val observer=ServiceClient(context)
        try {
            observer.connect();kotlinx.coroutines.runBlocking {observer.readSnapshot()};observer.stop();await("旧服务停止") {observer.snapshot.value.state==0}
            store.replace(AppData(nodes=listOf(Node(1,1,"fixture A","""{"type":"socks","server":"10.0.2.2","server_port":19081}""")),groups=listOf(Group(1,"notification")),settings=mapOf("serviceMode" to "proxy","selectedNodeId" to "1","selectedGroupId" to "1","dnsRemote" to "local","sniff" to "false","statsEnabled" to "false","uiSkin" to "claude")))
            scenario=ActivityScenario.launch(MainActivity::class.java)
            lateinit var vm:AppViewModel
            scenario.onActivity {vm=ViewModelProvider(it)[AppViewModel::class.java]}
            await("UI初始化") {!vm.busy.value}
            // Real first foreground notification before a core starts: persistent skin, connecting text.
            androidx.core.content.ContextCompat.startForegroundService(context,Intent(context,ZaneProxyService::class.java).setAction("foreground"))
            await("首次连接通知使用持久化皮肤") {notification()?.smallIcon?.resId==skinNotificationIcon("claude")}
            val connecting=notification()!!
            assertEquals("正在连接",connecting.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            store.putSetting("uiSkin","linear");observer.refreshNotification()
            await("连接通知即时换图标",2000) {notification()?.smallIcon?.resId==skinNotificationIcon("linear")}
            assertEquals(connecting.extras.getCharSequence(Notification.EXTRA_TEXT),notification()!!.extras.getCharSequence(Notification.EXTRA_TEXT))
            // Hold the existing first-run readiness marker, so STARTING remains observable.
            // Restore the exact file before core startup proceeds; no sleeps or production test hooks.
            await("内核资产就绪") {asset.isFile}
            assertTrue("暂存ready marker",asset.renameTo(heldAsset))
            observer.start();await("真实STARTING状态") {observer.snapshot.value.state==1}
            val starting=observer.snapshot.value
            store.putSetting("uiSkin","supabase");observer.refreshNotification()
            await("STARTING不等待连接队列",2000) {notification()?.smallIcon?.resId==skinNotificationIcon("supabase")}
            assertEquals(1,observer.snapshot.value.state)
            assertEquals(starting.generation,observer.snapshot.value.generation);assertEquals(starting.started,observer.snapshot.value.started)
            assertEquals("正在连接",notification()!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertTrue("恢复ready marker",heldAsset.renameTo(asset))
            await("真实连接") {observer.snapshot.value.state==2};probe()
            val session=observer.snapshot.value
            for((skin,_) in uiSkinChoices) {
                scenario.onActivity {vm.setting("uiSkin",skin)}
                await("$skin 保存并刷新",2000) {vm.store.snapshot().setting("uiSkin")==skin && notification()?.smallIcon?.resId==skinNotificationIcon(skin)}
                assertEquals(session.generation,observer.snapshot.value.generation);assertEquals(session.started,observer.snapshot.value.started)
                val shown=notification()!!
                assertTrue(shown.flags and Notification.FLAG_ONGOING_EVENT!=0)
                assertTrue(shown.flags and Notification.FLAG_ONLY_ALERT_ONCE!=0)
                assertEquals("断开",shown.actions.single().title.toString());probe()
            }
            // No traffic change is needed: refreshing the same posted notification preserves its fields.
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            await("后台静止流量基线",25000) {notification()?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()=="↑ 0 B/s  ↓ 0 B/s"}
            val old=notification()!!
            store.putSetting("uiSkin","supabase");observer.refreshNotification()
            await("后台刷新不等待十秒采样",2000) {notification()?.smallIcon?.resId==skinNotificationIcon("supabase")}
            val refreshed=notification()!!
            assertEquals(old.extras.getCharSequence(Notification.EXTRA_TITLE),refreshed.extras.getCharSequence(Notification.EXTRA_TITLE))
            assertEquals(old.extras.getCharSequence(Notification.EXTRA_TEXT),refreshed.extras.getCharSequence(Notification.EXTRA_TEXT))
            assertEquals(old.contentIntent,refreshed.contentIntent);assertEquals(old.actions.single().actionIntent,refreshed.actions.single().actionIntent)
            scenario.close();scenario=null
            scenario=ActivityScenario.launch(MainActivity::class.java)
            await("重开保持图标") {notification()?.smallIcon?.resId==skinNotificationIcon("supabase")}
            assertEquals(session.generation,observer.snapshot.value.generation);assertEquals(session.started,observer.snapshot.value.started);probe()
            observer.stop();await("断开通知移除") {observer.snapshot.value.state==0 && notification()==null}
            observer.refreshNotification();Thread.sleep(2100);assertNull("停止后不能复活",notification())
            val stoppedGeneration=observer.snapshot.value.generation
            observer.start();observer.stop()
            await("快速START/STOP按原顺序完成",30000) {observer.snapshot.value.generation>stoppedGeneration && observer.snapshot.value.state==0 && notification()==null}
            Thread.sleep(2100);assertNull("初始IO通知不能晚到复活",notification())
            context.startService(Intent(context,ZaneProxyService::class.java).setAction("stop"))
            val stoppedUntil=SystemClock.elapsedRealtime()+2100
            while(SystemClock.elapsedRealtime()<stoppedUntil) {
                assertNull("未连接STOP不得创建通知",notification());Thread.sleep(10)
            }
        } finally {
            if(heldAsset.exists())assertTrue("失败时恢复ready marker",heldAsset.renameTo(asset))
            observer.stop();await("清理停止") {observer.snapshot.value.state==0};scenario?.close();observer.close()
            store.replace(before);applyLauncherIcon(context,resolvedUiSkin(before.setting("uiSkin")));store.close()
        }
    }
    @Test fun connectedVpnKeepsItsTunSessionWhileAllFiveNotificationIconsChange() {
        val ins=InstrumentationRegistry.getInstrumentation()
        fun shell(command:String)=ins.uiAutomation.executeShellCommand(command).use { fd -> java.io.FileInputStream(fd.fileDescriptor).bufferedReader().readText() }
        @Suppress("DEPRECATION")
        fun vpnIsForeground()=(context.getSystemService(android.app.ActivityManager::class.java)).getRunningServices(Int.MAX_VALUE).any {it.foreground && it.service.className==ZaneVpnService::class.java.name}
        fun tunProbe() {
            val nonce="notificationVpn"+System.nanoTime()
            shell("am start -W -n com.zane.probe/.ProbeActivity --es url http://10.0.2.2:19080/probe --es nonce $nonce")
            var result=""
            await("独立UID经TUN请求",10000) {result=shell("logcat -d -s ZaneProbe:I");result.contains("$nonce=")}
            assertTrue(result,result.contains("$nonce=EXIT_A uid="))
        }
        val store=ZaneStore(context);val before=store.snapshot()
        val client=ServiceClient(context,ZaneVpnService::class.java)
        var scenario:ActivityScenario<MainActivity>?=null
        try {
            client.connect();kotlinx.coroutines.runBlocking {client.readSnapshot()};client.stop()
            await("旧VPN停止") {client.snapshot.value.state==0}
            store.replace(AppData(nodes=listOf(Node(1,1,"VPN fixture A","""{"type":"socks","server":"10.0.2.2","server_port":19081}""")),groups=listOf(Group(1,"VPN notification")),settings=mapOf("serviceMode" to "vpn","selectedNodeId" to "1","selectedGroupId" to "1","dnsRemote" to "local","dnsDirect" to "local","sniff" to "false","statsEnabled" to "false","bypassLan" to "false","bypassLanInCore" to "false","uiSkin" to "prism")))
            shell("appops set com.zane.zanebox ACTIVATE_VPN allow")
            assertNull("模拟器VPN授权",android.net.VpnService.prepare(context))
            scenario=ActivityScenario.launch(MainActivity::class.java)
            lateinit var vm:AppViewModel
            scenario.onActivity {vm=ViewModelProvider(it)[AppViewModel::class.java]}
            await("VPN界面初始化") {!vm.busy.value}
            client.start()
            await("真实VPN已连接",30000) {if(client.snapshot.value.state==4)fail(client.snapshot.value.error);client.snapshot.value.state==2 && vpnIsForeground()}
            tunProbe()
            val session=client.snapshot.value
            for((skin,_) in uiSkinChoices) {
                scenario.onActivity {vm.setting("uiSkin",skin)}
                await("VPN $skin 图标2秒内更新",2000) {store.readSetting("uiSkin")==skin && notification()?.smallIcon?.resId==skinNotificationIcon(skin)}
                assertEquals("VPN持续CONNECTED",2,client.snapshot.value.state)
                assertTrue("真实ZaneVpnService仍前景运行",vpnIsForeground())
                assertEquals(session.generation,client.snapshot.value.generation);assertEquals(session.started,client.snapshot.value.started)
                assertEquals("断开",notification()!!.actions.single().title.toString())
                tunProbe()
                assertEquals(2,client.snapshot.value.state)
                assertEquals(session.generation,client.snapshot.value.generation);assertEquals(session.started,client.snapshot.value.started)
            }
            notification()!!.actions.single().actionIntent.send()
            await("通知操作真实断开VPN并撤销通知") {client.snapshot.value.state==0 && notification()==null && !vpnIsForeground()}
        } finally {
            client.stop();await("VPN清理停止") {client.snapshot.value.state==0 && notification()==null}
            scenario?.close();client.close();store.replace(before)
            applyLauncherIcon(context,resolvedUiSkin(before.setting("uiSkin")));store.close()
        }
    }

}
