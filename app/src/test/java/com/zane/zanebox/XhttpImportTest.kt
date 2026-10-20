package com.zane.zanebox

import com.zane.zanebox.subscription.SubscriptionParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class XhttpImportTest {
    private fun outbound(text: String) = JSONObject(SubscriptionParser.parse(text).single().outbound)
    private fun uri(extra: String, query: String = "") = "vless://11111111-1111-4111-8111-111111111111@edge.example:443?security=tls&sni=sni.example&alpn=h2&type=xhttp&extra=${URLEncoder.encode(extra, "UTF-8")}$query#fixture"

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
