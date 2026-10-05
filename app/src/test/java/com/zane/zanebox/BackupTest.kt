package com.zane.zanebox

import com.zane.zanebox.backup.*
import com.zane.zanebox.data.*
import com.zane.zanebox.config.ConfigBuilder
import com.zane.zanebox.config.Purpose
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupTest {
    private val manager=BackupManager()
    private fun zip(vararg pairs:Pair<String,ByteArray>):ByteArray { val out=ByteArrayOutputStream();ZipOutputStream(out).use { z -> pairs.forEach { (name,bytes) -> z.putNextEntry(ZipEntry(name));z.write(bytes);z.closeEntry() } };return out.toByteArray() }
    private fun reject(block:()->Unit) { try { block();fail("应拒绝输入") } catch(e:IllegalArgumentException) { } catch(e:IllegalStateException) { } }
    @Test fun roundTripPreservesAllFields() {
        val original=AppData(nodes=listOf(Node(1,2,"测试","{\"type\":\"socks\",\"server\":\"example.org\",\"server_port\":1080}",metadata="{\"raw\":1}")),groups=listOf(Group(2,"分组",options="{\"opaque\":true}")),rules=listOf(RouteRule(3,"规则",advanced="{\"port\":\"443\"}",prioritize=true)),settings=mapOf("typed" to "[1,2]"))
        assertEquals(original.toJson(),manager.`import`(manager.export(original)).toJson())
    }
    @Test fun traversalAndUnknownAndTruncatedRejected() {
        reject { manager.`import`(zip("../data.json" to byteArrayOf())) }
        reject { manager.`import`(zip("data.json" to "{}".toByteArray())) }
        reject { manager.`import`(byteArrayOf(80,75,1)) }
        val valid=manager.export(AppData());reject { manager.`import`(valid.copyOf(valid.size-10)) }
    }
    @Test fun duplicateZipAndExpansionBombRejected() {
        val duplicate=zip("data.json" to "{}".toByteArray(),"dAta.json" to "{}".toByteArray())
        for(i in 0 until duplicate.size-8) { if(duplicate.copyOfRange(i,i+9).contentEquals("dAta.json".toByteArray())) duplicate[i+1]='a'.code.toByte() }
        reject { manager.`import`(duplicate) }
        reject { manager.`import`(zip("data.json" to ByteArray(MAX_BACKUP+1))) }
    }
    @Test fun deepAndTrailingJsonRejectedBeforeParsing() {
        reject { manager.`import`(("{\"x\":"+"[".repeat(129)+"0"+"]".repeat(129)+"}").toByteArray()) }
        reject { manager.`import`(("{\"version\":1,\"profiles\":[],\"groups\":[],\"rules\":[],\"settings\":[]} {} ").toByteArray()) }
    }
    @Test fun previousStringAdvancedFieldsNormalizeWithoutLoss() {
        val original=AppData(nodes=listOf(Node(2,1,"Node","{\"type\":\"socks\",\"server\":\"example.org\",\"server_port\":1080}",metadata="{\"opaque\":7}")),groups=listOf(Group(1,"Group",options="{\"opaque\":8}")),rules=listOf(RouteRule(3,"Rule",advanced="{\"opaque\":9}")))
        val root=JSONObject(original.toJson());root.getJSONArray("nodes").getJSONObject(0).put("metadata",original.nodes[0].metadata);root.getJSONArray("groups").getJSONObject(0).put("options",original.groups[0].options);root.getJSONArray("rules").getJSONObject(0).put("advanced",original.rules[0].advanced)
        val data=root.toString().toByteArray();val manifest=JSONObject().put("app","zanebox").put("format",1).put("dataSha256",digest(data)).toString().toByteArray()
        assertEquals(original.toJson(),manager.`import`(zip("manifest.json" to manifest,"data.json" to data)).toJson())
    }
    @Test fun unknownNativeFieldsDoNotSilentlyDisappear() {
        val data=JSONObject(AppData().toJson()).put("unknownFutureField","retained elsewhere").toString().toByteArray()
        val manifest=JSONObject().put("app","zanebox").put("format",1).put("dataSha256",digest(data)).toString().toByteArray()
        reject { manager.`import`(zip("manifest.json" to manifest,"data.json" to data)) }
    }
    @Test fun digestMismatchRejected() {
        val manifest=JSONObject().put("app","zanebox").put("format",1).put("dataSha256","0".repeat(64)).toString().toByteArray()
        reject { manager.`import`(zip("manifest.json" to manifest,"data.json" to AppData().toJson().toByteArray())) }
    }
    @Test fun anyBoxChecksBothDigestsBeforeMigration() {
        val logical="{\"version\":1,\"profiles\":[],\"groups\":[],\"rules\":[],\"settings\":[]}".toByteArray();val prefs="{\"unknown\":{\"theme\":{\"type\":\"string\",\"value\":\"dark\"}}}".toByteArray()
        val manifest=JSONObject().put("app","AnyBox").put("format",2).put("logicalSha256",digest(logical)).put("preferencesSha256",digest(prefs)).toString().toByteArray()
        val restored=manager.`import`(zip("manifest.json" to manifest,"logical.json" to logical,"preferences.json" to prefs))
        assertEquals(JSONObject(prefs.toString(Charsets.UTF_8)).toString(),restored.settings["legacy.preferences"])
        reject { manager.`import`(zip("manifest.json" to manifest,"logical.json" to logical,"preferences.json" to "{}".toByteArray())) }
    }
    @Test fun unsupportedLegacyRecordsFailWithoutPartialResult() {
        val root=JSONObject().put("version",1).put("profiles",org.json.JSONArray().put("AA==")).put("groups",org.json.JSONArray()).put("rules",org.json.JSONArray()).put("settings",org.json.JSONArray())
        reject { manager.`import`(root.toString().toByteArray()) }
    }
    @Test fun webDavRejectsUnsafeBaseAndNamesBeforeNetworking() {
        reject { WebDavClient("http://example.org/backups/","u","p") }
        reject { WebDavClient("https://u:p@example.org/backups/","u","p") }
        val client=WebDavClient("https://example.org/backups/","u","p")
        reject { client.download("../secret.zip") }
        reject { client.download(WebDavEntry("x.zip",0,"","https://example.org/other/x.zip")) }
        reject { client.download(WebDavEntry("x.zip",0,"","https://other.org/backups/x.zip")) }
        reject { client.delete("unrelated.zip") }
        reject { client.download(WebDavEntry("nekobox_backup%2e%2e%2fsecret.json",0,"","https://example.org/backups/nekobox_backup%252e%252e%252fsecret.json")) }
    }
    @Test fun parcelAndKryoBoundsAndStrings() {
        val raw=ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(2).putShort('中'.code.toShort()).putShort('文'.code.toShort()).putShort(0).putShort(0).array()
        val p=ParcelReader(raw);assertEquals("中文",p.string());p.end()
        assertEquals("abc",KryoReader(byteArrayOf(97,98,(99 or 128).toByte())).string())
        assertEquals("A😀",KryoReader(byteArrayOf(0x84.toByte(),65,0xed.toByte(),0xa0.toByte(),0xbd.toByte(),0xed.toByte(),0xb8.toByte(),0x80.toByte())).string())
        reject { ParcelReader(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(Int.MAX_VALUE).array()).bytes() }
    }
    private class KryoWriter {
        val out=ByteArrayOutputStream()
        fun int(v:Int) { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()) }
        fun long(v:Long) { out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()) }
        fun bool(v:Boolean) { out.write(if(v) 1 else 0) }
        fun string(v:String) { require(v.length<63);out.write(128 or (v.length+1));out.write(v.toByteArray()) }
        fun bytes(v:ByteArray) { var size=v.size;while(size>=128) {out.write((size and 127) or 128);size=size ushr 7};out.write(size);out.write(v) }
    }
    private fun parcel(bytes:ByteArray):String { val out=ByteArrayOutputStream();out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).array());out.write(bytes);repeat((4-bytes.size%4)%4) {out.write(0)};return Base64.getEncoder().encodeToString(out.toByteArray()) }
    @Test fun syntheticOriginalSocksAndVlessParcelMigration() {
        val group=KryoWriter().apply { int(1);long(2);long(0);bool(false);string("Group");int(0);int(0);bool(true);long(-1);long(-1);long(7) }
        fun entity(type:Int,bean:KryoWriter,id:Long)=KryoWriter().apply { int(0);long(id);long(2);int(type);long(0);long(10);long(20);int(0);int(30);string("uuid");string("");bytes(bean.out.toByteArray());bool(false) }
        val socks=KryoWriter().apply { int(2);string("example.org");int(1080);int(0);string("user");string("pass");bool(false);int(0);string("SOCKS");string("");string("") }
        val vless=KryoWriter().apply { int(10);string("example.org");int(443);string("00000000-0000-4000-8000-000000000001");string("xtls-rprx-vision");string("none");int(-1);string("ws");string("host.example");string("/ws");int(0);string("");string("tls");string("server.example");string("h2");string("");bool(false);string("chrome");string("public-key");string("abcd");bool(false);string("");int(2);bool(false);bool(false);int(0);int(8);int(0);int(0);int(0);bool(false);int(0);int(0);int(0);string("VLESS");string("");string("") }
        val root=JSONObject().put("version",1).put("profiles",org.json.JSONArray().put(parcel(entity(0,socks,1).out.toByteArray())).put(parcel(entity(4,vless,3).out.toByteArray()))).put("groups",org.json.JSONArray().put(parcel(group.out.toByteArray()))).put("rules",org.json.JSONArray()).put("settings",org.json.JSONArray())
        val data=manager.`import`(root.toString().toByteArray());assertEquals(2,data.nodes.size);assertEquals("SOCKS",data.nodes[0].name);assertEquals("vless",JSONObject(data.nodes[1].outbound).getString("type"));assertEquals("abcd",JSONObject(data.nodes[1].outbound).getJSONObject("tls").getJSONObject("reality").getString("short_id"));assertTrue(data.settings.containsKey("legacy.logical"))
    }

    @Test fun originalPrioritizedProtocolSerializersRoundTrip() {
        fun base(version:Int)=KryoWriter().apply { int(version);string("example.org");int(443) }
        fun finish(k:KryoWriter,name:String,custom:String="",global:String="")=k.apply { int(0);string(name);string(custom);string(global) }
        fun standard(type:Int,alter:Int=0,transport:String="tcp"):KryoWriter = KryoWriter().apply {
            if(type==1) int(0);if(type==6) int(2)
            int(10);string("example.org");int(443);string("00000000-0000-4000-8000-000000000001");string(if(alter==-1) "none" else "auto");string("none");if(type==4) int(alter)
            string(transport);if(transport=="xhttp") { string("host.example");string("/xhttp");string("auto");string("{\"x_padding_bytes\":\"100-200\",\"no_grpc_header\":true}") };string("tls");string("sni.example");string("h2");string("");bool(false);string("");string("");string("");bool(false);string("");int(0);bool(false);bool(false);int(0);int(0);int(0);int(0);int(0);bool(false);int(0);int(0)
            if(type==1) { string("fixture-user");string("fixture-password") };if(type==6) string("fixture-password")
        }
        val protocols=listOf(
            22 to finish(base(1).apply { string("password");string("sni.example");string("h2");string("");string("chrome");bool(false);string("");string("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");string("0123456789abcdef") },"AnyTLS","{\"tls\":{\"insecure\":true,\"alpn+\":[\"http/1.1\"]}}","{\"log\":{\"level\":\"warn\"}}"),
            15 to finish(base(7).apply { int(2);int(1);string("password");int(0);string("obfs");string("sni.example");string("h3");int(50);int(100);bool(false);string("");int(0);int(0);bool(false);int(10);string("443,500-600") },"HY2"),
            20 to finish(base(2).apply { string("password");string("");string("native");string("bbr");string("h3");bool(false);bool(true);int(1500);string("sni.example");bool(false);bool(false);string("");int(5);string("00000000-0000-4000-8000-000000000001") },"TUIC"),
            17 to finish(base(0).apply { string("user");int(1);string("fixture-password");string("") },"SSH"),
            18 to finish(base(2).apply { string("10.0.0.2/32,fd00::2/128");string("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");string("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=");string("AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=");int(1420);string("AQID") },"WG"),
            8 to finish(KryoWriter().apply { int(1);int(2);long(24);long(25) },"Chain"),
            998 to finish(base(0).apply { int(0);string("{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"proxy\"}]}") },"Custom"),
            23 to finish(base(1).apply { string("00000000-0000-4000-8000-000000000001");string("password");string("sni.example");string("");bool(false) },"Juicity"),
            24 to finish(base(3).apply { string("fixture-psk");int(4);string("http");string("host.example");bool(true);string("tcp");string("");string("default") },"Snell"),
            19 to finish(KryoWriter().apply { int(0);int(10);string("example.org");int(443);string("");string("");string("");string("tcp");string("tls");string("sni.example");string("h2");string("");bool(false);string("");string("");string("");bool(false);string("");int(0);bool(false);bool(false);int(0);int(0);int(0);int(0);int(0);bool(false);int(0);int(0);int(3);string("password") },"ShadowTLS"),
            1 to finish(standard(1),"HTTP"),
            4 to finish(standard(4),"VMess"),
            4 to finish(standard(4,-1),"VLESS"),
            6 to finish(standard(6),"Trojan"),
            0 to finish(base(2).apply { int(2);string("fixture-user");string("fixture-password");bool(false) },"SOCKS"),
            2 to finish(base(5).apply { string("aes-128-gcm");string("fixture-password");string("");bool(false);bool(false);bool(false);int(0);int(0);int(0);int(0);int(0);bool(false);int(0);int(0) },"SS"),
            4 to finish(standard(4,-1,"xhttp"),"XHTTP")
        )
        val group=KryoWriter().apply { int(0);long(2);long(0);bool(false);string("Group");int(0);int(0) }
        val profiles=org.json.JSONArray()
        protocols.forEachIndexed { index,(type,bean) -> val entity=KryoWriter().apply { int(0);long(index+10L);long(2);int(type);long(index.toLong());long(0);long(0);int(0);int(-1);string("");string("");bytes(bean.out.toByteArray());bool(false) };profiles.put(parcel(entity.out.toByteArray())) }
        val root=JSONObject().put("version",1).put("profiles",profiles).put("groups",org.json.JSONArray().put(parcel(group.out.toByteArray()))).put("rules",org.json.JSONArray()).put("settings",org.json.JSONArray())
        val data=manager.`import`(root.toString().toByteArray());assertEquals(listOf("anytls","hysteria2","tuic","ssh","wireguard","chain","custom","juicity","snell","shadowtls","http","vmess","vless","trojan","socks","shadowsocks","vless"),data.nodes.map {JSONObject(it.outbound).getString("type")})
        assertEquals("password",JSONObject(data.nodes[0].outbound).getString("password"));assertEquals("443:443",JSONObject(data.nodes[1].outbound).getJSONArray("server_ports").getString(0));assertEquals("500:600",JSONObject(data.nodes[1].outbound).getJSONArray("server_ports").getString(1));assertEquals("AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=",JSONObject(data.nodes[4].outbound).getString("pre_shared_key"))
        assertEquals(data.toJson(),manager.`import`(manager.export(data)).toJson())
        val anyTls=JSONObject(data.nodes[0].outbound);assertEquals(2,anyTls.getJSONObject("tls").getJSONArray("alpn").length());assertTrue(anyTls.getJSONObject("tls").getBoolean("insecure"));assertTrue(anyTls.getJSONObject("tls").has("reality"))
        val covered=JSONObject(ConfigBuilder.build(data.copy(settings=data.settings+mapOf("selectedNodeId" to "10")),Purpose.EXPORT));assertEquals("warn",covered.getJSONObject("log").getString("level"))
        val chainConfig=JSONObject(ConfigBuilder.build(data.copy(settings=data.settings+mapOf("selectedNodeId" to "15")),Purpose.EXPORT));assertFalse(chainConfig.getJSONArray("outbounds").toString().contains("\"type\":\"chain\""));assertTrue(chainConfig.getJSONArray("outbounds").toString().contains("detour"))
        val wgConfig=JSONObject(ConfigBuilder.build(data.copy(settings=data.settings+mapOf("selectedNodeId" to "14")),Purpose.EXPORT));assertEquals("wireguard",wgConfig.getJSONArray("endpoints").getJSONObject(0).getString("type"));assertFalse(wgConfig.getJSONArray("outbounds").toString().contains("\"type\":\"wireguard\""))
        val customConfig=JSONObject(ConfigBuilder.build(data.copy(settings=data.settings+mapOf("selectedNodeId" to "16")),Purpose.EXPORT));assertEquals("direct",customConfig.getJSONArray("outbounds").getJSONObject(0).getString("type"))

        val evidence=System.getenv("ZANEBOX_BACKUP_EVIDENCE_DIR")
        if(evidence!=null) { val directory=java.io.File(evidence).apply { mkdirs() };java.io.File(directory,"synthetic-appdata.json").writeText(data.toJson());data.nodes.forEach { node -> val selected=data.copy(settings=data.settings+mapOf("selectedNodeId" to node.id.toString(),"selectedGroupId" to "2"));val config=ConfigBuilder.build(selected,Purpose.EXPORT);val generated=JSONObject(config);generated.optJSONObject("dns")?.optJSONArray("rules")?.let { rules -> repeat(rules.length()) { assertFalse("DNS规则不能再带旧strategy",rules.getJSONObject(it).has("strategy")) } };val outbound=JSONObject(node.outbound);val stem=if(outbound.optJSONObject("transport")?.optString("type")=="xhttp") "vless-xhttp" else outbound.getString("type");java.io.File(directory,"$stem.json").writeText(config);java.io.File(directory,"$stem-test.json").writeText(ConfigBuilder.build(selected,Purpose.TEST,node.id)) } }

    }

    @Test fun originalTypedSettingsMapToCurrentConsumptionKeys() {
        fun preference(key:String,type:Int,bytes:ByteArray):String {
            val out=ByteArrayOutputStream()
            fun int(v:Int) { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()) }
            fun align() { while(out.size()%4!=0) out.write(0) }
            int(key.length);out.write(key.toByteArray(Charsets.UTF_16LE));out.write(byteArrayOf(0,0));align();int(type);int(bytes.size);out.write(bytes);align();return Base64.getEncoder().encodeToString(out.toByteArray())
        }
        fun int(v:Int)=ByteBuffer.allocate(4).putInt(v).array()
        val settings=org.json.JSONArray()
        listOf("logLevel" to 3,"tunImplementation" to 0,"ipv6Mode" to 2,"trafficSniffing" to 1,"isAutoConnect" to 1,"profileTrafficStatistics" to 1,"networkChangeResetConnections" to 0,"enableClashAPI" to 1,"enableFakeDns" to 0,"nightTheme" to 1).forEach { (key,value)->settings.put(preference(key,3,int(value))) }
        settings.put(preference("proxyApps",1,byteArrayOf(1))).put(preference("bypass",1,byteArrayOf(0))).put(preference("individual",5,"com.example.app".toByteArray()))
        settings.put(preference("connectionTestURL",5,"https://example.test/204".toByteArray())).put(preference("appTheme",5,"#123456".toByteArray())).put(preference("appLanguage",5,"zh-CN".toByteArray()))
        settings.put(preference("domain_strategy_for_remote",5,"auto".toByteArray())).put(preference("domain_strategy_for_direct",5,"prefer_ipv4".toByteArray())).put(preference("domain_strategy_for_server",5,"ipv6_only".toByteArray()))
        val frames=ByteArrayOutputStream().apply { listOf("a","中文").forEach { val raw=it.toByteArray();write(int(raw.size));write(raw) } }.toByteArray();settings.put(preference("unknownSet",6,frames))
        val root=JSONObject().put("version",1).put("profiles",org.json.JSONArray()).put("groups",org.json.JSONArray()).put("rules",org.json.JSONArray()).put("settings",settings)
        val data=manager.`import`(root.toString().toByteArray());assertEquals("debug",data.setting("logLevel"));assertEquals("gvisor",data.setting("tunStack"));assertEquals("prefer_ipv6",data.setting("dnsStrategy"));assertEquals("include",data.setting("perAppMode"));assertTrue(data.bool("perAppEnabled"));assertEquals("com.example.app",data.setting("perAppPackages"));assertEquals(2,org.json.JSONArray(data.setting("unknownSet")).length());assertTrue(data.settings.containsKey("legacy.setting.logLevel"))
        assertTrue(data.bool("autoStart"));assertTrue(data.bool("statsEnabled"));assertFalse(data.bool("networkReset"));assertTrue(data.bool("clashApi"));assertFalse(data.bool("fakeDns"));assertEquals("https://example.test/204",data.setting("testUrl"));assertEquals("",data.setting("dnsStrategyRemote"));assertEquals("prefer_ipv4",data.setting("dnsStrategyDirect"));assertEquals("ipv6_only",data.setting("dnsStrategyServer"));assertEquals("dark",data.setting("theme"));assertEquals("#123456",data.setting("appTheme"));assertEquals("zh-CN",data.setting("appLanguage"))
        listOf("isAutoConnect","profileTrafficStatistics","connectionTestURL","enableClashAPI","networkChangeResetConnections","enableFakeDns","domain_strategy_for_remote","domain_strategy_for_direct","domain_strategy_for_server","nightTheme","appTheme","appLanguage").forEach { assertTrue("保留旧设置 $it",data.settings.containsKey(it)) }
        listOf(0 to "system",1 to "dark",2 to "light",3 to "system").forEach { (mode,expected) ->
            val modeSettings=org.json.JSONArray().put(preference("nightTheme",3,int(mode)))
            val modeRoot=JSONObject().put("version",1).put("profiles",org.json.JSONArray()).put("groups",org.json.JSONArray()).put("rules",org.json.JSONArray()).put("settings",modeSettings)
            assertEquals(expected,manager.`import`(modeRoot.toString().toByteArray()).setting("theme"))
        }
    }

    @Test fun originalSubscriptionSecondsAndGroupUserOrderMigrateExactly() {
        val group=KryoWriter().apply { int(1);long(2);long(5);bool(false);string("Subscription");int(1);int(3);int(0);string("https://example.org/sub");bool(false);bool(true);bool(true);string("fixture-agent");bool(true);int(60);int(1800000000);string("userInfo");int(1);string("^JP");string("8.8.8.8");int(2);bool(false);long(-1);long(-1);long(7) }
        val root=JSONObject().put("version",1).put("profiles",org.json.JSONArray()).put("groups",org.json.JSONArray().put(parcel(group.out.toByteArray()))).put("rules",org.json.JSONArray()).put("settings",org.json.JSONArray())
        val data=manager.`import`(root.toString().toByteArray());val restored=data.groups.single()
        assertEquals(5,restored.order);assertEquals(1800000000000L,restored.updatedAt);assertEquals("https://example.org/sub",restored.subscriptionUrl)
        val options=JSONObject(restored.options);assertEquals(2,options.getInt("nodeSortOrder"));assertEquals(1800000000,options.getInt("subscriptionLastUpdated"));assertEquals("^JP",options.getString("filterRegex"));assertEquals("8.8.8.8",options.getString("serverDnsResolver"))
    }

    @Test fun legacyHy2SinglePortsAndRangesFollowNativeColonSchema() {
        fun read(ports:String,version:Int=7,server:String="example.org"):JSONObject {
            val k=KryoWriter().apply { int(version);string(server);int(443);if(version>=7) int(2);int(1);string("password");if(version>=3) int(0);string("");string("sni.example");if(version>=2) string("h3");int(50);int(100);bool(false);if(version>=1) { string("");int(0);int(0);if(version!=4) bool(false) };if(version>=5) int(10);if(version>=6) string(ports);int(0);string("HY2");string("");string("") }
            return LegacyNodeProtocols.read(KryoReader(k.out.toByteArray()),15,JSONObject()).second
        }
        listOf("443","443:443","443-443").forEach { ports -> val out=read(ports);assertEquals(443,out.getInt("server_port"));assertFalse(out.has("server_ports")) }
        assertEquals("443:443",read("443,500-600").getJSONArray("server_ports").getString(0));assertEquals("500:600",read("443,500-600").getJSONArray("server_ports").getString(1))
        val older=read("",5,"example.org:443,500-600");assertEquals("example.org",older.getString("server"));assertEquals("443:443",older.getJSONArray("server_ports").getString(0));assertEquals("500:600",older.getJSONArray("server_ports").getString(1))
        listOf("0","65536","600-500","1-65535,443","443,,500","+443").forEach { ports -> reject { read(ports) } }
    }

}
