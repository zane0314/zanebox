package com.zane.zanebox

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.data.ZaneStore
import com.zane.zanebox.ui.*
import org.junit.Assert.*
import org.junit.Test

class LauncherSkinTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun launcherEntries()=context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName),0)
    private fun assertLauncher(skin:String) {
        val entries=launcherEntries()
        assertEquals("桌面入口必须唯一",1,entries.size)
        assertEquals("${context.packageName}.${launcherAliasForSkin(skin)}",entries.single().activityInfo.name)
        assertEquals(skin,enabledLauncherSkin(context))
    }
    private fun await(condition:()->Boolean) {
        val deadline=SystemClock.uptimeMillis()+15000
        while(!condition() && SystemClock.uptimeMillis()<deadline)SystemClock.sleep(50)
        assertTrue("等待皮肤与桌面状态同步超时",condition())
    }
    @Test fun fiveDistinctIconsKeepExactlyOneLauncherEntry() {
        val original=enabledLauncherSkin(context)
        try {
            val icons=mutableSetOf<Int>()
            for((skin,_) in uiSkinChoices) {
                applyLauncherIcon(context,skin)
                assertLauncher(skin)
                val info=launcherEntries().single().activityInfo
                assertTrue(info.icon!=0);assertNotNull(context.packageManager.getActivityIcon(ComponentName(context,info.name)))
                icons+=info.icon
            }
            assertEquals("五套皮肤应使用五个独立图标资源",5,icons.size)
        } finally {applyLauncherIcon(context,original)}
    }
    @Test fun failedPersistenceRestoresThePreviousLauncher() {
        val original=enabledLauncherSkin(context)
        try {
            applyLauncherIcon(context,"linear")
            try {
                withLauncherSkin(context,"supabase") {assertLauncher("supabase");throw IllegalStateException("fixture database failure")}
                fail("保存失败应被报告")
            } catch(e:IllegalStateException) {assertEquals("fixture database failure",e.message)}
            assertLauncher("linear")
        } finally {applyLauncherIcon(context,original)}
    }
    @Test fun legacyIconIsIgnoredAndRapidChangesRestartAndResetStayBound() {
        val store=ZaneStore(context)
        val original=store.snapshot()
        try {
            store.update {it.copy(settings=it.settings+("uiSkin" to "claude")+("launcherIcon" to "pixel"))}
            val pm=context.packageManager
            pm.setComponentEnabledSettings(listOf(
                PackageManager.ComponentEnabledSetting(ComponentName(context.packageName,"${context.packageName}.launcher.PixelAlias"),PackageManager.COMPONENT_ENABLED_STATE_ENABLED,PackageManager.DONT_KILL_APP),
                PackageManager.ComponentEnabledSetting(ComponentName(context.packageName,"${context.packageName}.${launcherAliasForSkin(enabledLauncherSkin(context))}"),PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP),
            ))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm:AppViewModel
                scenario.onActivity {vm=ViewModelProvider(it)[AppViewModel::class.java]}
                await {!vm.busy.value && vm.data.value.setting("uiSkin")=="claude" && enabledLauncherSkin(context)=="claude"}
                assertLauncher("claude")
                assertEquals("pixel",vm.store.snapshot().setting("launcherIcon"))
                scenario.onActivity {repeat(5) {uiSkinChoices.forEach { (skin,_) -> vm.setting("uiSkin",skin)}}}
                await {!vm.busy.value && vm.data.value.setting("uiSkin")=="miro" && enabledLauncherSkin(context)=="miro"}
                assertLauncher("miro")
                scenario.recreate()
                scenario.onActivity {vm=ViewModelProvider(it)[AppViewModel::class.java]}
                await {!vm.busy.value && vm.data.value.setting("uiSkin")=="miro" && enabledLauncherSkin(context)=="miro"}
                assertLauncher("miro")
                scenario.onActivity {vm.resetSettings()}
                await {!vm.busy.value && !vm.data.value.settings.containsKey("uiSkin") && enabledLauncherSkin(context)=="prism"}
                assertLauncher("prism")
            }
        } finally {store.update {original};applyLauncherIcon(context,resolvedUiSkin(original.setting("uiSkin")));store.close()}
    }
}
