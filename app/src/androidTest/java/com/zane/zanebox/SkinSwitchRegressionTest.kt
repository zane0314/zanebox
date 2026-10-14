package com.zane.zanebox

import android.app.NotificationManager
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.data.*
import com.zane.zanebox.runtime.skinNotificationIcon
import com.zane.zanebox.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SkinSwitchRegressionTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val vm get()=ViewModelProvider(compose.activity)[AppViewModel::class.java]
    private fun prepare() {
        compose.waitUntil(15000){!vm.busy.value}
        compose.runOnIdle {vm.edit {AppData(nodes=listOf(Node(1,1,"换肤回归","""{"type":"socks","server":"10.0.2.2","server_port":19081}""")),
            groups=listOf(Group(1,"换肤")),settings=mapOf("uiSkin" to "prism","appLanguage" to "zh-CN","selectedNodeId" to "1","serviceMode" to "proxy","dnsRemote" to "local","statsEnabled" to "false","sniff" to "false"))}}
        compose.waitUntil(15000){!vm.busy.value && enabledLauncherSkin(context)=="prism"}
    }
    private fun fingerprint():List<Int> {
        val screenshot=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        return try {(20..70 step 2).map {y->screenshot.getPixel(screenshot.width/100,screenshot.height*y/100)}} finally {screenshot.recycle()}
    }
    @Test fun screenChangesWhileTheDatabaseIsBusy() {
        prepare()
        compose.onNodeWithTag("tab_2").performClick();compose.onNodeWithTag("settings_general").performClick()
        compose.runOnIdle {vm.setting("uiSkin","linear")}
        compose.waitUntil(15000){!vm.busy.value && vm.data.value.setting("uiSkin")=="linear"}
        compose.waitForIdle();val linear=fingerprint()
        compose.runOnIdle {vm.setting("uiSkin","prism")}
        compose.waitUntil(15000){!vm.busy.value && vm.data.value.setting("uiSkin")=="prism"}
        compose.waitForIdle();assertNotEquals(linear,fingerprint())
        compose.onNodeWithTag("setting_uiSkin").performClick()
        val locked=CountDownLatch(1);val release=CountDownLatch(1)
        val holder=thread {synchronized(vm.store){locked.countDown();release.await(10,TimeUnit.SECONDS)}}
        try {
            assertTrue(locked.await(5,TimeUnit.SECONDS))
            compose.onNodeWithTag("choice_linear").performClick()
            compose.waitUntil(1500){fingerprint()==linear}
            assertEquals("数据库尚未保存", "prism",vm.data.value.setting("uiSkin"))
        } finally {release.countDown();holder.join(5000);assertFalse(holder.isAlive)}
        compose.waitUntil(15000){!vm.busy.value && vm.data.value.setting("uiSkin")=="linear" && enabledLauncherSkin(context)=="linear"}
    }
    @Test fun persistenceAndNotificationDoNotWaitForTheLauncherQueue() {
        prepare();vm.service.start()
        compose.waitUntil(30000){vm.service.snapshot.value.state==2}
        val session=vm.service.snapshot.value
        // Hold only the existing launcher queue; emulate a slow previous system icon update.
        val lock=AppViewModel::class.java.getDeclaredField("uiSkinLock").apply {isAccessible=true}.get(vm) as Mutex
        runBlocking {lock.lock()}
        try {
            compose.runOnIdle {vm.setting("uiSkin","linear")}
            val manager=context.getSystemService(NotificationManager::class.java)
            compose.waitUntil(2000){vm.data.value.setting("uiSkin")=="linear" && manager.activeNotifications.any {it.id==1 && it.notification.smallIcon.resId==skinNotificationIcon("linear")}}
            assertEquals("图标仍被队列挡住", "prism",enabledLauncherSkin(context))
            assertEquals(session.generation,vm.service.snapshot.value.generation)
            assertEquals(session.started,vm.service.snapshot.value.started)
            compose.runOnIdle {vm.setting("uiSkin","claude");vm.setting("uiSkin","miro")}
            compose.waitUntil(2000){vm.data.value.setting("uiSkin")=="miro"}
        } finally {lock.unlock();vm.service.stop()}
        compose.waitUntil(15000){!vm.busy.value && enabledLauncherSkin(context)=="miro" && vm.service.snapshot.value.state==0}
    }
    @Test fun failedSaveRestoresAppearanceAndResetWinsOverQueuedSkin() {
        prepare();compose.waitForIdle();val original=fingerprint()
        vm.store.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_skin BEFORE INSERT ON kv WHEN NEW.key='uiSkin' BEGIN SELECT RAISE(ABORT, 'fixture skin save failure'); END")
        try {
            compose.runOnIdle {vm.setting("uiSkin","supabase")}
            compose.waitUntil(15000){!vm.busy.value}
            assertEquals("prism",vm.data.value.setting("uiSkin"));assertEquals("prism",enabledLauncherSkin(context))
            compose.waitUntil(2000){fingerprint()==original}
        } finally {vm.store.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fail_skin")}
        val locked=CountDownLatch(1);val release=CountDownLatch(1)
        val holder=thread {synchronized(vm.store){locked.countDown();release.await(10,TimeUnit.SECONDS)}}
        try {
            assertTrue(locked.await(5,TimeUnit.SECONDS))
            compose.runOnIdle {vm.setting("uiSkin","miro");vm.resetSettings()}
        } finally {release.countDown();holder.join(5000);assertFalse(holder.isAlive)}
        compose.waitUntil(15000){!vm.busy.value}
        assertFalse(vm.data.value.settings.containsKey("uiSkin"));assertEquals("prism",enabledLauncherSkin(context))
    }
    @Test fun backgroundSavesKeepIconsBoundWithoutAnActiveComposition() {
        prepare();val model=vm
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        fun await(condition:()->Boolean) {
            val end=SystemClock.elapsedRealtime()+5000
            while(!condition() && SystemClock.elapsedRealtime()<end)SystemClock.sleep(10)
            assertTrue("后台保存未同步桌面图标",condition())
        }
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {model.setting("uiSkin","linear")}
            await {!model.busy.value && model.data.value.setting("uiSkin")=="linear" && enabledLauncherSkin(context)=="linear"}
            InstrumentationRegistry.getInstrumentation().runOnMainSync {model.setting("uiSkin","prism");model.setting("uiSkin","linear");model.setting("uiSkin","prism")}
            await {!model.busy.value && model.data.value.setting("uiSkin")=="prism" && enabledLauncherSkin(context)=="prism"}
        } finally {compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)}
    }
}
