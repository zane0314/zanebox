package com.zane.zanebox.backup

import com.zane.zanebox.config.ConfigBuilder
import com.zane.zanebox.subscription.SubscriptionParser
import org.json.JSONArray
import org.json.JSONObject

/** Field order and version gates follow the original AnyBox Bean.serialize/deserialize methods. */
internal object LegacyNodeProtocols {
    val supported=setOf(15,17,18,20,22,23,24)
    fun read(k:KryoReader,type:Int,meta:JSONObject):Pair<String,JSONObject> {
        val version=k.int();val host=k.string();var displayHost=host;val port=k.int();require(port in 1..65535)
        val o=JSONObject().put("server",host).put("server_port",port)
        fun tls(sni:String,alpn:String="",ca:String="",insecure:Boolean=false):JSONObject {
            val t=JSONObject().put("enabled",true).put("server_name",sni).put("insecure",insecure)
            if(alpn.isNotEmpty()) t.put("alpn",JSONArray(alpn.split(',').map { it.trim() }))
            if(ca.isNotEmpty()) t.put("certificate",ca)
            o.put("tls",t);return t
        }
        when(type) {
            22 -> {
                require(version in 0..1) { "未知AnyTLS bean版本" };o.put("type","anytls").put("password",k.string())
                val sni=k.string();val alpn=k.string();val ca=k.string();val fingerprint=k.string();val insecure=k.bool();val ech=k.string()
                val t=tls(sni,alpn,ca,insecure)
                if(fingerprint.isNotEmpty()) t.put("utls",JSONObject().put("enabled",true).put("fingerprint",fingerprint))
                if(ech.isNotEmpty()) t.put("ech",JSONObject().put("enabled",true).put("config",JSONArray(ech.lines().filter { it.isNotBlank() })))
                if(version>=1) { val pub=k.string();val short=k.string();if(pub.isNotEmpty()) t.put("reality",JSONObject().put("enabled",true).put("public_key",pub).put("short_id",short)) }
            }
            15 -> {
                require(version in 0..7) { "未知Hysteria bean版本" }
                val protocolVersion=if(version>=7) k.int() else 1;require(protocolVersion in 1..2)
                val authType=k.int();val auth=k.string();val transport=if(version>=3) k.int() else 0
                // Original app also rejects FakeTCP/WeChat in native sing-box mode.
                require(transport==0) { "Hysteria非UDP传输需要外部实现" }
                val obfs=k.string();val sni=k.string();val alpn=if(version>=2) k.string() else ""
                val up=k.int();val down=k.int();val insecure=k.bool();var ca="";var stream=0;var connection=0;var disableMtu=false
                if(version>=1) { ca=k.string();stream=k.int();connection=k.int();if(version!=4) disableMtu=k.bool() }
                val hop=if(version>=5) k.int() else 10;var ports=if(version>=6) k.string() else ""
                if(version<6 && host.contains(':')) { val suffix=host.substringAfterLast(':');if(suffix.contains('-') || suffix.contains(',')) { ports=suffix;displayHost=host.substringBeforeLast(':');o.put("server",displayHost);meta.put("legacyServerAddress",host) } }
                meta.put("authPayloadType",authType).put("streamReceiveWindow",stream).put("connectionReceiveWindow",connection).put("disableMtuDiscovery",disableMtu)
                o.put("type",if(protocolVersion==2) "hysteria2" else "hysteria")
                if(up>0) o.put("up_mbps",up);if(down>0) o.put("down_mbps",down)
                if(protocolVersion==2) { o.put("password",auth);if(obfs.isNotEmpty()) o.put("obfs",JSONObject().put("type","salamander").put("password",obfs)) }
                else { if(auth.isNotEmpty()) o.put(when(authType) { 1->"auth_str";2->"auth";else->error("未知Hysteria认证类型") },auth);if(obfs.isNotEmpty()) o.put("obfs",obfs);if(stream>0) o.put("recv_window",stream.toLong());if(connection>0) o.put("recv_window_conn",connection.toLong());o.put("disable_mtu_discovery",disableMtu) }
                if(ports.isNotEmpty()) {
                    SubscriptionParser.applyHysteriaPorts(o,ports)
                    if(o.has("server_ports")) o.put("hop_interval","${hop.coerceAtLeast(1)}s")
                }
                tls(sni,alpn,ca,insecure)
            }
            20 -> {
                require(version in 0..2) { "未知TUIC bean版本" }
                val token=k.string();val ca=k.string();val relay=k.string();val congestion=k.string();val alpn=k.string();val disableSni=k.bool();val zero=k.bool();val mtu=k.int();val sni=k.string()
                val fast=if(version>=1) k.bool() else false;val insecure=if(version>=1) k.bool() else false
                val custom=if(version>=2) k.string() else "";val protocol=if(version>=2) k.int() else 4;val uuid=if(version>=2) k.string() else ""
                require(protocol==5) { "TUIC4需要原外部插件，当前内核不支持" }
                o.put("type","tuic").put("uuid",uuid).put("password",token).put("congestion_control",congestion).put("udp_relay_mode",relay).put("zero_rtt_handshake",zero)
                tls(sni,alpn,ca,insecure).put("disable_sni",disableSni)
                meta.put("tuicMtu",mtu).put("fastConnect",fast)
                if(custom.isNotEmpty()) { val fields=backupJson(custom);meta.put("customOutbound",fields);ConfigBuilder.mergeJson(o,fields) }
            }
            23 -> {
                require(version in 0..1) { "未知Juicity bean版本" };o.put("type","juicity").put("uuid",k.string()).put("password",k.string())
                val sni=k.string();val pin=k.string();val insecure=k.bool();tls(sni,insecure=insecure);if(pin.isNotEmpty()) o.put("pin_cert_sha256",pin)
            }
            24 -> {
                require(version in 0..3) { "未知Snell bean版本" };o.put("type","snell").put("psk",k.string());val protocol=k.int();require(protocol in setOf(4,6)) { "当前内核只支持Snell4/6" };o.put("version",protocol)
                val mode=k.string();val host=k.string();val reuse=k.bool();o.put("reuse",reuse)
                if(version>=2) { val network=k.string();if(network.isNotEmpty()) o.put("network",network) }
                if(version>=3) { val user=k.string();val shaping=k.string();if(user.isNotEmpty()) o.put("userkey",user);if(protocol==6 && shaping.isNotEmpty()) o.put("mode",shaping) }
                if(protocol==4 && mode.isNotEmpty()) { o.put("obfs_mode",mode);if(host.isNotEmpty()) o.put("obfs_host",host) }
            }
            17 -> {
                require(version==0) { "未知SSH bean版本" };o.put("type","ssh").put("user",k.string())
                when(k.int()) { 0->Unit;1->o.put("password",k.string());2->{o.put("private_key",k.string());o.put("private_key_passphrase",k.string())};else->error("未知SSH认证类型") }
                val public=k.string();if(public.isNotEmpty()) o.put("host_key",JSONArray(public.lines().filter { it.isNotBlank() }))
            }
            18 -> {
                require(version in 0..2) { "未知WireGuard bean版本" };o.put("type","wireguard")
                o.put("local_address",JSONArray(k.string().split(',','\n').map { it.trim() }.filter { it.isNotBlank() })).put("private_key",k.string()).put("peer_public_key",k.string())
                val pre=k.string();if(pre.isNotEmpty()) o.put("pre_shared_key",pre);o.put("mtu",k.int());val reserved=k.string()
                if(reserved.isNotEmpty()) o.put("reserved",reserved)
            }
            else->error("未识别协议")
        }
        require(k.int()==0) { "未知bean尾部版本" };val name=k.string();val custom=k.string();val config=k.string();k.end()
        if(config.isNotEmpty()) meta.put("customConfig",backupJson(config))
        if(custom.isNotEmpty()) { val fields=backupJson(custom);meta.put("customOutbound",fields);ConfigBuilder.mergeJson(o,fields) }
        return name.ifEmpty { "$displayHost:$port" } to o
    }
}
