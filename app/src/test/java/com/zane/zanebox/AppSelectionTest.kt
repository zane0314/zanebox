package com.zane.zanebox

import com.zane.zanebox.config.*
import com.zane.zanebox.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppSelectionTest {
    private val fixture = AppData(nodes=listOf(Node(1,1,"fixture","""{"type":"socks","server":"127.0.0.1","server_port":1080}""")),groups=listOf(Group(1,"fixture")),settings=mapOf("smart.ai.target" to "direct","smartCustom.ai.packages" to "com.example.allowed\ncom.example.excluded","smartRules.ai" to "","perAppEnabled" to "true","perAppMode" to "include","perAppPackages" to "com.example.allowed"))

    @Test fun smartApplicationRulesStayInsideVpnScopeWithoutDestroyingStoredSelection() {
        for ((mode,expected) in listOf("include" to "com.example.allowed","exclude" to "com.example.excluded")) {
            val data=fixture.copy(settings=fixture.settings+("perAppMode" to mode))
            val rules=JSONObject(ConfigBuilder.build(data)).getJSONObject("route").getJSONArray("rules")
            val appRule=(0 until rules.length()).map{rules.getJSONObject(it)}.single{it.has("package_name")}
            assertEquals(listOf(expected),(0 until appRule.getJSONArray("package_name").length()).map{appRule.getJSONArray("package_name").getString(it)})
            assertEquals("com.example.allowed\ncom.example.excluded",data.setting("smartCustom.ai.packages"))
        }
    }

    @Test fun tenApplicationWhitelistAndBlacklistFilterEverySelectionPath() {
        val apps=(0 until 12).map{InstalledApp("App $it","com.example.app$it")}
        val ten=apps.take(10).map{it.packageName}.toSet()
        val base=fixture.copy(settings=fixture.settings+("perAppPackages" to ten.joinToString("\n")))
        for(mode in listOf("include","exclude")) {
            val data=base.copy(settings=base.settings+("perAppMode" to mode))
            val expected=if(mode=="include")ten else apps.drop(10).map{it.packageName}.toSet()
            assertEquals(expected,visibleApps(apps,emptySet(),"",true,data).map{it.packageName}.toSet())
            assertEquals(expected,parseAppPaste(apps.joinToString("\n"){it.packageName},apps.map{it.packageName}.toSet(),data).accepted)
            assertEquals(emptyList<InstalledApp>(),visibleApps(apps,emptySet(),if(mode=="include")"app11" else "app0",true,data))
        }
        val disabled=base.copy(settings=base.settings+("perAppEnabled" to "false"))
        assertEquals(12,visibleApps(apps,emptySet(),"",true,disabled).size)
    }

    @Test fun commonSelectionUsesUpstreamBoundariesCompanionsAndScope() {
        val apps=listOf(InstalledApp("Gmail","com.google.android.gm"),InstalledApp("Play services","com.google.android.gms",true,false),InstalledApp("Downloads","com.android.providers.downloads",true,false),InstalledApp("Renamed ChatGPT","com.example.ai"),InstalledApp("Local","com.example.local"),InstalledApp("Google WebView","com.google.android.webview",true,false),InstalledApp("Links","com.zane.zanebox"))
        val common=commonAppPackages(apps,"com.zane.zanebox")
        assertEquals(setOf("com.google.android.gm","com.google.android.gms","com.android.providers.downloads","com.example.ai"),common)
        assertFalse(CommonProxyApps.matches("com.discordevil","Local"))
        assertFalse(CommonProxyApps.matches("com.google.android.apps.walletnfcrel","Google Wallet"))
        val allowed=fixture.copy(settings=fixture.settings+("perAppPackages" to "com.google.android.gm\ncom.example.local"))
        assertEquals(setOf("com.google.android.gm"),filterVpnPackages(allowed,common))
        assertEquals(setOf("com.example.local","com.google.android.webview"),commonAppPackages(apps,"com.zane.zanebox",true))
    }

    @Test fun selectedAppsKeepAlphabeticalOrderAndSystemVisibility() {
        val apps=listOf(InstalledApp("Zulu","com.example.z"),InstalledApp("alpha","com.example.a"),InstalledApp("Beta","com.example.b"),InstalledApp("System","com.example.s",true,false))
        assertEquals(listOf("com.example.b","com.example.z","com.example.a"),visibleApps(apps,setOf("com.example.z","com.example.b"),"",false).map{it.packageName})
        assertEquals(listOf("com.example.a","com.example.z","com.example.b","com.example.s"),visibleApps(apps,setOf("com.example.z","com.example.a"),"",true).map{it.packageName})
    }

    @Test fun pasteDeduplicatesAndRejectsUninstalledInvalidAndOutOfScopePackages() {
        val installed=setOf("com.example.allowed","com.example.excluded")
        val paste=parseAppPaste("com.example.allowed\ncom.example.allowed,com.example.excluded;com.example.missing invalid!",installed,fixture)
        assertEquals(setOf("com.example.allowed"),paste.accepted)
        assertEquals(3,paste.skipped)
    }

    @Test fun previewUsesActualReferencesAndNeverReportsDatabaseAsLoaded() {
        val data=fixture.copy(rules=listOf(RouteRule(7,"China",domains="geosite:cn",enabled=true),RouteRule(8,"China IP",enabled=false,advanced="""{"rule_set":["geoip:cn"]}""")))
        val previews=buildRulePreviews(data,geoPresent={it=="geosite"},builtinText={if(it=="ai")"DOMAIN-SUFFIX,example.test" else ""})
        assertFalse(previews.any{it.title in listOf("Global.list","Domestic.list")})
        val site=previews.single{it.title=="geosite:cn"};assertNull(site.count);assertTrue(site.status.contains("本地数据库存在"));assertTrue(site.status.contains("未验证"))
        val ip=previews.single{it.title=="geoip:cn"};assertTrue(ip.status.contains("已停用"));assertTrue(ip.status.contains("尚未就绪"))
        val builtin=buildRulePreviews(fixture.copy(settings=fixture.settings-"smartRules.ai"),builtinText={if(it=="ai")"DOMAIN-SUFFIX,example.test" else ""}).single{it.title=="ai"}
        assertTrue(builtin.status.contains("内置"));assertEquals(1,builtin.count)
        val remote=buildRulePreviews(AppData(settings=mapOf("smartUrl.custom" to "https://example.test/rules.srs","smart.custom.target" to "off"))).single{it.title=="custom"}
        assertNull(remote.count);assertTrue(remote.status.contains("策略关闭"));assertTrue(remote.status.contains("仅配置 URL"))
    }
    @Test fun backupPreservesModesIndependentListsAndInactiveHistory() {
        val data=fixture.copy(settings=fixture.settings+mapOf("smartCustom.youtube.packages" to "com.example.excluded","smart.youtube.target" to "direct"))
        val manager=com.zane.zanebox.backup.BackupManager()
        val restored=manager.`import`(manager.export(data))
        assertEquals(data.settings,restored.settings)
        assertEquals(setOf("com.example.allowed"),effectiveSmartPackages(restored,"ai"))
        assertEquals(setOf("com.example.allowed","com.example.excluded"),effectiveSmartPackages(restored.copy(settings=restored.settings+("perAppEnabled" to "false")),"ai"))
    }

    @Test fun uninstalledAndOrphanSelectionsRemainStoredButAreNotEffective() {
        val data=fixture.copy(settings=fixture.settings+mapOf("perAppEnabled" to "false","smartRules.orphan" to "DOMAIN-SUFFIX,orphan.test","smartRules.geosite:cn" to "DOMAIN-SUFFIX,old.test"))
        assertEquals(setOf("com.example.allowed"),effectiveSmartPackages(data,"ai",setOf("com.example.allowed")))
        val rules=JSONObject(ConfigBuilder.build(data,installedPackages=setOf("com.example.allowed"))).getJSONObject("route").getJSONArray("rules")
        val packages=(0 until rules.length()).map{rules.getJSONObject(it)}.single{it.has("package_name")}.getJSONArray("package_name")
        assertEquals(1,packages.length());assertEquals("com.example.allowed",packages.getString(0))
        val previews=buildRulePreviews(data,installed=setOf("com.example.allowed"))
        assertFalse(previews.any{it.title in listOf("orphan","geosite:cn")})
        assertTrue(previews.single{it.title=="ai"}.subtitle.contains("1 个有效应用"))
        assertEquals("com.example.allowed\ncom.example.excluded",data.setting("smartCustom.ai.packages"))
    }

}
