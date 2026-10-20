package com.zane.zanebox

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.zane.zanebox.config.ConfigBuilder
import com.zane.zanebox.config.Purpose
import com.zane.zanebox.data.AppData
import com.zane.zanebox.data.Group
import com.zane.zanebox.data.Node
import com.zane.zanebox.runtime.ServiceClient
import com.zane.zanebox.core.NativePlatform
import com.zane.zanebox.core.SingBoxEngine
import com.zane.zanebox.subscription.SubscriptionParser
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder
import org.json.JSONObject
import java.io.File

class XhttpCompatibilityTest {
    @Test fun unifiedDelayChangesRealNativeRequestsAndMeasuredDelay() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val platform=NativePlatform(context,null) { _,_-> }
        platform.initialize()
        val engine=SingBoxEngine(platform)
        val results=JSONObject()
        try {
            for(enabled in listOf(false,true)) {
                val data=AppData(nodes=listOf(Node(1,1,"delayed SOCKS","""{"type":"socks","server":"10.0.2.2","server_port":19092}""")),groups=listOf(Group(1,"fixture")),settings=mapOf("unifiedDelay" to enabled.toString(),"tcpConcurrent" to "true","dnsRemote" to "local"))
                val latency=engine.test(ConfigBuilder.build(data,Purpose.TEST,1),"http://203.0.113.9:19093/measure",4000)
                results.put(enabled.toString(),latency)
                if(enabled)assertTrue("统一延迟仍包含350ms首次握手: $latency",latency in 1..250)
                else assertTrue("关闭后没有计入首次握手: $latency",latency>=350)
            }
            File(context.getExternalFilesDir(null),"unified-delay-native.json").writeText(results.toString())
        } finally { engine.cancelTests();platform.close() }
    }

    @Test fun importedXhttpOptionsReachTheRealNativeDecoder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val extra = """{"xPaddingBytes":0,"noGRPCHeader":true,"scMaxEachPostBytes":65536,"scMinPostsIntervalMs":"10-20","xmux":{"maxConcurrency":2,"hMaxRequestTimes":100}}"""
        val samples = listOf(
            "vless://11111111-1111-4111-8111-111111111111@127.0.0.1:443?security=tls&sni=example.test&alpn=h2&type=xhttp&mode=packet-up&host=example.test&path=%2Fxhttp&extra=${URLEncoder.encode(extra, "UTF-8")}",
            """proxies:
              - {name: fixture, type: vless, server: 127.0.0.1, port: 443, uuid: 11111111-1111-4111-8111-111111111111, tls: true, servername: example.test, alpn: [h2], network: xhttp, xhttp-opts: {host: example.test, path: /xhttp, mode: stream-up, extra: {noGRPCHeader: true, scMaxEachPostBytes: 65536, xmux: {maxConcurrency: 2}}}}
            """.trimIndent()
        )
        val client = ServiceClient(context)
        client.connect()
        try {
            samples.forEachIndexed { index, sample ->
                val parsed = SubscriptionParser.parse(sample).single()
                val data = AppData(nodes = listOf(Node(1, 1, "fixture", parsed.outbound)), groups = listOf(Group(1, "fixture")))
                val key = "xhttp-import-$index"
                client.validateConfig(key, ConfigBuilder.build(data, Purpose.TEST, 1))
                val deadline = SystemClock.elapsedRealtime() + 15000
                while (key !in client.validationResults.value && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
                assertTrue("原生校验未返回 $key", key in client.validationResults.value)
                assertEquals("原生拒绝导入结果 $key", "", client.validationResults.value[key])
            }
        } finally { client.close() }
    }
}
