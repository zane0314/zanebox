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
            val until=SystemClock.elapsedRealtime()+60000
            while(client.validationResults.value.size<files.size && SystemClock.elapsedRealtime()<until)Thread.sleep(100)
            val result=client.validationResults.value
            File(ins.targetContext.getExternalFilesDir(null),"native-schema-result.json").writeText(JSONObject(result).toString(2))
            assertEquals("原生校验未全部返回",files.size,result.size)
            assertEquals("原生校验失败",emptyMap<String,String>(),result.filterValues { it.isNotEmpty() })
            client.validateConfig("malformed","{\"outbounds\":[{\"type\":\"does-not-exist\"}]}")
            val invalidUntil=SystemClock.elapsedRealtime()+10000
            while(!client.validationResults.value.containsKey("malformed") && SystemClock.elapsedRealtime()<invalidUntil)Thread.sleep(100)
            assertTrue("无效协议没有被原生拒绝",client.validationResults.value["malformed"].orEmpty().isNotBlank())
        } finally { client.close() }
    }
}
