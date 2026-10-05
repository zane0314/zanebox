package com.zane.zanebox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.runtime.ServiceClient
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import android.os.SystemClock
import java.io.File
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class NativeSchemaTest {
    @Test fun migratedConfigurationsUseRealNativeDecoder() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val client=ServiceClient(ins.targetContext)
        client.connect()
        val files=ins.context.assets.list("native-configs").orEmpty().filter { it.endsWith(".json") && it!="synthetic-appdata.json" }
        assertTrue("缺少原序列化迁移配置夹具",files.size>=20)
        try {
            files.forEach { name->client.validateConfig(name,ins.context.assets.open("native-configs/$name").bufferedReader().use { it.readText() }) }
            val advanced=com.zane.zanebox.data.AppData(nodes=listOf(com.zane.zanebox.data.Node(1,1,"TLS","""{"type":"trojan","server":"example.com","server_port":443,"password":"fixture","tls":{"enabled":true}}""")),groups=listOf(com.zane.zanebox.data.Group(1,"fixture")),merges=listOf(com.zane.zanebox.data.MergeGroup(2,"auto",nodeIds=listOf(1),mode="urltest")),settings=mapOf("concurrentDial" to "true","resolveDestination" to "true","enableDnsRouting" to "false","enableTLSFragment" to "true","urlTestInterval" to "12m","urlTestTolerance" to "25","smart.ai.target" to "direct","smartUrl.ai" to "https://example.test/AI.srs","smart.netflix.target" to "direct","smartUrl.netflix" to "https://example.test/Netflix.json","smartRules.netflix" to """{"version":3,"rules":[{"domain":["example.org"]}]}"""))
            client.validateConfig("advanced-ui-preferences",com.zane.zanebox.config.ConfigBuilder.build(advanced))
            val expected=files.size+1
            val until=SystemClock.elapsedRealtime()+60000
            while(client.validationResults.value.size<expected && SystemClock.elapsedRealtime()<until)Thread.sleep(100)
            val result=client.validationResults.value
            File(ins.targetContext.getExternalFilesDir(null),"native-schema-result.json").writeText(JSONObject(result).toString(2))
            assertEquals("原生校验未全部返回",expected,result.size)
            assertEquals("原生校验失败",emptyMap<String,String>(),result.filterValues { it.isNotEmpty() })
            client.validateConfig("malformed","{\"outbounds\":[{\"type\":\"does-not-exist\"}]}")
            val invalidUntil=SystemClock.elapsedRealtime()+10000
            while(!client.validationResults.value.containsKey("malformed") && SystemClock.elapsedRealtime()<invalidUntil)Thread.sleep(100)
            assertTrue("无效协议没有被原生拒绝",client.validationResults.value["malformed"].orEmpty().isNotBlank())
        } finally { client.close() }
    }
    @Test fun assetUpdatesRefreshStateAndInvalidInputPreservesTheInstalledDatabase() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val client=ServiceClient(context);client.connect()
        try {
            client.stop()
            val until=SystemClock.elapsedRealtime()+30000
            while((!File(context.filesDir,"core-assets/yacd/index.html").exists() || client.snapshot.value.state in 1..3) && SystemClock.elapsedRealtime()<until)Thread.sleep(100)
            val file=File(context.filesDir,"core-assets/geoip.db");assertTrue(file.isFile)
            val previous=file.readBytes()
            val stage=File(context.noBackupFilesDir,"asset-${java.util.UUID.randomUUID()}-geoip.db")
            stage.writeBytes(ByteArray(100)+byteArrayOf(0xab.toByte(),0xcd.toByte(),0xef.toByte())+"MaxMind.com".toByteArray()+ByteArray(50))
            client.installAsset(stage.name)
            val deadline=SystemClock.elapsedRealtime()+30000
            while(stage.exists() && SystemClock.elapsedRealtime()<deadline)Thread.sleep(100)
            assertFalse("资源验证未返回",stage.exists())
            assertArrayEquals("失败更新损坏原有资源",previous,file.readBytes())
            val revision=client.assetRevision.value
            val validStage=File(context.noBackupFilesDir,"asset-${java.util.UUID.randomUUID()}-geoip.db")
            validStage.writeBytes(previous);client.installAsset(validStage.name)
            val validDeadline=SystemClock.elapsedRealtime()+30000
            while((validStage.exists() || client.assetRevision.value<=revision) && SystemClock.elapsedRealtime()<validDeadline)Thread.sleep(100)
            assertFalse("有效资源安装未返回",validStage.exists())
            assertTrue("成功安装未通知页面刷新",client.assetRevision.value>revision)
            assertArrayEquals(previous,file.readBytes())
            File(context.getExternalFilesDir(null),"native-asset-result.json").writeText(JSONObject().put("invalidPreservesOriginal",true).put("validInstallRevision",client.assetRevision.value).toString(2))
        } finally { client.close() }
    }
}
