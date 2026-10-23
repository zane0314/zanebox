package com.zane.zanebox

import com.zane.zanebox.subscription.SubscriptionParser
import com.zane.zanebox.ui.updateNodeField
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic fixtures only; no real Ninja endpoint, password or subscription is used here. */
class NinjaImportTest {
    private fun node(yaml: String) = SubscriptionParser.parseReport(yaml.trimIndent()).nodes.single()
    private fun outbound(yaml: String) = JSONObject(node(yaml).outbound)
    private fun rejected(yaml: String, context: String = ""): String {
        val error = runCatching { SubscriptionParser.parseReport(yaml.trimIndent()) }.exceptionOrNull()
        assertTrue("expected rejection $context", error is IllegalArgumentException)
        return error?.message.orEmpty()
    }

    private fun clash(name: String, network: String = "tcp", ws: String = "") = buildString {
        append("""
            proxies:
              - name: $name
                type: ninja
                server: $network.example.test
                port: 20001
                method: aes-128-gcm
                password: fixture-outer
                node_password: node.example.test
                pass: true
                pass-opts:
                  method: aes-128-gcm
                  password: fixture-inner
                  padding_min: 16
                  padding_mode: min_random
                  padding_random_max: 32
                tls: true
                servername: sni.example.test
                skip-cert-verify: true
                client-fingerprint: chrome
                network: $network
                udp: true
                udp-over-tcp: true
                udp-over-tcp-version: 2
        """.trimIndent())
        if (ws.isNotBlank()) append("\n").append(ws.trimIndent().prependIndent("    "))
    }

    @Test
    fun clashNinjaTcpMapsPassTlsAndUdpOverTcp() {
        val o = outbound(clash("ninja-tcp"))
        assertEquals("ninja", o.getString("type"))
        assertEquals("tcp.example.test", o.getString("server"))
        assertEquals(20001, o.getInt("server_port"))
        assertEquals("aes-128-gcm", o.getString("method"))
        assertEquals("fixture-outer", o.getString("password"))
        assertEquals("node.example.test", o.getString("node_password"))
        assertTrue(o.getBoolean("pass"))
        val pass = o.getJSONObject("pass_options")
        assertEquals("aes-128-gcm", pass.getString("method"))
        assertEquals("fixture-inner", pass.getString("password"))
        assertEquals(16, pass.getInt("padding_min"))
        assertEquals("min_random", pass.getString("padding_mode"))
        assertEquals(32, pass.getInt("padding_random_max"))
        val tls = o.getJSONObject("tls")
        assertTrue(tls.getBoolean("enabled"))
        assertEquals("sni.example.test", tls.getString("server_name"))
        assertTrue(tls.getBoolean("insecure"))
        assertTrue(tls.getJSONObject("utls").getBoolean("enabled"))
        assertEquals("chrome", tls.getJSONObject("utls").getString("fingerprint"))
        val uot = o.getJSONObject("udp_over_tcp")
        assertTrue(uot.getBoolean("enabled"))
        assertEquals(2, uot.getInt("version"))
        assertFalse(o.has("transport"))
        assertFalse(o.has("network"))
        assertFalse(o.has("udp"))
        assertFalse(o.has("udp-over-tcp"))
        assertFalse(o.has("pass-opts"))
    }

    @Test
    fun clashNinjaWsMapsTransportWithoutCopyingNetwork() {
        val o = outbound(clash("ninja-ws", "ws", """
            ws-opts:
              headers:
                Host: ws.example.test
              path: /m3u8
        """))
        val tr = o.getJSONObject("transport")
        assertEquals("ws", tr.getString("type"))
        assertEquals("/m3u8", tr.getString("path"))
        assertEquals("ws.example.test", tr.getJSONObject("headers").getString("Host"))
        assertFalse(o.has("network"))
        assertFalse(tr.has("network"))
    }

