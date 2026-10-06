package com.zane.zanebox.ui

import com.zane.zanebox.data.AppData

internal enum class SettingsApply { LIVE, AUTO, MANUAL }

/** These change permissions, service identity or exposed listeners; keep an explicit apply. */
internal val manualApplyDefaults=mapOf(
    "serviceMode" to "vpn","globalCustomConfig" to "","mixedPort" to "2080","apiPort" to "9090",
    "allowLan" to "false","shareEnabled" to "false","sharePort" to "2081","disableMixedInbound" to "false",
    "appendHttpProxy" to "false","httpProxyBypass" to "","globalAllowInsecure" to "false","clashApi" to "false","strictRoute" to "true",
    "perAppEnabled" to "false","perAppMode" to "exclude","perAppPackages" to "")
private val liveSettings=setOf(
    "browseGroupId","theme","uiSkin","launcherIcon","appLanguage","showBottomBar","alwaysShowAddress","hideFromRecentApps","confirmProfileDelete",
    "autoStart","showDirectSpeed","showGroupInNotification","acquireWakeLock","networkReset","wakeResetConnections",
    "testTimeout","testConcurrency","rulesProvider","rulesGeoipUrl","rulesGeositeUrl","rulesUpdateInterval","rulesUpdateDelay",
    "allowInsecureOnRequest","appTLSVersion","webdavUrl","webdavUser","webdavPassword","exitProbeUrl")
private val autoSettings=setOf(
    "routeMode","tunStack","mtu","statsEnabled","meteredNetwork","logLevel","bypassLan","bypassLanInCore","concurrentDial","sniff","resolveDestination",
    "ipv6Mode","ipv6","dnsStrategy","dnsRemote","dnsDirect","dnsStrategyRemote","dnsStrategyDirect","dnsStrategyServer","domainStrategy","dnsHosts",
    "enableDnsRouting","fakeDns","testUrl","urlTestInterval","urlTestTolerance","enableTLSFragment","muxEnabled","muxProtocol","muxMaxStreams","muxPadding")
internal fun settingsApply(key:String):SettingsApply=when {
    key in liveSettings || key.startsWith("sort_group_") || key.startsWith("sort_mode_group_") -> SettingsApply.LIVE
    key in autoSettings -> SettingsApply.AUTO
    else -> SettingsApply.MANUAL
}
internal fun hasManualSettingsPending(applied:AppData,pending:AppData)=manualApplyDefaults.any { (key,default)->applied.setting(key,default)!=pending.setting(key,default) }
