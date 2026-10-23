package com.zane.zanebox

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.config.*
import com.zane.zanebox.core.NativePlatform
import com.zane.zanebox.core.SingBoxEngine
import com.zane.zanebox.data.*
import com.zane.zanebox.runtime.ServiceClient
import com.zane.zanebox.subscription.SubscriptionParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URI
import java.util.Base64

/** Root injects private input into filesDir/chain-real-fixture.json; credentials never enter assertions or evidence. */
class RealSocksChainTest {
    private val ins=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private data class Fixture(val data:AppData,val socks:Node,val expectedIp:String,val sourceIds:List<Long>,val chainIds:List<Long>)
    private fun fixture():Fixture {
        val root=runCatching {JSONObject(File(context.filesDir,"chain-real-fixture.json").readText())}.getOrNull()
        assertNotNull("缺少有效的 root 私密夹具",root)
        val input=root!!;val source=input.getJSONArray("sourceNodes")
        assertEquals("必须使用全部机场源节点",50,source.length())
        val nodes=(0 until source.length()).map {index->val n=source.getJSONObject(index);Node(index+1L,1,n.getString("name"),n.get("outbound").let {if(it is JSONObject)it.toString()else it as String})}
        val uri=input.getString("socksUri")
        val parsed=runCatching {SubscriptionParser.parse(uri).single()}.getOrNull()
        assertNotNull("生产 SOCKS 分享链接导入失败",parsed)
        val rawInfo=URI(uri).userInfo
        val decoded=runCatching {String(Base64.getDecoder().decode(rawInfo.replace('-','+').replace('_','/')),Charsets.UTF_8)}.getOrNull()
        assertTrue("夹具必须覆盖 Base64 user:pass",decoded!=null && decoded.contains(':'))
        val bean=JSONObject(parsed!!.outbound)
        assertTrue("生产导入认证字段不正确",bean.optString("username")==decoded!!.substringBefore(':') && bean.optString("password")==decoded.substringAfter(':'))
        val socks=Node(51,2,"台湾落地",parsed.outbound,shareLink=uri,order=7,metadata="""{"fixture":"preserve"}""")
        val settings=builtinSmartRuleFiles.keys.associate {"smart.$it.target" to "off"}+mapOf(
            "serviceMode" to "vpn","routeMode" to "rule","selectedNodeId" to "1","selectedGroupId" to "1",
            "sniff" to "false","statsEnabled" to "false","bypassLan" to "false","bypassLanInCore" to "false",
            "testUrl" to "https://www.google.com/generate_204","testTimeout" to "4000","rulesUpdateInterval" to "off",
            "globalCustomConfig" to """{"log":{"disabled":true}}""")
        val sourceData=AppData(nodes=nodes+socks,groups=listOf(Group(1,"原始机场 A"),Group(2,"原始台湾 B")),settings=settings)
        val data=sourceData.createDerivedChainGroup(1,"机场→台湾",ChainTargets(landing="node:51"))
        val group=data.groups.single {derivedGroupSourceId(it)==1L}
        val wanted=listOf("日本A 隧道 1.2x","马来西亚01 隧道 1x")
        val ids=wanted.map {name->nodes.singleOrNull {it.name==name}?.id ?: error("夹具缺少指定验收节点")}
        val chains=ids.map {id->data.nodes.single {it.groupId==group.id && derivedNodeSourceId(it)==id}.id}
        assertEquals(50,data.nodes.count {it.groupId==group.id})
        assertTrue("派生操作修改了原始 A/B",data.nodes.filter {it.groupId in setOf(1L,2L)}==sourceData.nodes)
        assertEquals("验收必须保留生产默认远程 DNS","https://1.1.1.1/dns-query",data.setting("dnsRemote"))
        return Fixture(data,socks,input.getString("expectedExitIp"),ids,chains)
    }
    private fun shell(command:String)=ins.uiAutomation.executeShellCommand(command).use {java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText()}
    private fun waitFor(label:String,timeout:Long=45000,predicate:()->Boolean) {
        val end=SystemClock.elapsedRealtime()+timeout
        while(!predicate()) {if(SystemClock.elapsedRealtime()>end)fail("超时：$label");Thread.sleep(100)}
    }
    private fun uidProbe(url:String,label:String):Pair<Int,String> {
        val nonce="realChain"+System.nanoTime()
        shell("am start -W -n com.zane.probe/.ProbeActivity --es url $url --es nonce $nonce --ez expected_vpn true")
        var log=""
        waitFor("$label 独立 UID 请求") {log=shell("logcat -d -s ZaneProbe:I");log.contains("$nonce=")}
        val status=Regex("${Regex.escape(nonce)} HTTP=([0-9]+)").find(log)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val body=log.substringAfter("$nonce=","").substringBefore(" uid=")
        val ip=Regex("\\bip=([^\\s]+)").find(body)?.groupValues?.get(1).orEmpty()
        return status to ip
    }
    @Test fun twoRealAirportNodesReachTaiwanThroughImportedSocksWithDefaultDns() {
        val f=fixture();val store=ZaneStore(context);val original=store.snapshot();val client=ServiceClient(context)
        val platform=NativePlatform(context,null) {_,_->};platform.initialize();val engine=SingBoxEngine(platform)
        val facts=JSONObject();val failed=mutableListOf<String>()
        fun record(label:String,ok:Boolean,value:Any?=null) {facts.put(label,JSONObject().put("pass",ok).put("value",value));if(!ok)failed.add(label)}
        try {
            for(index in f.sourceIds.indices) {
                val role=if(index==0)"japan" else "malaysia"
                for((kind,id) in listOf("raw" to f.sourceIds[index],"chain" to f.chainIds[index])) {
                    val delay=runCatching {engine.test(ConfigBuilder.build(f.data,Purpose.TEST,id),f.data.setting("testUrl"),4000)}.getOrDefault(-1)
                    record("$role.$kind.test4000",delay>0,delay)
                }
            }
            val first=f.data.nodes.first {it.id==f.chainIds.first()}
            store.replace(f.data.copy(settings=f.data.settings+("selectedNodeId" to "${first.id}")+("selectedGroupId" to "${first.groupId}")))
            client.connect();shell("appops set com.zane.zanebox ACTIVATE_VPN allow");client.start()
            waitFor("真实链 VPN 启动",60000) {client.snapshot.value.state in listOf(2,4)}
            assertEquals("真实链 VPN 启动失败",2,client.snapshot.value.state)
            fun connectedChecks(label:String) {
                val google=uidProbe("https://www.google.com/generate_204",label+" Google204")
                record("$label.google204",google.first==204,google.first)
                val trace=uidProbe("https://www.cloudflare.com/cdn-cgi/trace",label+" trace")
                record("$label.traceHttp",trace.first==200,trace.first)
                record("$label.taiwanExit",trace.second==f.expectedIp,trace.second)
            }
            connectedChecks("japan.initial")
            client.stop();waitFor("真实链断开",30000) {client.snapshot.value.state==0}
            client.start();waitFor("真实链重连",60000) {client.snapshot.value.state in listOf(2,4)}
            assertEquals("真实链重连失败",2,client.snapshot.value.state)
            connectedChecks("japan.reconnect")
            client.selectNode(f.chainIds[1]);waitFor("切换第二个派生 C") {store.snapshot().selectedNodeId==f.chainIds[1]}
            connectedChecks("malaysia.switched")
            assertTrue("运行操作修改了原始 A/B",store.snapshot().nodes.filter {it.groupId in setOf(1L,2L)}.map {it.copy(ping=-1,status=0)}==f.data.nodes.filter {it.groupId in setOf(1L,2L)})
            assertTrue("真实链必要阶段未通过："+failed.joinToString(","),failed.isEmpty())
        } finally {
            File(context.filesDir,"chain-real-results.json").writeText(facts.toString(2))
            try {client.stop();waitFor("真实验收 VPN 收尾",30000) {client.snapshot.value.state==0}}
            finally {client.close();store.replace(original);store.close();engine.cancelTests();platform.close()}
        }
    }
    @Test fun existingBrokenSocksCredentialsAreRepairedAndPersistedOnce() {
        val f=fixture();val name="real-socks-migration.db"
        val old=JSONObject(f.socks.outbound).put("username",URI(f.socks.shareLink).userInfo).apply {remove("password")}
        val broken=f.socks.copy(outbound=old.toString())
        val legacy=f.data.copy(nodes=f.data.nodes.map {if(it.id==broken.id)broken else it},settings=emptyMap())
        context.deleteDatabase(name)
        try {
            ZaneStore(context,name).use {seed->seed.writableDatabase.execSQL("UPDATE state SET payload=?,revision=revision+1 WHERE id=1",arrayOf(legacy.toJson()))}
            var revision=0L
            ZaneStore(context,name).use {store->
                val repaired=store.snapshot();val node=repaired.nodes.first {it.id==broken.id}
                val actual=JSONObject(node.outbound);val expected=JSONObject(f.socks.outbound)
                assertTrue("旧节点认证字段未修复",actual.optString("username")==expected.optString("username") && actual.optString("password")==expected.optString("password"))
                assertTrue("旧节点其他记录被修改",node.copy(outbound=broken.outbound)==broken)
                val actualConnection=JSONObject(node.outbound).apply {remove("username");remove("password")}.toString()
                val oldConnection=JSONObject(broken.outbound).apply {remove("username");remove("password")}.toString()
                assertTrue("迁移修改了非认证连接字段",actualConnection==oldConnection)
                assertTrue("迁移修改了机场或派生 C",repaired.nodes.filter {it.id!=broken.id}==legacy.nodes.filter {it.id!=broken.id} && repaired.groups==legacy.groups)
                store.readableDatabase.rawQuery("SELECT payload,revision FROM state WHERE id=1",null).use {cursor->
                    assertTrue(cursor.moveToFirst());val persisted=AppData.fromJson(cursor.getString(0)).nodes.first {it.id==broken.id}
                    assertTrue("修复仅在内存未持久化",JSONObject(persisted.outbound).optString("username")==expected.optString("username") && JSONObject(persisted.outbound).optString("password")==expected.optString("password"))
                    revision=cursor.getLong(1)
                }
            }
            ZaneStore(context,name).use {store->store.readableDatabase.rawQuery("SELECT revision FROM state WHERE id=1",null).use {cursor->assertTrue(cursor.moveToFirst());assertEquals("第二次打开重复迁移",revision,cursor.getLong(0))}}
        } finally {context.deleteDatabase(name)}
    }
}