    @Test
    fun canonicalNinjaJsonPassesThrough() {
        val json = """
            {"outbounds":[{"type":"ninja","tag":"ninja-json","server":"json.example.test","server_port":443,
            "method":"aes-128-gcm","password":"fixture-outer","node_password":"json-node.example.test","pass":true,
            "pass_options":{"method":"aes-128-gcm","password":"fixture-inner","padding_min":16,"padding_mode":"min_random","padding_random_max":32},
            "tls":{"enabled":true,"server_name":"sni.example.test","utls":{"enabled":true,"fingerprint":"chrome"}},
            "transport":{"type":"ws","path":"/json","headers":{"Host":"json.example.test"}},
            "udp_over_tcp":{"enabled":true,"version":2},"future_option":{"kept":true}}]}
        """.trimIndent()
        val parsed = SubscriptionParser.parseReport(json).nodes.single()
        assertEquals("ninja-json", parsed.name)
        assertTrue(JSONObject(parsed.metadata).has("sourceTag"))
        val o = JSONObject(parsed.outbound)
        assertEquals("ninja", o.getString("type"))
        assertEquals("fixture-outer", o.getString("password"))
        assertEquals("fixture-inner", o.getJSONObject("pass_options").getString("password"))
        assertEquals("json.example.test", o.getJSONObject("transport").getJSONObject("headers").getString("Host"))
        assertTrue(o.getJSONObject("future_option").getBoolean("kept"))
        assertFalse(o.has("tag"))
    }

    @Test
    fun malformedNinjaNodeIsSkippedWithoutLeakingCredentials() {
        val report = SubscriptionParser.parseReport("""
            proxies:
              - name: broken
                type: ninja
                port: 20002
                method: aes-128-gcm
                password: secret-leak-marker
                node_password: node.example.test
              - name: usable
                type: ninja
                server: usable.example.test
                port: 20003
                method: aes-128-gcm
                password: fixture-outer
                node_password: node.example.test
                pass: false
                tls: true
        """.trimIndent())
        assertEquals(1, report.skipped)
        assertEquals(1, report.nodes.size)
        assertEquals("usable", report.nodes.single().name)
        assertNotNull(report.firstError)
        assertFalse(report.firstError.orEmpty().contains("secret-leak-marker"))
        assertFalse(report.firstError.orEmpty().contains("fixture-outer"))
        val o = JSONObject(report.nodes.single().outbound)
        assertFalse(o.getBoolean("pass"))
        assertFalse(o.has("pass_options"))
        assertFalse(o.has("udp_over_tcp"))
    }

    @Test
    fun editorUpdatesNinjaSecretsPassAndWebSocketAndPreservesOtherFields() {
        var text = JSONObject("""
            {"type":"ninja","server":"tcp.example.test","server_port":20001,"method":"aes-128-gcm",
            "password":"fixture-outer","node_password":"node.example.test","pass":true,
            "pass_options":{"method":"aes-128-gcm","password":"fixture-inner","padding_min":16,"padding_mode":"min_random","padding_random_max":32},
            "tls":{"enabled":true,"server_name":"sni.example.test"},
            "udp_over_tcp":{"enabled":true,"version":2}}
        """.trimIndent()).toString()
        text = updateNodeField(text, "password", "fixture-outer-2", "password")
        text = updateNodeField(text, "node_password", "node2.example.test", "password")
        text = updateNodeField(text, "pass_options.password", "fixture-inner-2", "password")
        text = updateNodeField(text, "pass_options.padding_min", "24", "number")
        text = updateNodeField(text, "pass", "false", "bool")
        var o = JSONObject(text)
        assertEquals("fixture-outer-2", o.getString("password"))
        assertEquals("node2.example.test", o.getString("node_password"))
        assertEquals("fixture-inner-2", o.getJSONObject("pass_options").getString("password"))
        assertEquals(24, o.getJSONObject("pass_options").getInt("padding_min"))
        assertEquals(32, o.getJSONObject("pass_options").getInt("padding_random_max"))
        assertFalse(o.getBoolean("pass"))
        assertEquals("sni.example.test", o.getJSONObject("tls").getString("server_name"))
        assertTrue(o.getJSONObject("udp_over_tcp").getBoolean("enabled"))

        text = updateNodeField(text, "transport.type", "ws", "text")
        text = updateNodeField(text, "transport.path", "/m3u8", "text")
        text = updateNodeField(text, "transport.headers.Host", "ws.example.test", "text")
        o = JSONObject(text).getJSONObject("transport")
        assertEquals("ws", o.getString("type"))
        assertEquals("/m3u8", o.getString("path"))
        assertEquals("ws.example.test", o.getJSONObject("headers").getString("Host"))

        text = updateNodeField(text, "transport.type", "", "text")
        assertFalse(JSONObject(text).has("transport"))
    }

