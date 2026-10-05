package com.zane.zanebox.subscription

import org.json.JSONArray
import org.json.JSONObject
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.util.Base64

data class ParsedNode(val name: String, val outbound: String, val shareLink: String = "", val metadata: String = "{}")
data class ParseReport(val nodes: List<ParsedNode>, val skipped: Int, val firstError: String?)

object SubscriptionParser {
    private const val LIMIT = 8 * 1024 * 1024
    private class Skips { var count = 0; var first: String? = null
        fun <T> attempt(block: () -> T): T? = try { block() } catch (e: Exception) { count++; if (first == null) first = e.message?.takeIf { !it.contains("://") }?.take(128) ?: e.javaClass.simpleName; null }
    }
    fun parse(text: String): List<ParsedNode> = parseReport(text).nodes
    /** One malformed entry must not discard the rest of a subscription; the caller reports how many were skipped. */
    fun parseReport(text: String): ParseReport {
        val skips = Skips()
        val nodes = parseInternal(text.trim(), 0, skips)
        require(nodes.isNotEmpty()) { skips.first?.let { "订阅没有可用节点（跳过 ${skips.count} 条：$it）" } ?: "订阅没有节点" }
        require(nodes.size <= 10000) { "节点数量超过限制" }
        return ParseReport(nodes, skips.count, skips.first)
    }
    private fun parseInternal(text: String, depth: Int, skips: Skips): List<ParsedNode> {
        require(text.isNotBlank()) { "没有可导入的内容" }
        require(text.length <= LIMIT && depth <= 2) { "订阅超过解析限制" }
        if (text.startsWith("{") || text.startsWith("[")) {
            val array = if (text.startsWith("[")) JSONArray(text) else JSONObject(text).let { root ->
                val outbounds=root.optJSONArray("outbounds"); val endpoints=root.optJSONArray("endpoints")
                if(outbounds!=null || endpoints!=null) JSONArray().apply { listOfNotNull(outbounds,endpoints).forEach { a -> for(i in 0 until a.length()) put(a.get(i)) } }
                else root.optJSONArray("proxies") ?: root.optJSONArray("servers") ?: JSONArray().put(root)
            }
            return (0 until array.length()).mapNotNull { index -> skips.attempt {
                val obj = array.getJSONObject(index)
                if(!obj.has("type") && obj.has("method") && obj.has("server"))obj.put("type","shadowsocks")
                if (obj.optString("type") in listOf("direct", "block", "dns", "selector", "urltest")) null
                else if (obj.has("server-port")) clash(jsonMap(obj))
                else canonical(obj, obj.optString("display_name", obj.optString("name", obj.optString("remarks",obj.optString("tag", "节点 ${index + 1}")))))
            } }
        }
        if (text.contains("proxies:") && !text.lineSequence().first().contains("://")) {
            val options = LoaderOptions().apply { codePointLimit = LIMIT; maxAliasesForCollections = 30; nestingDepthLimit = 30 }
            val root = Yaml(SafeConstructor(options)).load<Any>(text) as? Map<*, *> ?: error("Clash YAML 根格式无效")
            val proxies = root["proxies"] as? List<*> ?: error("Clash YAML 缺少 proxies")
            return proxies.mapNotNull { item -> skips.attempt { clash(item as? Map<*, *> ?: error("Clash 节点格式无效")) } }
        }
        if (!text.contains("://")) return parseInternal(decode64(text), depth + 1, skips)
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }.mapNotNull { line -> skips.attempt { link(line) } }
    }
    internal fun applyHysteriaPorts(out:JSONObject,text:String) {
        require(text.length<=65536) { "跳跃端口列表过长" }
        val ranges=text.split(',').map { raw -> val parts=raw.trim().replace('-',':').split(':');require(parts.size in 1..2 && parts.all { it.matches(Regex("[0-9]{1,5}")) && it.toIntOrNull() in 1..65535 }) { "跳跃端口无效" };val start=parts[0].toInt();val end=parts.getOrElse(1) { parts[0] }.toInt();require(start<=end) { "跳跃端口范围倒序" };start to end }
        require(ranges.sumOf { it.second-it.first+1 }<=65535) { "跳跃端口总数超限" }
        if(ranges.size==1 && ranges[0].first==ranges[0].second) { out.put("server_port",ranges[0].first);out.remove("server_ports") }
        else { out.remove("server_port");out.put("server_ports",JSONArray(ranges.map { "${it.first}:${it.second}" })) }
    }
    private fun canonical(obj: JSONObject, name: String, link: String = ""): ParsedNode {
        require(obj.optString("type").isNotBlank()) { "节点缺少协议" }
        val metadata=JSONObject(); if(obj.has("tag")) metadata.put("sourceTag", obj.getString("tag"))
        obj.remove("name"); obj.remove("display_name"); obj.remove("tag")
        if(obj.optString("type")=="shadowsocks") { obj.remove("id");obj.remove("remarks") }
        require(obj.has("server") || obj.optString("type") in listOf("wireguard", "tailscale")) { "节点缺少服务器" }
        return ParsedNode(name.ifBlank { obj.optString("server", "节点") }, obj.toString(), link, metadata.toString())
    }
    private fun decoded(s: String) = ShareUri.decode(s)
    private fun decode64(s: String): String = String(Base64.getDecoder().decode(s.filterNot { it.isWhitespace() }.replace('-', '+').replace('_', '/')), Charsets.UTF_8)
    private fun utls(tls: JSONObject, fingerprint: String?) {
        // sing-box refuses Reality without uTLS; v2rayN-style links frequently omit fp.
        val value = fingerprint?.takeIf { it.isNotBlank() } ?: if (tls.has("reality")) "chrome" else return
        tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", value))
    }
    private fun link(raw: String): ParsedNode {
        val scheme = raw.substringBefore("://").lowercase()
        if (scheme == "vmess") {
            val v = JSONObject(decode64(raw.substringAfter("://").substringBefore('#')))
            val server = v.getString("add")
            val o = JSONObject().put("type", "vmess").put("server", server).put("server_port", v.get("port").toString().trim().toInt()).put("uuid", v.getString("id")).put("security", v.optString("scy").ifBlank { "auto" }).put("alter_id", v.optInt("aid", 0))
            if (v.optString("tls") == "tls") {
                val tls = JSONObject().put("enabled", true).put("server_name", v.optString("sni").ifBlank { v.optString("host").substringBefore(',') }.ifBlank { server })
                if (v.optString("allowInsecure") in listOf("1", "true")) tls.put("insecure", true)
                v.optString("alpn").takeIf { it.isNotBlank() }?.let { tls.put("alpn", JSONArray(it.split(','))) }
                utls(tls, v.optString("fp")); o.put("tls", tls)
            }
            transport(o, mapOf("type" to v.optString("net", "tcp"), "headerType" to v.optString("type"), "host" to v.optString("host"), "path" to v.optString("path")))
            return canonical(o, v.optString("ps", "VMess"), raw)
        }
        if (scheme == "ss") return shadowsocks(raw)
        val uri = ShareUri.parse(raw); val q = uri.query; val type = when (scheme) { "hy2", "hysteria2" -> "hysteria2"; "socks", "socks4", "socks4a", "socks5" -> "socks"; "https" -> "http"; else -> scheme }
        require(type in listOf("vless", "trojan", "hysteria", "hysteria2", "tuic", "socks", "http", "ssh", "anytls", "shadowtls", "juicity", "snell")) { "暂不支持分享协议 $scheme；可导入完整 sing-box JSON" }
        val o = JSONObject().put("type", type).put("server", uri.host).put("server_port", if (uri.port > 0) uri.port else when(type) { "socks"->1080;"ssh"->22;"http"->if(scheme=="https")443 else 80;else->443 })
        val user = decoded(uri.rawUserInfo.orEmpty()); val parts = user.split(':', limit = 2)
        when (type) {
            "vless" -> { require(user.isNotBlank()) { "VLESS 缺少 UUID" }; o.put("uuid", user); q["flow"]?.takeIf { it.isNotBlank() }?.let { o.put("flow", it) } }
            "shadowtls" -> { o.put("password", user).put("version", (q["version"] ?: "3").toInt()) }
            "snell" -> { o.put("psk", user).put("version", (q["version"] ?: "4").toInt()) }
            "trojan", "anytls" -> { require(user.isNotBlank()) { "缺少密码" }; o.put("password", user) }
            "hysteria2" -> o.put("password", user)
            "hysteria" -> {
                o.put("auth_str", q["auth"] ?: user); o.put("up_mbps", (q["upmbps"] ?: "100").toInt()); o.put("down_mbps", (q["downmbps"] ?: "100").toInt()); q["protocol"]?.let { require(it=="udp") { "当前内核不支持 Hysteria $it 伪装" } }
                // Hysteria v1 URIs name the algorithm in obfs (xplus) and carry the password in obfsParam.
                val obfs = q["obfs"].orEmpty(); val password = q["obfsParam"] ?: q["obfs-password"]
                when { !password.isNullOrBlank() -> o.put("obfs", password); obfs.isBlank() || obfs == "none" -> Unit; obfs == "xplus" -> error("Hysteria 混淆缺少密码"); else -> o.put("obfs", obfs) }
            }
            "tuic", "juicity" -> { require(parts.size == 2) { "TUIC 缺少密码" }; o.put("uuid", parts[0]).put("password", parts[1]); if(type == "tuic") { o.put("congestion_control", q["congestion_control"] ?: "cubic"); q["udp_relay_mode"]?.let { o.put("udp_relay_mode", it) } } else q["pinned_certchain_sha256"]?.let { o.put("pin_cert_sha256", it) } }
            else -> { if (parts[0].isNotEmpty()) o.put(if(type=="ssh")"user" else "username", parts[0]); if (parts.size > 1) o.put("password", parts[1]); if (type == "socks") o.put("version",when(scheme){"socks4"->"4";"socks4a"->"4a";else->"5"}) }
        }
        val security = q["security"] ?: if (type in listOf("trojan", "anytls", "hysteria", "hysteria2", "tuic", "shadowtls", "juicity") || scheme == "https") "tls" else "none"
        if (security in listOf("tls", "reality", "xtls")) {
            val tls = JSONObject().put("enabled", true).put("server_name", q["sni"]?.takeIf { it.isNotBlank() } ?: q["peer"]?.takeIf { it.isNotBlank() } ?: uri.host).put("insecure", q["allowInsecure"] in listOf("1", "true") || q["insecure"] in listOf("1", "true"))
            q["alpn"]?.takeIf { it.isNotBlank() }?.let { tls.put("alpn", JSONArray(it.split(','))) }
            if (security == "reality") tls.put("reality", JSONObject().put("enabled", true).put("public_key", q["pbk"] ?: error("Reality 缺少公钥")).put("short_id", q["sid"] ?: ""))
            utls(tls, q["fp"])
            o.put("tls", tls)
        }
        if (type == "hysteria2" && q["obfs"] == "salamander") o.put("obfs", JSONObject().put("type", "salamander").put("password", q["obfs-password"] ?: error("混淆缺少密码")))
        if(type in listOf("hysteria","hysteria2")) {
            (q["mport"] ?: q["ports"] ?: q["server_ports"])?.takeIf { it.isNotBlank() }?.let { applyHysteriaPorts(o,it); q["hop_interval"]?.let { hop -> o.put("hop_interval",if(hop.endsWith("s")) hop else "${hop}s") } }
        }
        transport(o, q)
        return canonical(o, decoded(uri.rawFragment.orEmpty()).ifBlank { "$type ${uri.host}" }, raw)
    }
    private fun splitHosts(value: String?) = value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    private fun httpTransport(path: String?, hosts: List<String>, method: String? = null) = JSONObject().put("type", "http").apply {
        path?.takeIf { it.isNotBlank() }?.let { put("path", it) }
        if (hosts.isNotEmpty()) put("host", JSONArray(hosts))
        method?.takeIf { it.isNotBlank() }?.let { put("method", it) }
    }
    private fun webSocket(rawPath: String?, host: String?, earlyData: Int?, earlyHeader: String?): JSONObject {
        var path = rawPath?.ifBlank { "/" } ?: "/"; var ed = earlyData
        // Xray encodes early data as "?ed=N" inside the path; sing-box needs it as separate fields.
        if (path.contains('?')) {
            val params = path.substringAfter('?').split('&').filter { it.isNotEmpty() }
            params.firstOrNull { it.startsWith("ed=") }?.substringAfter('=')?.toIntOrNull()?.let { value ->
                ed = value; val kept = params.filterNot { it.startsWith("ed=") }
                path = path.substringBefore('?') + if (kept.isEmpty()) "" else "?" + kept.joinToString("&")
            }
        }
        val tr = JSONObject().put("type", "ws").put("path", path.ifBlank { "/" })
        host?.takeIf { it.isNotEmpty() }?.let { tr.put("headers", JSONObject().put("Host", it)) }
        if (ed != null && ed!! > 0) tr.put("max_early_data", ed).put("early_data_header_name", earlyHeader?.takeIf { it.isNotBlank() } ?: "Sec-WebSocket-Protocol")
        return tr
    }
    private fun transport(o: JSONObject, q: Map<String, String>) {
        when (val t = q["type"] ?: "tcp") {
            "tcp", "none", "" -> if (q["headerType"] == "http") o.put("transport", httpTransport(q["path"], splitHosts(q["host"])))
            "ws" -> o.put("transport", webSocket(q["path"], q["host"], q["ed"]?.toIntOrNull(), q["eh"]))
            "httpupgrade" -> { val tr = JSONObject().put("type", t).put("path", q["path"] ?: "/"); q["host"]?.takeIf { it.isNotEmpty() }?.let { tr.put("host", it) }; o.put("transport", tr) }
            "http", "h2" -> o.put("transport", httpTransport(q["path"] ?: "/", splitHosts(q["host"])))
            "grpc" -> o.put("transport", JSONObject().put("type", "grpc").put("service_name", q["serviceName"] ?: q["path"] ?: ""))
            "xhttp", "splithttp" -> { val extra = q["extra"]?.let { JSONObject(it) } ?: JSONObject(); o.put("transport", extra.put("type", "xhttp").put("path", q["path"] ?: "/").put("host", q["host"] ?: "").put("mode", q["mode"] ?: "auto")) }
            "quic" -> o.put("transport", JSONObject().put("type", "quic"))
            else -> error("暂不支持传输 $t；可导入完整 sing-box JSON")
        }
    }
    private fun shadowsocks(raw: String): ParsedNode {
        val payload = raw.substringAfter("://").substringBefore('#'); val fragment = raw.substringAfter('#', "")
        val expanded = if (!payload.substringBefore('?').contains('@')) decode64(payload.substringBefore('?')) + if (payload.contains('?')) "?${payload.substringAfter('?')}" else "" else payload
        val uri = ShareUri.parse("ss://$expanded"); val credentials = uri.rawUserInfo ?: error("SS 缺少认证")
        val user = if (credentials.contains(':')) decoded(credentials) else decode64(decoded(credentials))
        val pieces = user.split(':', limit = 2); require(pieces.size == 2) { "SS 认证无效" }
        require(uri.port > 0) { "SS 端口无效" }
        val o = JSONObject().put("type", "shadowsocks").put("server", uri.host).put("server_port", uri.port).put("method", pieces[0]).put("password", pieces[1])
        uri.query["plugin"]?.let { val p = it.split(';', limit = 2); o.put("plugin", p[0]); if (p.size > 1) o.put("plugin_opts", p[1]) }
        return canonical(o, decoded(fragment).ifBlank { "SS ${uri.host}" }, raw)
    }
    private fun jsonMap(o: JSONObject): Map<String, Any?> = o.keys().asSequence().associateWith { key ->
        when (val v = o.get(key)) { is JSONObject -> jsonMap(v); is JSONArray -> (0 until v.length()).map { v.get(it) }; JSONObject.NULL -> null; else -> v }
    }
    private fun firstString(value: Any?): String? = when (value) { null -> null; is List<*> -> value.firstOrNull()?.toString(); else -> value.toString() }
    private fun stringList(value: Any?): List<String> = when (value) { null -> emptyList(); is List<*> -> value.mapNotNull { it?.toString() }; else -> splitHosts(value.toString()) }
    private fun clashTransport(o: JSONObject, m: Map<*, *>) {
        val network = m["network"]?.toString() ?: "tcp"
        val opts = m["$network-opts"] as? Map<*, *> ?: emptyMap<Any, Any>()
        val headers = opts["headers"] as? Map<*, *> ?: emptyMap<Any, Any>()
        when (network) {
            "tcp", "" -> Unit
            "ws" -> {
                val host = firstString(headers["Host"])
                if (opts["v2ray-http-upgrade"] == true) transport(o, mapOf("type" to "httpupgrade", "path" to (opts["path"]?.toString() ?: "/"), "host" to host.orEmpty()))
                else o.put("transport", webSocket(opts["path"]?.toString(), host, opts["max-early-data"]?.toString()?.toIntOrNull(), opts["early-data-header-name"]?.toString()))
            }
            "http" -> o.put("transport", httpTransport(firstString(opts["path"]) ?: "/", stringList(headers["Host"]), opts["method"]?.toString()))
            "h2" -> o.put("transport", httpTransport(firstString(opts["path"]) ?: "/", stringList(opts["host"])))
            "grpc" -> o.put("transport", JSONObject().put("type", "grpc").put("service_name", opts["grpc-service-name"]?.toString() ?: ""))
            else -> transport(o, mapOf("type" to network, "path" to (firstString(opts["path"]) ?: "/"), "host" to (firstString(headers["Host"]) ?: "")))
        }
    }
    private fun clash(m: Map<*, *>): ParsedNode {
        fun s(key: String, fallback: String = "") = m[key]?.toString() ?: fallback
        val type = when (s("type")) { "ss" -> "shadowsocks"; "socks5" -> "socks"; else -> s("type") }
        require(type in listOf("shadowsocks", "vmess", "vless", "trojan", "socks", "http", "hysteria", "hysteria2", "tuic", "anytls", "wireguard", "ssh", "snell", "juicity")) { "Clash 协议 $type 暂不兼容；请使用 sing-box JSON" }
        val o = JSONObject().put("type", type).put("server", s("server")).put("server_port", s("port", s("server-port")).toInt())
        val keys = mapOf("password" to "password", "uuid" to "uuid", "flow" to "flow", "username" to if(type=="ssh")"user" else "username", "udp-relay-mode" to "udp_relay_mode", "congestion-controller" to "congestion_control")
        keys.forEach { (from, to) -> m[from]?.let { o.put(to, it) } }
        // Clash reuses "cipher" for both the Shadowsocks method and the VMess security; never cross them.
        if (type == "shadowsocks") m["cipher"]?.let { o.put("method", it) }
        if (type == "vmess") { o.put("security", s("cipher", "auto").ifBlank { "auto" }); o.put("alter_id", s("alterId", "0").toIntOrNull() ?: 0) }
        if (m["tls"] == true || type in listOf("trojan", "hysteria", "hysteria2", "tuic", "anytls")) {
            val tls = JSONObject().put("enabled", true).put("server_name", s("servername", s("sni", s("server")))).put("insecure", m["skip-cert-verify"] == true)
            (m["alpn"] as? List<*>)?.let { tls.put("alpn", JSONArray(it)) }
            (m["reality-opts"] as? Map<*, *>)?.let { tls.put("reality", JSONObject().put("enabled", true).put("public_key", it["public-key"]).put("short_id", it["short-id"] ?: "")) }
            utls(tls, m["client-fingerprint"]?.toString()); o.put("tls", tls)
        }
        if (type == "hysteria") {
            o.put("auth_str", s("auth-str", s("auth"))).put("up_mbps", s("up", "100").filter { it.isDigit() }.ifBlank { "100" }.toInt()).put("down_mbps", s("down", "100").filter { it.isDigit() }.ifBlank { "100" }.toInt())
            m["obfs"]?.let { o.put("obfs", it) }
        }
        if (type == "hysteria2" && m["obfs"] != null) o.put("obfs", JSONObject().put("type", m["obfs"]).put("password", s("obfs-password")))
        if (type == "snell") {
            o.put("psk", s("psk")).put("version", s("version", "4").toInt())
            (m["obfs-opts"] as? Map<*, *>)?.let { o.put("obfs_mode", it["mode"]).put("obfs_host", it["host"]) }
        }
        if (type == "wireguard") {
            val addresses = mutableListOf<String>(); m["ip"]?.let { addresses.add("$it/32") }; m["ipv6"]?.let { addresses.add("$it/128") }
            require(addresses.isNotEmpty()) { "WireGuard 缺少本地地址" }
            o.put("address", JSONArray(addresses)).put("private_key", s("private-key"))
            val peer=JSONObject().put("address", s("server")).put("port", s("port").toInt()).put("public_key", s("public-key")).put("allowed_ips", JSONArray(listOf("0.0.0.0/0", "::/0")))
            m["pre-shared-key"]?.let { peer.put("pre_shared_key", it) }; (m["reserved"] as? List<*>)?.let { peer.put("reserved", JSONArray(it)) }
            o.put("peers", JSONArray().put(peer)); o.remove("server"); o.remove("server_port"); m["mtu"]?.let { o.put("mtu", it) }
        }
        clashTransport(o, m)
        if (type == "shadowsocks") { m["plugin"]?.let { o.put("plugin", it) }; (m["plugin-opts"] as? Map<*, *>)?.let { options -> o.put("plugin_opts", options.entries.joinToString(";") { "${it.key}=${it.value}" }) } }
        return canonical(o, s("name", s("server")))
    }
}
