package com.zane.zanebox

import com.zane.zanebox.data.*
import com.zane.zanebox.ui.validatePreference
import org.junit.Assert.*
import org.junit.Test

class LatencySettingsTest {
    @Test fun defaultsAndLegacyValuesRespectTheHardLimit() {
        assertEquals("12",AppData().setting("testConcurrency"))
        assertEquals("4000",AppData().setting("testTimeout"))
        val saved=AppData(settings=mapOf("testTimeout" to "10000","testConcurrency" to "2"))
        assertEquals(4000,SettingDefaults.testTimeout(saved.setting("testTimeout")))
        assertEquals("10000",saved.setting("testTimeout"))
        assertEquals(2,SettingDefaults.testConcurrency(saved.setting("testConcurrency")))
        assertEquals(12,SettingDefaults.testConcurrency("legacy-invalid"))
        assertEquals(4000,SettingDefaults.testTimeout("invalid"))
        validatePreference("testTimeout","4000")
        for(value in listOf("999","4001","10000")) {
            try { validatePreference("testTimeout",value);fail("accepted $value") } catch(_:IllegalArgumentException) {}
        }
    }
}
