package com.zane.zanebox

import com.zane.zanebox.data.AppData
import com.zane.zanebox.ui.*
import org.junit.Assert.*
import org.junit.Test

class SettingsApplyTest {
    @Test fun onlyKnownSafeFieldsAutoApplyAndPendingManualChangesBlockThem() {
        for(key in listOf("theme","autoStart","testTimeout","networkReset","showDirectSpeed","acquireWakeLock","sort_group_1"))assertEquals(SettingsApply.LIVE,settingsApply(key))
        for(key in listOf("routeMode","sniff","dnsRemote","ipv6Mode","tunStack","muxEnabled"))assertEquals(SettingsApply.AUTO,settingsApply(key))
        for(key in listOf("serviceMode","mixedPort","globalCustomConfig","allowLan","perAppPackages","unknownFutureSetting"))assertEquals(SettingsApply.MANUAL,settingsApply(key))
        val applied=AppData()
        assertFalse(hasManualSettingsPending(applied,applied.copy(settings=mapOf("mixedPort" to "2080","serviceMode" to "vpn","sniff" to "false"))))
        assertTrue(hasManualSettingsPending(applied,applied.copy(settings=mapOf("mixedPort" to "2081"))))
        assertTrue(hasManualSettingsPending(applied,applied.copy(settings=mapOf("allowLan" to "true"))))
        assertTrue(hasManualSettingsPending(applied,applied.copy(settings=mapOf("clashApi" to "true"))))
    }
}
