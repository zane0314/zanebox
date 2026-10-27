package com.zane.zanebox

import com.zane.zanebox.subscription.SubscriptionParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class XhttpImportTest {
    private fun outbound(text: String) = JSONObject(SubscriptionParser.parse(text).single().outbound)
    private fun uri(extra: String, query: String = "") = "vless://11111111-1111-4111-8111-111111111111@edge.example:443?security=tls&sni=sni.example&alpn=h2&type=xhttp&extra=${URLEncoder.encode(extra, "UTF-8")}$query#fixture"
    private fun download() = JSONObject("""{"address":"download.example","port":18443,"network":"xhttp","xhttpSettings":{"host":"download-host.example","path":"/down","mode":"packet-up"}}""")
    private fun split(settings: JSONObject = download()) = JSONObject().put("downloadSettings", settings).toString()

    @Test fun separatePlaintextDownloadDoesNotInheritUploadTlsOrTransport() {
        val o = outbound(uri(split(), "&host=upload.example&path=%2Fup&mode=packet-up"))
        val tr = o.getJSONObject("transport"); val down = tr.getJSONObject("download")
        assertEquals("edge.example", o.getString("server")); assertEquals(443, o.getInt("server_port"))
        assertEquals("sni.example", o.getJSONObject("tls").getString("server_name"))
        assertEquals("h2", o.getJSONObject("tls").getJSONArray("alpn").getString(0))
        assertEquals("upload.example", tr.getString("host")); assertEquals("/up", tr.getString("path"))
        assertEquals("download.example", down.getString("server")); assertEquals(18443, down.getInt("server_port"))
        assertEquals("download-host.example", down.getString("host")); assertEquals("/down", down.getString("path")); assertEquals("packet-up", down.getString("mode"))
        assertFalse(down.has("tls")); assertFalse(down.has("type")); assertFalse(tr.has("downloadSettings"))
    }

    @Test fun downloadTlsAndRealityUseOnlyTheirOwnSettings() {
        listOf("tls", "reality").forEach { security ->
            val settings = JSONObject().put("serverName", "down-sni.example").put("fingerprint", "firefox")
            if (security == "tls") settings.put("allowInsecure", true).put("alpn", org.json.JSONArray(listOf("http/1.1")))
            else settings.put("publicKey", "fixture-key").put("shortId", "aabb")
            val o = outbound(uri(split(download().put("security", security).put("${security}Settings", settings))))
            val tls = o.getJSONObject("transport").getJSONObject("download").getJSONObject("tls")
            assertTrue(tls.getBoolean("enabled")); assertEquals("down-sni.example", tls.getString("server_name"))
            assertEquals("firefox", tls.getJSONObject("utls").getString("fingerprint"))
            if (security == "tls") { assertTrue(tls.getBoolean("insecure")); assertEquals("http/1.1", tls.getJSONArray("alpn").getString(0)) }
            else { assertFalse(tls.has("alpn")); assertEquals("fixture-key", tls.getJSONObject("reality").getString("public_key")); assertEquals("aabb", tls.getJSONObject("reality").getString("short_id")) }
            assertEquals("sni.example", o.getJSONObject("tls").getString("server_name"))
        }
    }

    @Test fun nestedDownloadExtraUsesTheExistingXhttpAliasesAndDefaults() {
        val settings = download()
        settings.put("xhttpSettings", JSONObject("""{"path":"/down","extra":{"xPaddingBytes":0,"scMinPostsIntervalMs":30,"headers":{"X-Test":"download"},"xmux":{"maxConcurrency":2}}}"""))
        val tr = outbound(uri(split(settings), "&host=upload.example&mode=stream-up")).getJSONObject("transport")
        val down = tr.getJSONObject("download")
        assertEquals("", down.getString("host")); assertEquals("auto", down.getString("mode"))
        assertEquals("0", down.getString("x_padding_bytes")); assertEquals("30", down.getString("sc_min_posts_interval_ms"))
        assertEquals("2", down.getJSONObject("xmux").getString("max_concurrency"))
        assertEquals("download", down.getJSONObject("headers").getString("X-Test")); assertFalse(tr.has("xmux"))
    }

    @Test fun clashAndBase64SubscriptionsImportAllFiveSeparateDownloads() {
        val links = (1..5).joinToString("\n") { index -> uri(split()).replace("edge.example", "[2001:db8::$index]") }
        val report = SubscriptionParser.parseReport(java.util.Base64.getEncoder().encodeToString(links.toByteArray()))
        assertEquals(5, report.nodes.size); assertEquals(0, report.skipped)
        report.nodes.forEach { assertEquals(18443, JSONObject(it.outbound).getJSONObject("transport").getJSONObject("download").getInt("server_port")) }
        val down = outbound("""proxies:
          - {name: split, type: vless, server: edge.example, port: 443, uuid: 11111111-1111-4111-8111-111111111111, tls: true, network: xhttp, xhttp-opts: {path: /up, mode: packet-up, extra: ${split()}}}
        """.trimIndent()).getJSONObject("transport").getJSONObject("download")
        assertEquals("download.example", down.getString("server")); assertFalse(down.has("tls"))
    }

    @Test fun unsupportedDownloadFieldsAndMalformedSettingsAreReportedWithoutLosingGoodNodes() {
        val invalid = listOf(
            download().put("network", "ws") to "network",
            download().put("port", 0) to "port",
            download().put("port", 443.5) to "port",
            download().put("address", "") to "address",
            download().put("security", "xtls") to "security",
            download().put("sockopt", JSONObject().put("mark", 1)) to "sockopt",
            download().put("xhttpSettings", JSONObject().put("extra", JSONObject().put("xmux", "bad"))) to "xmux",
            download().put("xhttpSettings", JSONObject().put("headers", 1)) to "headers",
            download().put("xhttpSettings", JSONObject().put("extra", JSONObject().put("path", JSONObject().put("bad", 1)))) to "path",
            download().put("xhttpSettings", JSONObject().put("extra", JSONObject().put("host", 123))) to "host",
            download().put("xhttpSettings", JSONObject().put("noSSEHeader", "true")) to "no_sse_header",
            download().put("xhttpSettings", JSONObject().put("scMaxEachPostBytes", "1-2-3")) to "sc_max_each_post_bytes",
            download().put("xhttpSettings", JSONObject().put("xmux", JSONObject().put("maxConcurrency", JSONObject().put("from", "1")))) to "max_concurrency",
            download().put("xhttpSettings", JSONObject().put("unknownOption", true)) to "unknownOption",
            download().put("xhttpSettings", JSONObject().put("extra", JSONObject().put("downloadSettings", download()))) to "downloadSettings",
            download().put("security", "tls").put("tlsSettings", JSONObject().put("certificates", org.json.JSONArray())) to "certificates",
            download().put("tlsSettings", JSONObject().put("allowInsecure", true)) to "tlsSettings"
        )
        invalid.forEach { (settings, field) ->
            val report = SubscriptionParser.parseReport("trojan://fixture@example.test:443#good\n" + uri(split(settings)))
            assertEquals(field, 1, report.nodes.size); assertEquals(field, 1, report.skipped)
            assertTrue("$field: ${report.firstError}", report.firstError.orEmpty().contains(field))
        }
        val report = SubscriptionParser.parseReport("trojan://fixture@example.test:443\n" + uri(split(), "&mode=stream-one"))
        assertEquals(1, report.skipped); assertTrue(report.firstError.orEmpty().contains("stream-one"))
    }

    @Test fun nativeJsonEditingAndSubscriptionRefreshKeepTheIndependentDownload() = kotlinx.coroutines.runBlocking {
        val parsed = SubscriptionParser.parse(uri(split())).single()
        val original = JSONObject(parsed.outbound).getJSONObject("transport").getJSONObject("download")
        val edited = com.zane.zanebox.ui.updateNodeField(parsed.outbound, "transport.path", "/edited-up", "text")
        assertTrue(original.similar(JSONObject(edited).getJSONObject("transport").getJSONObject("download")))
        assertTrue(original.similar(JSONObject(outbound(parsed.outbound).toString()).getJSONObject("transport").getJSONObject("download")))
        val group = com.zane.zanebox.data.Group(1, "split", "https://subscription.example/list")
        val data = com.zane.zanebox.data.AppData(nodes = listOf(com.zane.zanebox.data.Node(10, 1, "old", edited, parsed.shareLink)), groups = listOf(group))
        val prepared = com.zane.zanebox.subscription.SubscriptionUpdater.prepare(listOf(parsed), com.zane.zanebox.subscription.SubscriptionOptions(), true) { error("unexpected DNS lookup") }
        val started = com.zane.zanebox.subscription.SubscriptionUpdater.begin(data, group, "split-refresh", 1000)
        val updated = com.zane.zanebox.subscription.SubscriptionUpdater.apply(started, started.groups.single(), prepared, "", 2000) { 99 }
        assertEquals(10L, updated.nodes.single().id)
        val restored = com.zane.zanebox.data.AppData.fromJson(updated.toJson())
        assertTrue(original.similar(JSONObject(restored.nodes.single().outbound).getJSONObject("transport").getJSONObject("download")))
    }

    @Test fun shareExtraMapsSupportedXrayFieldsAndKeepsExplicitQueryPriority() {
        val extra = """{"host":"extra.example","path":"/extra","mode":"stream-up","xPaddingBytes":0,"noGRPCHeader":true,"noSSEHeader":true,"scMaxEachPostBytes":65536,"scMinPostsIntervalMs":"10-20","scMaxBufferedPosts":12,"scStreamUpServerSecs":30,"headers":{"X-Test":"fixture"},"xmux":{"maxConcurrency":2,"maxConnections":0,"cMaxReuseTimes":"4-8","hMaxRequestTimes":100,"hMaxReusableSecs":600,"hKeepAlivePeriod":15}}"""
        val o = outbound(uri(extra, "&host=query.example&path=%2Fquery&mode=packet-up"))
        val tr = o.getJSONObject("transport")
        assertEquals("query.example", tr.getString("host")); assertEquals("/query", tr.getString("path")); assertEquals("packet-up", tr.getString("mode"))
        assertEquals("0", tr.getString("x_padding_bytes")); assertTrue(tr.getBoolean("no_grpc_header")); assertTrue(tr.getBoolean("no_sse_header"))
        assertEquals("65536", tr.getString("sc_max_each_post_bytes")); assertEquals("10-20", tr.getString("sc_min_posts_interval_ms"))
        assertEquals(12, tr.getInt("sc_max_buffered_posts")); assertEquals("30", tr.getString("sc_stream_up_server_secs"))
        assertFalse(tr.has("xPaddingBytes")); assertFalse(tr.has("noGRPCHeader"))
        val xmux = tr.getJSONObject("xmux")
        assertEquals("2", xmux.getString("max_concurrency")); assertEquals("0", xmux.getString("max_connections")); assertEquals("4-8", xmux.getString("c_max_reuse_times"))
        assertEquals("100", xmux.getString("h_max_request_times")); assertEquals("600", xmux.getString("h_max_reusable_secs")); assertEquals(15, xmux.getInt("h_keep_alive_period"))
        assertEquals("fixture", tr.getJSONObject("headers").getString("X-Test"))
        assertEquals("sni.example", o.getJSONObject("tls").getString("server_name")); assertEquals("h2", o.getJSONObject("tls").getJSONArray("alpn").getString(0))
        val fallback = outbound(uri(extra)).getJSONObject("transport")
        assertEquals("extra.example", fallback.getString("host")); assertEquals("/extra", fallback.getString("path")); assertEquals("stream-up", fallback.getString("mode"))
    }

    @Test fun clashXhttpOptionsPreserveHostModeHeadersAndExtra() {
        val tr = outbound("""
            proxies:
              - name: fixture
                type: vless
                server: edge.example
                port: 443
                uuid: 11111111-1111-4111-8111-111111111111
                tls: true
                servername: sni.example
                alpn: [h2]
                network: xhttp
                xhttp-opts:
                  host: host.example
                  path: /xhttp?test=1
                  mode: packet-up
                  headers: {Content-Type: application/custom, X-Test: fixture}
                  extra:
                    noGRPCHeader: true
                    scMaxEachPostBytes: 65536
                    xmux: {maxConcurrency: 2}
        """.trimIndent()).getJSONObject("transport")
        assertEquals("xhttp", tr.getString("type")); assertEquals("host.example", tr.getString("host")); assertEquals("packet-up", tr.getString("mode")); assertEquals("/xhttp?test=1", tr.getString("path"))
        assertEquals("application/custom", tr.getJSONObject("headers").getString("Content-Type")); assertTrue(tr.getBoolean("no_grpc_header"))
        assertEquals("65536", tr.getString("sc_max_each_post_bytes")); assertEquals("2", tr.getJSONObject("xmux").getString("max_concurrency"))
    }

    @Test fun snakeCaseExtraRangesAndSplitHttpAliasRemainUsable() {
        val tr = outbound(uri("""{"x_padding_bytes":"10-20","no_grpc_header":true,"xmux":{"max_concurrency":{"from":1,"to":2}}}""").replace("type=xhttp", "type=splithttp")).getJSONObject("transport")
        assertEquals("xhttp", tr.getString("type")); assertEquals("auto", tr.getString("mode")); assertEquals("/", tr.getString("path"))
        assertEquals("10-20", tr.getString("x_padding_bytes")); assertTrue(tr.getBoolean("no_grpc_header")); assertEquals(2, tr.getJSONObject("xmux").getJSONObject("max_concurrency").getInt("to"))
    }
}
