package com.zane.zanebox

import android.graphics.Bitmap
import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.data.*
import com.zane.zanebox.ui.*
import com.zane.zanebox.runtime.skinNotificationIcon
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Screen observations are upper bounds, not exact first-frame or launcher redraw timestamps. */
class SkinSwitchTimingTest {
    private fun await(condition:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+15000
        while(!condition() && SystemClock.elapsedRealtime()<end)SystemClock.sleep(10)
        assertTrue("等待真实界面收敛超时",condition())
    }
    private fun find(tag:String):AccessibilityNodeInfo? {
        fun search(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
            if(node.viewIdResourceName==tag)return node
            for(i in 0 until node.childCount)node.getChild(i)?.let {search(it)?.let {found->return found}}
            return null
        }
        // Compose exposes virtual-node test tags, not compiled Android resource IDs.
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::search)
    }
    private fun click(tag:String) {await {find(tag)!=null};assertTrue("无法点击 $tag",requireNotNull(find(tag)).performAction(AccessibilityNodeInfo.ACTION_CLICK))}
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun now()=SystemClock.elapsedRealtimeNanos()
    private fun pixels(bitmap:Bitmap):IntArray = try {
        // Sample only the outer page margins: text, snackbars and spinners are transient.
        IntArray(6*50) { i ->
            val x=bitmap.width*listOf(5,10,20,980,990,995)[i%6]/1000
            val y=bitmap.height*(20+(i/6)*50/49)/100
            bitmap.getPixel(x,y)
        }
    } finally {bitmap.recycle()}
    private fun screen()=pixels(requireNotNull(instrumentation.uiAutomation.takeScreenshot()))
    private fun similarity(a:IntArray,b:IntArray)=a.indices.count {a[it]==b[it]}.toDouble()/a.size
    private fun saveScreen(name:String) {
        val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {File(instrumentation.targetContext.getExternalFilesDir(null),"skin-switch-$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}} finally {bitmap.recycle()}
    }

    @Test fun realSettingsClicksReportScreenPersistenceAndAliasLatencyWith900Nodes() {
        lateinit var vm:AppViewModel
        var scenario:ActivityScenario<MainActivity>?=null
        val rows=JSONArray()
        val report=JSONObject().put("nodeCount",900).put("samples",rows)
            .put("sourceVersion",instrumentation.targetContext.packageManager.getPackageInfo(instrumentation.targetContext.packageName,0).versionName)
            .put("clock","elapsedRealtimeNanos; start immediately before accessibility ACTION_CLICK; real Choreographer, no Compose test clock")
            .put("screenScope","first screenshot matching calibrated settings background margins >=97%; observation upper bound, not exact first frame")
            .put("aliasScope","PackageManager enabled alias; excludes actual launcher redraw")
            .put("status","FAIL")
        val output=File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)),"skin-switch-timing.json")
        try {
            instrumentation.uiAutomation.serviceInfo=instrumentation.uiAutomation.serviceInfo.apply {
                flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            ZaneStore(instrumentation.targetContext).use {it.replace(AppData(
                nodes=(1L..900L).map {Node(it,1,"时序节点 $it","""{"type":"socks","server":"10.0.2.2","server_port":19081}""",order=it.toInt())},
                groups=listOf(Group(1,"时序分组")),
                settings=mapOf("uiSkin" to "prism","appLanguage" to "zh-CN","selectedNodeId" to "1","browseGroupId" to "1","selectedGroupId" to "1","serviceMode" to "proxy","statsEnabled" to "false","dnsRemote" to "local","sniff" to "false")
            ))}
            scenario=ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity {vm=ViewModelProvider(it)[AppViewModel::class.java]}
            await {!vm.busy.value && vm.data.value.nodes.size==900}
            vm.service.start()
            await {vm.service.snapshot.value.state==2}
            val session=vm.service.snapshot.value
            val manager=instrumentation.targetContext.getSystemService(NotificationManager::class.java)
            click("tab_2")
            click("settings_general")
            val skins=uiSkinChoices.map {it.first}
            val golden=linkedMapOf<String,IntArray>()
            for(skin in skins) {
                scenario.onActivity {vm.setting("uiSkin",skin)}
                await {!vm.busy.value && vm.store.readSetting("uiSkin")==skin && enabledLauncherSkin(instrumentation.targetContext)==skin}
                var settled=screen()
                var equalFrames=0
                await {
                    SystemClock.sleep(50)
                    val next=screen()
                    equalFrames=if(similarity(settled,next)==1.0)equalFrames+1 else 0
                    settled=next
                    equalFrames>=3
                }
                golden[skin]=settled
                saveScreen("golden-$skin")
            }
            for(a in skins)for(b in skins)if(a!=b)
                assertTrue("屏幕指纹不能区分 $a / $b",similarity(golden.getValue(a),golden.getValue(b))<0.97)
            ZaneStore(instrumentation.targetContext).use {observerStore ->
                repeat(3) {round -> for(skin in skins) {
                    click("setting_uiSkin")
                    await {find("choice_$skin")!=null}
                    val choice=requireNotNull(find("choice_$skin"))
                    val persisted=AtomicLong();val alias=AtomicLong();val visible=AtomicLong();val notified=AtomicLong()
                    val samples=AtomicLong();val screenshotCost=AtomicLong();val bestMatch=AtomicLong()
                    val pool=Executors.newFixedThreadPool(4)
                    val ready=CountDownLatch(4);val go=CountDownLatch(1);val started=AtomicLong()
                    fun observe(endpoint:AtomicLong,condition:()->Boolean)=pool.submit {
                        ready.countDown();go.await()
                        val deadline=started.get()+TimeUnit.SECONDS.toNanos(15)
                        while(now()<deadline && endpoint.get()==0L) {
                            if(condition())endpoint.compareAndSet(0,now()) else SystemClock.sleep(10)
                        }
                    }
                    try {
                        val saved=observe(persisted){observerStore.readSetting("uiSkin")==skin}
                        val launcher=observe(alias){enabledLauncherSkin(instrumentation.targetContext)==skin}
                        val notification=observe(notified){manager.activeNotifications.firstOrNull {it.id==1}?.notification?.smallIcon?.resId==skinNotificationIcon(skin)}
                        val drawing=observe(visible){
                            val captureStart=now();val actual=screen()
                            screenshotCost.addAndGet(now()-captureStart);samples.incrementAndGet()
                            val match=similarity(actual,golden.getValue(skin))
                            bestMatch.updateAndGet {maxOf(it,(match*10000).toLong())}
                            match>=0.97
                        }
                        assertTrue("观察线程未就绪",ready.await(5,TimeUnit.SECONDS))
                        val start=now();started.set(start);go.countDown()
                        assertTrue(choice.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        val clickReturned=now()
                        saved.get(16,TimeUnit.SECONDS);launcher.get(16,TimeUnit.SECONDS)
                        notification.get(16,TimeUnit.SECONDS);drawing.get(16,TimeUnit.SECONDS)
                        fun ms(value:Long)=if(value==0L)JSONObject.NULL else (value-start)/1_000_000.0
                        rows.put(JSONObject().put("round",round).put("skin",skin)
                            .put("clickReturnedMs",ms(clickReturned)).put("screenObservedMs",ms(visible.get()))
                            .put("persistedObservedMs",ms(persisted.get())).put("aliasObservedMs",ms(alias.get()))
                            .put("notificationObservedMs",ms(notified.get())).put("screenshotSamples",samples.get())
                            .put("bestScreenMatch",bestMatch.get()/10000.0)
                            .put("screenshotTotalCostMs",screenshotCost.get()/1_000_000.0))
                        if(visible.get()==0L)saveScreen("failure-$skin")
                        assertTrue("$skin 屏幕/保存/alias/通知未收敛",visible.get()>0 && persisted.get()>0 && alias.get()>0 && notified.get()>0)
                        await {!vm.busy.value}
                        assertEquals(skin,vm.data.value.setting("uiSkin"))
                        assertEquals(900,vm.data.value.nodes.size)
                        assertEquals(2,vm.service.snapshot.value.state)
                        assertEquals(session.generation,vm.service.snapshot.value.generation)
                        assertEquals(session.started,vm.service.snapshot.value.started)
                    } finally {pool.shutdownNow();assertTrue("观察线程未停止",pool.awaitTermination(20,TimeUnit.SECONDS))}
                }}
            }
            report.put("status","PASS")
        } finally {
            output.writeText(report.toString(2))
            scenario?.onActivity {vm.service.stop()}
            if(scenario!=null)await {vm.service.snapshot.value.state==0}
            scenario?.close()
        }
    }
}