    @Test
    fun clashNinjaUdpFalseOmitsUdpOverTcpAndVersionDefaultsOnlyWhenEnabled() {
        val disabled = outbound(clash("ninja-udp-off").replace("udp: true", "udp: false"))
        assertFalse(disabled.has("udp_over_tcp"))
        assertFalse(disabled.has("network"))
        val uotDisabled = outbound(clash("ninja-uot-off").replace("udp-over-tcp: true", "udp-over-tcp: false"))
        assertFalse(uotDisabled.has("udp_over_tcp"))
        val defaulted = outbound(clash("ninja-version-default").replace("udp-over-tcp-version: 2\n", ""))
        assertEquals(2, defaulted.getJSONObject("udp_over_tcp").getInt("version"))
    }

    @Test
    fun clashNinjaRejectsUnsupportedNetworkTlsAndFlags() {
        val cases = listOf(
            "network: tcp" to "network: grpc",
            "tls: true" to "tls: false",
            "    tls: true\n" to "",
            "pass: true" to "pass: \"true\"",
            "pass: true" to "pass: 1",
            "udp-over-tcp: true" to "udp-over-tcp: \"true\"",
            "udp: true" to "udp: \"false\"",
            "udp-over-tcp-version: 2" to "udp-over-tcp-version: 1",
            "udp-over-tcp-version: 2" to "udp-over-tcp-version: 3",
            "udp-over-tcp-version: 2" to "udp-over-tcp-version: 2.5",
            "udp-over-tcp-version: 2" to "udp-over-tcp-version: \"2\""
        )
        for ((from, to) in cases) {
            val message = rejected(clash("ninja-invalid").replace(from, to), "$from -> $to")
            assertTrue("expected Ninja validation for $from -> $to: $message", message.contains("Ninja"))
            assertFalse(message.contains("fixture-outer") || message.contains("fixture-inner"))
        }
    }

    @Test
    fun clashNinjaValidatesCredentialsPassOptionsAndPadding() {
        val cases = listOf(
            "    method: aes-128-gcm" to "    method: \"\"",
            "    password: fixture-outer" to "    password: \"\"",
            "    node_password: node.example.test" to "    node_password: \"\"",
            "pass-opts:" to "pass-opts-missing:",
            "      method: aes-128-gcm" to "      method: \"\"",
            "      password: fixture-inner" to "      password: \"\"",
            "padding_mode: min_random" to "padding_mode: max_random",
            "padding_min: 16" to "padding_min: 70000",
            "padding_min: 16" to "padding_min: -1",
            "padding_min: 16" to "padding_min: 16.5",
            "padding_min: 16" to "padding_min: \"16\"",
            "padding_random_max: 32" to "padding_random_max: 65536"
        )
        for ((from, to) in cases) {
            val message = rejected(clash("ninja-invalid").replace(from, to), "$from -> $to")
            assertTrue("expected Ninja validation for $from -> $to: $message", message.contains("Ninja"))
            assertFalse(message.contains("fixture-outer") || message.contains("fixture-inner"))
        }
        val sum = rejected(clash("ninja-sum").replace("padding_min: 16", "padding_min: 40000").replace("padding_random_max: 32", "padding_random_max: 40000"))
        assertTrue(sum.contains("Ninja"))
        val boundary = outbound(clash("ninja-boundary").replace("padding_min: 16", "padding_min: 32767").replace("padding_random_max: 32", "padding_random_max: 32768"))
        assertEquals(32767, boundary.getJSONObject("pass_options").getInt("padding_min"))
        assertEquals(32768, boundary.getJSONObject("pass_options").getInt("padding_random_max"))
    }
}
