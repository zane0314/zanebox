package com.zane.zanebox

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.ui.openBatteryOptimizationSettings
import org.junit.Assert.*
import org.junit.Test

class BatteryFallbackTest {
    @Test fun unavailableRequestFallsBackWithoutLosingThePackageAndAllFailuresAreReported() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val attempts=mutableListOf<Intent>()
        var failures=1
        val context=object:ContextWrapper(base) {
            override fun startActivity(intent:Intent) {
                attempts.add(intent)
                if(attempts.size<=failures)throw ActivityNotFoundException("controlled missing settings entry")
            }
        }
        openBatteryOptimizationSettings(context,false)
        assertEquals(listOf(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),attempts.map{it.action})
        assertEquals("package:${base.packageName}",attempts.first().data.toString())
        assertTrue(attempts.all{it.flags and Intent.FLAG_ACTIVITY_NEW_TASK!=0})
        attempts.clear();failures=0;openBatteryOptimizationSettings(context,true)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,attempts.single().action)
        attempts.clear();failures=3
        try {openBatteryOptimizationSettings(context,false);fail("全部入口不可用必须报告失败")}catch(_:ActivityNotFoundException){}
        assertEquals(3,attempts.size)
    }
}
