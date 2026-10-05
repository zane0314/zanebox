package com.zane.zanebox

import com.zane.zanebox.subscription.SubscriptionParser
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ParserCompatTest {
    private fun out(text:String)=JSONObject(SubscriptionParser.parse(text).single().outbound)

    @Test fun badLineIsSkippedNotWholeBatch() {
        val report=SubscriptionParser.parseReport("trojan://pw@example.com:443#ok\nunsupported://x@example.com:443\nvless://broken")
        assertEquals(1,report.nodes.size);assertEquals("ok",report.nodes.single().name);assertEquals(2,report.skipped)
        val yaml=SubscriptionParser.parseReport("proxies:\n  - {name: good, type: trojan, server: example.com, port: 443, password: pw}\n  - {name: bad, type: mystery, server: example.com, port: 1}\n")
        assertEquals(listOf("good"),yaml.nodes.map { it.name });assertEquals(1,yaml.skipped)
        try { SubscriptionParser.parse("unsupported://x@example.com:443");fail("all invalid must fail") } catch(_:IllegalArgumentException) {}
    }

    @Test fun lenientShareLinkSyntax() {
        val spaced=SubscriptionParser.parse("trojan://p@ss w0rd@example.com:443?sni=a.example#名字 with space 100%").single()
        assertEquals("名字 with space 100%",spaced.name)
        val o=JSONObject(spaced.outbound);assertEquals("p@ss w0rd",o.getString("password"));assertEquals("example.com",o.getString("server"));assertEquals("a.example",o.getJSONObject("tls").getString("server_name"))
        val v6=out("trojan://pw@[2001:db8::1]:8443#v6");assertEquals("2001:db8::1",v6.getString("server"));assertEquals(8443,v6.getInt("server_port"))
        val ss=out("ss://YWVzLTI1Ni1nY206cGFzcy93b3JkKys@example.com:8388/?plugin=obfs-local%3Bobfs%3Dhttp#SS 节点")
        assertEquals("pass/word++",ss.getString("password"));assertEquals("obfs-local",ss.getString("plugin"))
    }

    @Test fun clashVmessDoesNotWriteShadowsocksMethod() {
        val o=out("proxies:\n  - {name: vm, type: vmess, server: example.com, port: 443, uuid: u, alterId: 0, cipher: auto, tls: true}\n")
        assertFalse(o.has("method"));assertEquals("auto",o.getString("security"))
        val ss=out("proxies:\n  - {name: s, type: ss, server: example.com, port: 8388, cipher: aes-128-gcm, password: pw}\n")
        assertEquals("aes-128-gcm",ss.getString("method"));assertFalse(ss.has("alter_id"))
    }

    @Test fun hysteriaV1ObfsUsesObfsParam() {
        val o=out("hysteria://example.com:443?auth=a&upmbps=10&downmbps=50&obfs=xplus&obfsParam=secret#hy")
        assertEquals("secret",o.getString("obfs"));assertEquals(10,o.getInt("up_mbps"))
    }

    @Test fun websocketEarlyDataAndH2Transport() {
        val ws=out("vless://u@example.com:443?security=tls&type=ws&host=cdn.example&path=%2Fws%3Fed%3D2048#ws").getJSONObject("transport")
        assertEquals("/ws",ws.getString("path"));assertEquals(2048,ws.getInt("max_early_data"));assertEquals("Sec-WebSocket-Protocol",ws.getString("early_data_header_name"))
        val h2=out("vless://u@example.com:443?security=tls&type=h2&host=a.example,b.example&path=%2Fh2#h2").getJSONObject("transport")
        assertEquals("http",h2.getString("type"));assertEquals("/h2",h2.getString("path"));assertEquals(2,h2.getJSONArray("host").length())
        val vmess=JSONObject().put("v","2").put("ps","vm").put("add","example.com").put("port","443").put("id","u").put("aid","0").put("net","h2").put("host","h.example").put("path","/p").put("tls","tls").put("sni","")
        val vm=out("vmess://"+Base64.getEncoder().encodeToString(vmess.toString().toByteArray()))
        assertEquals("http",vm.getJSONObject("transport").getString("type"));assertEquals("h.example",vm.getJSONObject("tls").getString("server_name"))
    }

    @Test fun realityWithoutFingerprintDefaultsToChrome() {
        val link=out("vless://u@example.com:443?security=reality&pbk=key&sid=aa#r").getJSONObject("tls")
        assertEquals("chrome",link.getJSONObject("utls").getString("fingerprint"));assertTrue(link.getJSONObject("utls").getBoolean("enabled"))
        val clash=out("proxies:\n  - {name: r, type: vless, server: example.com, port: 443, uuid: u, tls: true, reality-opts: {public-key: key, short-id: aa}}\n").getJSONObject("tls")
        assertEquals("chrome",clash.getJSONObject("utls").getString("fingerprint"))
    }

    @Test fun clashTransportsMapToSingBox() {
        val http=out("proxies:\n  - {name: h, type: vmess, server: example.com, port: 80, uuid: u, alterId: 0, cipher: auto, network: http, http-opts: {method: GET, path: [/a], headers: {Host: [h.example]}}}\n").getJSONObject("transport")
        assertEquals("http",http.getString("type"));assertEquals("/a",http.getString("path"));assertEquals("h.example",http.getJSONArray("host").getString(0));assertEquals("GET",http.getString("method"))
        val h2=out("proxies:\n  - {name: h2, type: vmess, server: example.com, port: 443, uuid: u, alterId: 0, cipher: auto, tls: true, network: h2, h2-opts: {host: [h.example], path: /h2}}\n").getJSONObject("transport")
        assertEquals("http",h2.getString("type"));assertEquals("/h2",h2.getString("path"))
        val ws=out("proxies:\n  - {name: w, type: vless, server: example.com, port: 443, uuid: u, tls: true, network: ws, ws-opts: {path: /w, headers: {Host: w.example}, max-early-data: 2048, early-data-header-name: Sec-WebSocket-Protocol}}\n").getJSONObject("transport")
        assertEquals("/w",ws.getString("path"));assertEquals("w.example",ws.getJSONObject("headers").getString("Host"));assertEquals(2048,ws.getInt("max_early_data"))
        val grpc=out("proxies:\n  - {name: g, type: vless, server: example.com, port: 443, uuid: u, tls: true, network: grpc, grpc-opts: {grpc-service-name: svc}}\n").getJSONObject("transport")
        assertEquals("svc",grpc.getString("service_name"))
    }

    @Test fun incomingLinkToleratesUnencodedCharacters() {
        val link=IncomingLink.parse("sn://subscription?url=https%3A%2F%2Fexample.com%2Fs%3Ftoken%3Da%7Cb&name=My Sub")
        assertEquals("https://example.com/s?token=a|b",link.subscriptionUrl);assertEquals("My Sub",link.name)
        assertEquals("trojan://pw@example.com:443#名字 空格",IncomingLink.parse("trojan://pw@example.com:443#名字 空格").text)
        try { IncomingLink.parse("sn://subscription?url=file%3A%2F%2F%2Fetc%2Fpasswd");fail("non http") } catch(_:IllegalArgumentException) {}
    }
}
