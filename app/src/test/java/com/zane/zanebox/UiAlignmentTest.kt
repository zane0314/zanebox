package com.zane.zanebox

import com.zane.zanebox.config.ConfigBuilder
import com.zane.zanebox.data.*
import com.zane.zanebox.ui.updateNodeField
import com.zane.zanebox.ui.validatePreference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UiAlignmentTest {
    @Test fun hysteria2PortEditorsChooseTheEditedScalarOrValidatedHopList() {
        val hopping="""{"type":"hysteria2","server":"example.test","password":"fixture","tls":{"enabled":true},"server_ports":["443","500:600"]}"""
        val config=JSONObject(ConfigBuilder.build(AppData(nodes=listOf(Node(1,1,"hop",hopping)),groups=listOf(Group(1,"g")))))
        val out=config.getJSONArray("outbounds");val native=(0 until out.length()).map { out.getJSONObject(it) }.first { it.optString("type")=="hysteria2" }
        assertEquals("443:443",native.getJSONArray("server_ports").getString(0))
        val single=JSONObject(com.zane.zanebox.ui.updateNodeField(hopping,"server_port","8443","number"))
        assertEquals(8443,single.getInt("server_port"));assertFalse(single.has("server_ports"))
        val again=JSONObject(com.zane.zanebox.ui.updateNodeField(single.toString(),"server_ports","443\n500-600","list"))
        assertFalse(again.has("server_port"));assertEquals("500:600",again.getJSONArray("server_ports").getString(1))
        try {com.zane.zanebox.ui.updateNodeField(hopping,"server_ports","70000","list");fail("invalid hop port")}catch(_:IllegalArgumentException){}
    }

    @Test fun upgradeAndXhttpHostEditorsReachTheNativeAuthorityField() {
        listOf("httpupgrade","xhttp").forEach { type->
            val old=JSONObject("""{"type":"vless","server":"example.test","server_port":443,"uuid":"11111111-1111-4111-8111-111111111111","transport":{"type":"$type","headers":{"Host":"cdn.example","X-Test":"kept"}}}""")
            ConfigBuilder.normalizeTransportHost(old)
            val transport=old.getJSONObject("transport");assertEquals("cdn.example",transport.getString("host"));assertFalse(transport.getJSONObject("headers").has("Host"));assertEquals("kept",transport.getJSONObject("headers").getString("X-Test"))
            val data=AppData(nodes=listOf(Node(1,1,"n",old.toString())),groups=listOf(Group(1,"g")))
            val outs=JSONObject(ConfigBuilder.build(data)).getJSONArray("outbounds")
            assertEquals("cdn.example",(0 until outs.length()).map { outs.getJSONObject(it) }.first { it.optString("type")=="vless" }.getJSONObject("transport").getString("host"))
        }
        val upgrade=JSONObject(com.zane.zanebox.subscription.SubscriptionParser.parse("vless://11111111-1111-4111-8111-111111111111@example.test:443?type=httpupgrade&host=cdn.example&path=%2Fws").single().outbound).getJSONObject("transport")
        assertEquals("cdn.example",upgrade.getString("host"));assertFalse(upgrade.has("headers"))
    }
    @Test fun automaticProxySelectionComplementsCommonAppsInBypassMode() {
        val apps=listOf(com.zane.zanebox.config.InstalledApp("Gmail","com.google.android.gm"),com.zane.zanebox.config.InstalledApp("Play","com.android.vending"),com.zane.zanebox.config.InstalledApp("Local","com.example.local"))
        assertEquals(setOf("com.google.android.gm","com.android.vending"),com.zane.zanebox.config.commonAppPackages(apps,"com.zane.zanebox"))
        assertEquals(setOf("com.example.local"),com.zane.zanebox.config.commonAppPackages(apps,"com.zane.zanebox",true))
    }
    @Test fun subscriptionSharingPreservesNamesAndUrlsAndLatencyUsesTheStoredResult() {
        val group=Group(1,"分组 空格+ &","https://example.test/sub?token=a+b&name=中文")
        val link=IncomingLink.parse(com.zane.zanebox.ui.subscriptionShareLink(group))
        assertEquals(group.name,link.name);assertEquals(group.subscriptionUrl,link.subscriptionUrl)
        assertEquals("",com.zane.zanebox.ui.subscriptionShareLink(group.copy(subscriptionUrl="")))
        val node=Node(1,1,"n","{}")
        assertEquals("未测试",com.zane.zanebox.ui.nodeTestLabel(node));assertEquals("失败",com.zane.zanebox.ui.nodeTestLabel(node.copy(status=1,ping=0)))
        assertEquals("12 ms",com.zane.zanebox.ui.nodeTestLabel(node.copy(status=3,ping=12)))
    }
    @Test fun diagnosticAutoCandidatesCoverEnabledSubscriptionsAndLegacyRegionsFallBack() {
        val nodes=listOf(Node(1,1,"日本 A","""{"type":"socks","server":"a.test","server_port":1080}"""),Node(2,2,"美国 B","""{"type":"socks","server":"b.test","server_port":1080}"""),Node(3,2,"日本 C","""{"type":"socks","server":"c.test","server_port":1080}"""),Node(4,3,"日本隐藏","""{"type":"socks","server":"d.test","server_port":1080}"""))
        val data=AppData(nodes=nodes,groups=listOf(Group(1,"A","https://example.test/a"),Group(2,"B","https://example.test/b"),Group(3,"隐藏",enabled=false)),merges=listOf(MergeGroup(5,"汇总",nodeIds=listOf(2,3,4))),settings=mapOf("selectedNodeId" to "1","smartSourceMergeId" to "5","smart.ai.target" to "region:jp","smartRules.ai" to "DOMAIN-SUFFIX,example.test"))
        assertEquals(listOf(1L),ConfigBuilder.smartTargetNodeIds(data,"region:jp"))
        assertEquals(listOf(1L,2L,3L),ConfigBuilder.smartTargetNodeIds(data,"auto"))
        assertTrue(ConfigBuilder.smartTargetNodeIds(data,"direct").isEmpty())
        assertTrue(ConfigBuilder.smartTargetNodeIds(data,"node:4").isEmpty())
        val route=JSONObject(ConfigBuilder.build(data)).getJSONObject("route").getJSONArray("rules")
        assertEquals("proxy",(0 until route.length()).map{route.getJSONObject(it)}.first{it.has("domain_suffix")}.getString("outbound"))
        assertFalse(ConfigBuilder.warnings(data.copy(settings=data.settings+("smart.ai.target" to "auto"))).any { it.contains("没有可用节点") })
    }
    @Test fun rulePortEditorIncludesStoredRanges() {
        val advanced=JSONObject("""{"port":[443],"port_range":["1000:1002"],"source_port_range":["2000:2002"]}""")
        assertEquals("443\n1000:1002",com.zane.zanebox.ui.jsonFieldText(advanced,"port"))
        assertEquals("2000:2002",com.zane.zanebox.ui.jsonFieldText(advanced,"source_port"))
    }

    @Test fun ruleFieldUpdatesClearOldRangesAndPreserveAdvancedMatches() {
        val original="""{"port":[443],"port_range":["1000:1002"],"source_port_range":["2000:2002"],"future_match":{"enabled":true}}"""
        val changed=JSONObject(com.zane.zanebox.ui.updateRuleField(original,"port","80"))
        ConfigBuilder.normalizeAdvanced(changed)
        assertEquals(80,changed.getJSONArray("port").getInt(0));assertFalse(changed.has("port_range"))
        assertTrue(changed.getJSONObject("future_match").getBoolean("enabled"))
        assertTrue(changed.has("source_port_range"))
        val cleared=JSONObject(com.zane.zanebox.ui.updateRuleField(changed.toString(),"source_port",""))
        assertFalse(cleared.has("source_port"));assertFalse(cleared.has("source_port_range"))
        val advanced=JSONObject("""{"port":[8443],"network":["tcp"],"future_match":true}""")
        assertEquals("8443",com.zane.zanebox.ui.jsonFieldText(advanced,"port"))
        val updated=JSONObject(com.zane.zanebox.ui.updateRuleField(advanced.toString(),"protocol","tls"))
        assertEquals(8443,updated.getJSONArray("port").getInt(0));assertTrue(updated.getBoolean("future_match"))
    }
    @Test fun editingKeepsUnknownFieldsAndUsesNativeFieldTypes() {
        val source="""{"type":"vless","future_option":{"enabled":true},"tls":{"enabled":true,"server_name":"old"}}"""
        val tls=updateNodeField(source,"tls.server_name","new.example","text")
        val alpn=updateNodeField(tls,"tls.alpn","h2,http/1.1","list")
        val changed=JSONObject(updateNodeField(alpn,"server_port","443","number"))
        assertTrue(changed.getJSONObject("future_option").getBoolean("enabled"))
        assertEquals("new.example",changed.getJSONObject("tls").getString("server_name"))
        assertEquals(2,changed.getJSONObject("tls").getJSONArray("alpn").length())
        assertEquals(443,changed.getInt("server_port"))
        assertFalse(JSONObject(updateNodeField(changed.toString(),"tls.server_name","","text")).getJSONObject("tls").has("server_name"))
    }
    @Test fun portAndConcurrencyValidationRejectInvalidValues() {
        validatePreference("mixedPort","65535");validatePreference("testConcurrency","16");validatePreference("nodeRegion.1","jp")
        listOf("mixedPort" to "65536","testConcurrency" to "17","testUrl" to "file:///tmp/a","nodeRegion.1" to "unknown").forEach{(key,value)->
            try {validatePreference(key,value);fail("accepted $key=$value")}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun transportSwitchAndWireguardReservedUseValidNativeShapes() {
        val source="""{"type":"vless","transport":{"type":"ws","path":"/old","headers":{"Host":"old"}}}"""
        assertFalse(JSONObject(updateNodeField(source,"transport.type","","text")).has("transport"))
        val grpc=JSONObject(updateNodeField(source,"transport.type","grpc","text")).getJSONObject("transport")
        assertEquals("grpc",grpc.getString("type"));assertFalse(grpc.has("path"))
        val reserved=JSONObject(updateNodeField("{}","reserved","[1,2,3]","reserved")).getJSONArray("reserved")
        assertEquals(3,reserved.length());assertEquals(2,reserved.getInt(1))
    }
    @Test fun sshLinksUseNativeUserKey() {
        val o=JSONObject(com.zane.zanebox.subscription.SubscriptionParser.parse("ssh://fixture:demo@example.com:22#ssh").single().outbound)
        assertEquals("fixture",o.getString("user"));assertFalse(o.has("username"))
    }
    @Test fun advancedUiPreferencesReachTheSharedConfigPipeline() {
        val node=Node(1,1,"tls","""{"type":"trojan","server":"example.com","server_port":443,"password":"fixture","tls":{"enabled":true}}""")
        val data=AppData(nodes=listOf(node),groups=listOf(Group(1,"g")),merges=listOf(MergeGroup(2,"m",nodeIds=listOf(1),mode="urltest")),
            rules=listOf(RouteRule(3,"domain",domains="example.org",outbound="direct")),settings=mapOf("concurrentDial" to "true","resolveDestination" to "true","enableDnsRouting" to "false","enableTLSFragment" to "true","urlTestInterval" to "12m","urlTestTolerance" to "25"))
        val root=JSONObject(ConfigBuilder.build(data));val route=root.getJSONObject("route")
        assertEquals("fallback",route.getString("default_network_strategy"))
        assertTrue((0 until route.getJSONArray("rules").length()).any{route.getJSONArray("rules").getJSONObject(it).optString("action")=="resolve"})
        val out=root.getJSONArray("outbounds");val objects=(0 until out.length()).map{out.getJSONObject(it)}
        assertTrue(objects.first{it.optString("type")=="trojan"}.getJSONObject("tls").getBoolean("fragment"))
        val group=objects.first{it.optString("type")=="urltest"};assertEquals("12m",group.getString("interval"));assertEquals(25,group.getInt("tolerance"))
        val dnsRules=root.getJSONObject("dns").getJSONArray("rules")
        assertFalse((0 until dnsRules.length()).any{dnsRules.getJSONObject(it).has("domain") && dnsRules.getJSONObject(it).optString("server")=="dns-direct"})
    }
    @Test fun backupSelectionAndAssetHeadersRejectBrokenDependencies() {
        val data=AppData(nodes=listOf(Node(1,1,"A","""{"type":"socks","server":"example.com","server_port":1080}""")),groups=listOf(Group(1,"A")),rules=listOf(RouteRule(2,"target",outbound="node:1")),settings=mapOf("selectedNodeId" to "1","smart.ai.target" to "node:1"))
        try { com.zane.zanebox.backup.backupSelection(data,com.zane.zanebox.backup.BackupScope(false,true,true));fail("dangling rule accepted") } catch(_:IllegalArgumentException){}
        val settings=com.zane.zanebox.backup.backupSelection(data,com.zane.zanebox.backup.BackupScope(false,false,true))
        assertTrue(settings.nodes.isEmpty());assertEquals(0L,settings.selectedNodeId);assertEquals("off",settings.setting("smart.ai.target"))
        listOf("geoip","geosite").forEach { kind->try { com.zane.zanebox.config.geoAssetCode(kind,"<html>bad download body</html>".toByteArray());fail("HTML accepted as $kind") } catch(_:IllegalArgumentException){} }
        val site=byteArrayOf(0,1,2,'c'.code.toByte(),'n'.code.toByte(),0,1)+ByteArray(16)
        assertEquals("cn",com.zane.zanebox.config.geoAssetCode("geosite",site))
    }
    @Test fun subscriptionTlsSettingsAreExplicitAndKeepHttpFixtures() {
        val client=com.zane.zanebox.subscription.SubscriptionClient.requestClient(mapOf("appTLSVersion" to "1.3"))
        assertEquals(listOf(okhttp3.TlsVersion.TLS_1_3),client.connectionSpecs.first().tlsVersions)
        assertTrue(client.connectionSpecs.any{!it.isTls})
        try { com.zane.zanebox.subscription.SubscriptionClient.requestClient(mapOf("appTLSVersion" to "1.0"));fail("obsolete TLS accepted") }catch(_:IllegalArgumentException){}
    }
    @Test fun subscriptionUsesExplicitTlsTrustAndVersionOnRealHandshake()=kotlinx.coroutines.runBlocking {
        val path=System.getenv("ZANEBOX_WEBDAV_TEST_KEYSTORE")
        org.junit.Assume.assumeTrue("需要本地 TLS 夹具",path!=null)
        val store=java.security.KeyStore.getInstance("PKCS12").apply { java.io.File(requireNotNull(path)).inputStream().use { load(it,"fixturepass".toCharArray()) } }
        val keys=javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply { init(store,"fixturepass".toCharArray()) }
        val tls=javax.net.ssl.SSLContext.getInstance("TLS").apply { init(keys.keyManagers,null,null) }
        val server=okhttp3.mockwebserver.MockWebServer().apply { useHttps(tls.socketFactory,false);start() }
        try {
            val url=server.url("/subscription").toString()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("tls-fixture"))
            try { com.zane.zanebox.subscription.SubscriptionClient.fetch(url);fail("默认信任了自签证书") }catch(_:javax.net.ssl.SSLHandshakeException){}
            val result=com.zane.zanebox.subscription.SubscriptionClient.fetch(url,settings=mapOf("allowInsecureOnRequest" to "true","appTLSVersion" to "1.3"))
            assertEquals("tls-fixture",result.body)
            assertEquals(okhttp3.TlsVersion.TLS_1_3,server.takeRequest().handshake!!.tlsVersion)
        } finally { server.shutdown() }
    }
    @Test fun appearanceLabelsFollowTheSelectedLanguage() {
        assertEquals("Settings",com.zane.zanebox.ui.translateText("设置","en"))
        assertEquals("設定",com.zane.zanebox.ui.translateText("设置","zh-TW"))
        assertEquals("用户节点名称",com.zane.zanebox.ui.translateText("用户节点名称","en"))
        assertEquals("zh-CN",com.zane.zanebox.ui.resolveUiLanguage("","zh-CN"))
    }
    @Test fun karingBinaryAndJsonSourcesUseNativeRuleSets() {
        val data=AppData(nodes=listOf(Node(1,1,"A","""{"type":"socks","server":"example.com","server_port":1080}""")),groups=listOf(Group(1,"A")),settings=mapOf("smart.ai.target" to "direct","smartUrl.ai" to "https://example.test/AI.srs","smartUpdated.ai" to "123","smart.netflix.target" to "direct","smartUrl.netflix" to "https://example.test/Netflix.json","smartRules.netflix" to """{"version":3,"rules":[{"domain":["example.org"]}]}"""))
        val sets=JSONObject(ConfigBuilder.build(data)).getJSONObject("route").getJSONArray("rule_set")
        val byTag=(0 until sets.length()).map{sets.getJSONObject(it)}.associateBy{it.getString("tag")}
        val binary=byTag.getValue("smart-set-ai");assertEquals("remote",binary.getString("type"));assertEquals("binary",binary.getString("format"));assertTrue(binary.getString("url").endsWith("#zanebox-update=123"))
        assertEquals("inline",byTag.getValue("smart-set-netflix").getString("type"))
    }
    @Test fun explicitGeoRuleSetFieldsProduceNativeDefinitions() {
        val data=AppData(nodes=listOf(Node(1,1,"A","""{"type":"socks","server":"example.com","server_port":1080}""")),groups=listOf(Group(1,"A")),rules=listOf(RouteRule(2,"geo",domains="geoip:cn",advanced="""{"rule_set":"geosite:cn","port":"443,1000-1002","network":"tcp"}""")))
        val route=JSONObject(ConfigBuilder.build(data)).getJSONObject("route")
        assertEquals(2,route.getJSONArray("rule_set").length())
        val rule=(0 until route.getJSONArray("rules").length()).map{route.getJSONArray("rules").getJSONObject(it)}.first{it.has("port")}
        assertEquals(2,rule.getJSONArray("rule_set").length());assertEquals(443,rule.getJSONArray("port").getInt(0));assertEquals("1000:1002",rule.getJSONArray("port_range").getString(0))
    }
    @Test fun customApplicationPoliciesUseEveryEnabledSubscriptionRatherThanTheOldSourceGroup() {
        val data=AppData(nodes=listOf(Node(1,1,"A","""{"type":"socks","server":"example.com","server_port":1080}"""),Node(2,2,"B","""{"type":"socks","server":"other.example","server_port":1080}""")),
            groups=listOf(Group(1,"A","https://example.test/a"),Group(2,"B","https://example.test/b")),settings=mapOf("smartCustom.custom_3.name" to "工作应用","smartCustom.custom_3.packages" to "com.example.work","smart.custom_3.target" to "auto","smartSourceGroupId" to "2"))
        val root=JSONObject(ConfigBuilder.build(data));val rules=root.getJSONObject("route").getJSONArray("rules")
        val app=(0 until rules.length()).map{rules.getJSONObject(it)}.firstOrNull{it.optJSONArray("package_name")?.optString(0)=="com.example.work"}
        assertNotNull("自定义应用必须进入运行配置",app)
        assertEquals("smart-custom_3",app!!.getString("outbound"))
        val out=root.getJSONArray("outbounds");val group=(0 until out.length()).map{out.getJSONObject(it)}.first{it.optString("tag")=="smart-custom_3"}
        assertEquals("node-1",group.getJSONArray("outbounds").getString(0))
        assertEquals("node-2",group.getJSONArray("outbounds").getString(1))
        assertEquals(2,group.getJSONArray("outbounds").length())
    }
}
