package com.zane.zanebox.backup

import com.zane.zanebox.data.*
import com.zane.zanebox.config.ConfigBuilder
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** Original AnyBox Parcel/Kryo formats, read without loading classes from the old application. */
internal object LegacyBackup {
    fun read(root:JSONObject,preferences:JSONObject):AppData {
        require(root.getInt("version")==1) { "不支持的旧备份版本" }
        val settings=linkedMapOf<String,String>()
        // Preserve the complete original data and typed preferences for future migration/export.
        settings["legacy.logical"]=root.toString();settings["legacy.preferences"]=preferences.toString()
        preferences.keys().forEach { file ->
            require(file.matches(Regex("[A-Za-z0-9_.-]+")) && !file.contains("..")) { "旧偏好文件名无效" }
            val fields=preferences.getJSONObject(file);require(fields.length()<=100000)
            fields.keys().forEach { key ->
                val item=fields.getJSONObject(key);val value=item.get("value")
                when(item.getString("type")) {
                    "string" -> require(value is String)
                    "boolean" -> require(value is Boolean)
                    "int" -> require(value is Number && value.toLong() in Int.MIN_VALUE..Int.MAX_VALUE && value.toDouble()==value.toLong().toDouble())
                    "long" -> require(value is Number && value.toDouble().isFinite() && value.toDouble()==value.toLong().toDouble())
                    "float" -> require(value is Number && value.toFloat().isFinite())
                    "set" -> { val a=item.getJSONArray("value");require(a.length()<=100000);repeat(a.length()) { require(a.get(it) is String) } }
                    else -> error("未知SharedPreferences值类型")
                }
                settings["legacy.preference.$file.$key"]=item.toString()
            }
        }
        fun <T> records(key:String,parse:(String)->T):List<T> {
            val array=root.getJSONArray(key);require(array.length()<=100000) { "旧备份记录过多" }
            return (0 until array.length()).map { index -> try { parse(array.getString(index)) } catch(e:Exception) { throw IllegalArgumentException("旧备份${key}第${index+1}项无法完整迁移：${e.message ?: "数据无效"}") } }
        }
        val groups=records("groups",::group)
        val nodes=records("profiles",::node)
        val nodeById=nodes.associateBy { it.id }
        val verifiedChains=mutableSetOf<Long>()
        fun chain(id:Long,path:Set<Long>) {
            if(id in verifiedChains) return
            require(path.size<64) { "旧链式代理嵌套超过64层" };require(id !in path) { "旧链式代理包含循环" }
            val node=nodeById[id] ?: error("旧链式代理引用不存在节点")
            val o=JSONObject(node.outbound);if(o.optString("type")=="chain") { val ids=o.getJSONArray("node_ids");repeat(ids.length()) { chain(ids.getLong(it),path+id) } };verifiedChains.add(id)
        }
        nodes.forEach { chain(it.id,emptySet()) }
        val rules=records("rules",::rule)
        records("settings") { encoded ->
            val parcel=ParcelReader(decoded(encoded));val key=parcel.string();val type=parcel.int();val bytes=parcel.bytes();parcel.end()
            settings["legacy.setting.$key"]=JSONObject().put("type",type).put("base64",Base64.getEncoder().encodeToString(bytes)).toString()
            val buffer=ByteBuffer.wrap(bytes)
            val value=when(type) { 1 -> {require(bytes.size==1);(bytes[0].toInt()!=0).toString()};2 -> {require(bytes.size==4);buffer.float.toString()};3 -> {require(bytes.size==4);buffer.int.toString()};4 -> {require(bytes.size==8);buffer.long.toString()};5 -> bytes.toString(Charsets.UTF_8);6 -> stringSet(bytes).toString();else -> null }
            if(value!=null) settings[key]=value
        }
        val aliases=mapOf(
            "selectedProxy" to "selectedNodeId","selectedGroup" to "selectedGroupId",
            "isAutoConnect" to "autoStart","profileTrafficStatistics" to "statsEnabled",
            "connectionTestURL" to "testUrl","enableClashAPI" to "clashApi",
            "networkChangeResetConnections" to "networkReset",
            "remoteDns" to "dnsRemote","directDns" to "dnsDirect","enableIPv6" to "ipv6",
            "allowAccess" to "allowLan","enableFakeDns" to "fakeDns",
            "proxyApps" to "perAppEnabled","individual" to "perAppPackages",
            "webdavUsername" to "webdavUser","webdavServer" to "webdavUrl"
        )
        aliases.forEach { (old,new) -> settings[old]?.let { settings[new]=it } }
        listOf("autoStart","statsEnabled","clashApi","networkReset","fakeDns","ipv6","allowLan","perAppEnabled").forEach { key ->
            settings[key]?.let { value->if(value in listOf("0","1"))settings[key]=(value=="1").toString() }
        }
        fun dnsStrategy(value:String) = when(value) { "auto" -> ""; "prefer_ipv6","prefer_ipv4","ipv4_only","ipv6_only" -> value; else -> value }
        listOf("domain_strategy_for_remote" to "dnsStrategyRemote","domain_strategy_for_direct" to "dnsStrategyDirect","domain_strategy_for_server" to "dnsStrategyServer").forEach { (old,new) -> settings[old]?.let { settings[new]=dnsStrategy(it) } }
        settings["appTheme"]?.toIntOrNull()?.let { mode->settings["appTheme"]=if(mode==0)"" else com.zane.zanebox.data.themePresetColors.getOrNull(mode-1) ?:settings.getValue("appTheme") }
        settings["nightTheme"]?.toIntOrNull()?.let { mode -> settings["theme"]=when(mode) { 0,3 -> "system";1 -> "dark";2 -> "light";else -> error("未知旧夜间主题") } }
        settings["logLevel"]?.toIntOrNull()?.let { level -> settings["logLevel"]=when(level) { 0->"panic";1->"warn";2->"info";3->"debug";4->"trace";else->error("未知旧日志等级") } }
        settings["tunImplementation"]?.toIntOrNull()?.let { stack -> settings["tunStack"]=when(stack) { 0->"gvisor";1->"system";2->"mixed";else->error("未知旧TUN实现") } }
        settings["trafficSniffing"]?.toIntOrNull()?.let { settings["sniff"]=(it>0).toString() }
        settings["ipv6Mode"]?.toIntOrNull()?.let { mode -> settings["ipv6"]=(mode!=0).toString();settings["dnsStrategy"]=when(mode) {0->"ipv4_only";1->"prefer_ipv4";2->"prefer_ipv6";3->"ipv6_only";else->error("未知旧IPv6模式") } }
        settings["globalMode"]?.toBooleanStrictOrNull()?.let { settings["routeMode"]=if(it) "global" else "rule" }
        settings["bypass"]?.toBooleanStrictOrNull()?.let { settings["perAppMode"]=if(it) "exclude" else "include" }
        if(settings.containsKey("webdavServer") && settings.containsKey("webdavPath")) settings["webdavUrl"]=settings.getValue("webdavServer").trimEnd('/')+"/"+settings.getValue("webdavPath").trim('/')+"/"

        return AppData(nodes,groups,rules,settings=settings).validate()
    }
    private fun stringSet(bytes:ByteArray):org.json.JSONArray {
        fun parse(legacy:Boolean):List<String> {
            val b=ByteBuffer.wrap(bytes);val result=mutableListOf<String>()
            while(b.hasRemaining()) { require(result.size<100000 && b.remaining()>=4);val count=b.int;require(count>=0)
                val raw:ByteArray
                if(legacy) { val position=b.position();val rest=bytes.copyOfRange(position,bytes.size);val text=Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(rest)).toString();require(count<=text.length);raw=text.substring(0,count).toByteArray(Charsets.UTF_8);require(raw.size<=b.remaining() && rest.copyOfRange(0,raw.size).contentEquals(raw));b.position(position+raw.size) }
                else { require(count<=b.remaining());raw=ByteArray(count);b.get(raw) }
                result.add(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString())
            }
            return result.distinct()
        }
        val values=try { parse(false) } catch(_:Exception) { parse(true) }
        return org.json.JSONArray(values)
    }
    private fun decoded(value:String):ByteArray { require(value.length<=MAX_BACKUP);return Base64.getDecoder().decode(value.replace(Regex("[\\t\\r\\n ]"),"")).also { require(it.size<=MAX_BACKUP) } }
    private fun kryo(value:String):KryoReader { val parcel=ParcelReader(decoded(value));val bytes=parcel.bytes();parcel.end();return KryoReader(bytes) }
    private fun group(value:String):Group {
        val k=kryo(value);val version=k.int();require(version in 0..1) { "未知分组版本" }
        val id=k.long();val userOrder=k.long();val ungrouped=k.bool();val name=k.string();val type=k.int();val options=JSONObject().put("ungrouped",ungrouped).put("userOrder",userOrder).put("type",type)
        var url="";var updated=0L;var info=""
        if(type==1) {
            val sv=k.int();require(sv in 0..3) { "未知订阅版本" }
            options.put("subscriptionType",k.int());url=k.string()
            listOf("forceResolve","deduplication","updateWhenConnectedOnly").forEach { options.put(it,k.bool()) }
            options.put("customUserAgent",k.string());options.put("autoUpdate",k.bool());options.put("autoUpdateDelay",k.int());val epoch=k.int();options.put("subscriptionLastUpdated",epoch);updated=epoch.toLong()*1000;info=k.string()
            if(sv>=2) { options.put("filterMode",k.int());options.put("filterRegex",k.string()) }
            if(sv>=3) options.put("serverDnsResolver",k.string())
        } else require(type==0) { "未知分组类型$type" }
        val order=k.int();options.put("nodeSortOrder",order);var front=0L;var landing=0L
        if(version>=1) { options.put("isSelector",k.bool());front=k.long().coerceAtLeast(0);landing=k.long().coerceAtLeast(0);options.put("profileRevision",k.long()) }
        k.end();require(userOrder in Int.MIN_VALUE..Int.MAX_VALUE);return Group(id,name,url,order=userOrder.toInt(),updatedAt=updated,userInfo=info,frontProxy=front,landingProxy=landing,options=options.toString())
    }
    private fun node(value:String):Node {
        val k=kryo(value);require(k.int()==0) { "未知节点版本" }
        val id=k.long();val group=k.long();val type=k.int();val order=k.long();val tx=k.long();val rx=k.long();val status=k.int();val ping=k.int()
        val meta=JSONObject().put("uuid",k.string()).put("error",k.string()).put("legacyType",type).put("legacyParcel",value)
        val bean=KryoReader(k.bytes(k.varInt()));meta.put("dirty",k.bool());k.end()
        if(type==8 || type==998) {
            val version=bean.int();val outbound:JSONObject
            if(type==8) {
                require(version in 0..1) { "未知链式bean版本" };if(version==0) { bean.string();bean.int() }
                val count=bean.int();require(count in 1..10000);val ids=(0 until count).map { bean.long().also { require(it>0) } }
                outbound=JSONObject().put("type","chain").put("node_ids",org.json.JSONArray(ids))
            } else {
                require(version==0) { "未知自定义配置bean版本" };meta.put("legacyServer",bean.string()).put("legacyPort",bean.int());val configType=bean.int();val config=backupJson(bean.string())
                outbound=when(configType) { 0 -> JSONObject().put("type","custom").put("config",config);1 -> config.also { require(it.optString("type").isNotBlank()) { "自定义outbound缺少type" } };else -> error("未知自定义配置类型") }
            }
            require(bean.int()==0) { "未知bean尾部版本" };val name=bean.string();val custom=bean.string();val config=bean.string();bean.end();if(config.isNotEmpty()) meta.put("customConfig",backupJson(config))
            if(custom.isNotEmpty()) { val fields=backupJson(custom);meta.put("customOutbound",fields);ConfigBuilder.mergeJson(outbound,fields) }
            require(order in Int.MIN_VALUE..Int.MAX_VALUE);return Node(id,group,name,outbound.toString(),ping=ping,tx=tx,rx=rx,order=order.toInt(),status=status,metadata=meta.toString())
        }
        if(type in LegacyNodeProtocols.supported) { val (name,outbound)=LegacyNodeProtocols.read(bean,type,meta);require(order in Int.MIN_VALUE..Int.MAX_VALUE);return Node(id,group,name,outbound.toString(),ping=ping,tx=tx,rx=rx,order=order.toInt(),status=status,metadata=meta.toString()) }
        require(type in setOf(0,1,2,4,6,19)) { "协议类型${type}的Kryo迁移尚未支持，已停止恢复以保护原数据" }
        if(type in setOf(1,4,6,19)) return standardNode(bean,type,id,group,order,tx,rx,status,ping,meta)
        val version=bean.int();require(version in 0..(if(type==0) 2 else 5)) { "未知协议bean版本" }
        val host=bean.string();val port=bean.int();require(port in 1..65535)
        val outbound=JSONObject().put("type",if(type==0) "socks" else "shadowsocks").put("server",host).put("server_port",port)
        if(type==0) {
            val protocol=if(version>=1) bean.int() else 2
            outbound.put("version",when(protocol) { 0 -> "4";1 -> "4a";2 -> "5";else -> error("未知SOCKS版本") })
            outbound.put("username",bean.string());outbound.put("password",bean.string())
            if(version>=2 && bean.bool()) outbound.put("udp_over_tcp",true)
        } else {
            outbound.put("method",bean.string());outbound.put("password",bean.string());val plugin=bean.string()
            if(plugin.isNotEmpty()) { val parts=plugin.split(';',limit=2);outbound.put("plugin",parts[0]);if(parts.size==2) outbound.put("plugin_opts",parts[1]) }
            if(bean.bool()) outbound.put("udp_over_tcp",true)
            if(version>=3) {
                val enabled=bean.bool();val padding=bean.bool();val protocol=bean.int();val concurrency=bean.int()
                val mux=JSONObject().put("enabled",enabled).put("padding",padding).put("protocol",when(protocol) { 0->"h2mux";1->"smux";2->"yamux";else->error("未知Mux类型") })
                if(version>=4) { val mode=bean.int();val max=bean.int();val min=bean.int();meta.put("muxMode",mode);if(max>0) mux.put("max_connections",max);if(min>0) mux.put("min_streams",min) } else if(concurrency>0) mux.put("max_streams",concurrency)
                if(version>=5) { val brutal=bean.bool();val up=bean.int();val down=bean.int();mux.put("brutal",JSONObject().put("enabled",brutal).put("up_mbps",up).put("down_mbps",down)) }
                outbound.put("multiplex",mux)
            }
        }
        require(bean.int()==0) { "未知bean尾部版本" };val name=bean.string();val customOutbound=bean.string();val customConfig=bean.string();bean.end()
        if(customConfig.isNotEmpty()) meta.put("customConfig",backupJson(customConfig))
        if(customOutbound.isNotEmpty()) { val custom=backupJson(customOutbound);meta.put("customOutbound",custom);ConfigBuilder.mergeJson(outbound,custom) }
        require(order in Int.MIN_VALUE..Int.MAX_VALUE);return Node(id,group,name.ifEmpty { "$host:$port" },outbound.toString(),ping=ping,tx=tx,rx=rx,order=order.toInt(),status=status,metadata=meta.toString())
    }
    private fun standardNode(bean:KryoReader,type:Int,id:Long,group:Long,order:Long,tx:Long,rx:Long,status:Int,ping:Int,meta:JSONObject):Node {
        if(type==1 || type==19) require(bean.int()==0) { "未知HTTP版本" }
        if(type==6) {
            val ownVersion=bean.int();require(ownVersion in 0..2) { "未知Trojan版本" }
            if(ownVersion<2) {
                val host=bean.string();val port=bean.int();require(port in 1..65535);val o=JSONObject().put("type","trojan").put("server",host).put("server_port",port).put("password",bean.string())
                val security=bean.string();val sni=bean.string();val alpn=bean.string();val insecure=if(ownVersion==1) bean.bool() else false
                require(security.isEmpty() || security=="tls") { "未知旧Trojan安全类型" };val tls=JSONObject().put("enabled",true).put("server_name",sni).put("insecure",insecure);if(alpn.isNotEmpty()) tls.put("alpn",org.json.JSONArray(alpn.split(',')));o.put("tls",tls)
                require(bean.int()==0);val name=bean.string();val custom=bean.string();val global=bean.string();bean.end();if(global.isNotEmpty()) meta.put("customConfig",backupJson(global));if(custom.isNotEmpty()) { val fields=backupJson(custom);meta.put("customOutbound",fields);ConfigBuilder.mergeJson(o,fields) }
                require(order in Int.MIN_VALUE..Int.MAX_VALUE);return Node(id,group,name.ifEmpty { "$host:$port" },o.toString(),ping=ping,tx=tx,rx=rx,order=order.toInt(),status=status,metadata=meta.toString())
            }
        }
        val version=bean.int();require(version in 1..10) { "V2Ray bean版本${version}尚未完整兼容" }
        val host=bean.string();val port=bean.int();require(port in 1..65535)
        val uuid=bean.string();val encryption=bean.string();val vlessEncryption=if(version>=5) bean.string() else ""
        val alter=if(type==4) bean.int() else 0
        val protocol=when(type) { 1->"http";6->"trojan";19->"shadowtls";else->if(alter==-1) "vless" else "vmess" }
        val o=JSONObject().put("type",protocol).put("server",host).put("server_port",port)
        if(type==4) { o.put("uuid",uuid);if(alter==-1) { require(vlessEncryption.isEmpty() || vlessEncryption=="none") { "VLESS加密扩展尚未兼容" };if(encryption.isNotEmpty() && encryption!="none") o.put("flow",encryption) } else { o.put("security",encryption.ifEmpty { "auto" });o.put("alter_id",alter) } }
        val transport=bean.string();meta.put("legacyTransport",transport)
        val t=JSONObject().put("type",transport)
        when(transport) {
            "ws","http","httpupgrade" -> { val h=bean.string();val path=bean.string();t.put("path",path);if(h.isNotEmpty()) { if(transport=="http") t.put("host",org.json.JSONArray(h.split(','))) else if(transport=="httpupgrade") t.put("host",h) else t.put("headers",JSONObject().put("Host",h)) };if(transport=="ws") { t.put("max_early_data",bean.int());t.put("early_data_header_name",bean.string()) } }
            "grpc" -> { t.put("service_name",bean.string());if(version<4) { meta.put("grpcLegacy1",bean.string());meta.put("grpcLegacy2",bean.string()) } }
            "xhttp" -> { require(version>=4);t.put("host",bean.string());t.put("path",bean.string());t.put("mode",bean.string());val extra=bean.string();if(extra.isNotEmpty()) { val fields=backupJson(extra);meta.put("legacyXhttpExtra",fields);ConfigBuilder.mergeJson(t,fields) } }
            "tcp","" -> Unit
            else -> error("传输${transport}尚未等价迁移")
        }
        if(type !in setOf(1,19) && transport !in setOf("tcp","")) o.put("transport",t)
        val security=bean.string()
        if(security=="tls") {
            val tls=JSONObject().put("enabled",true).put("server_name",bean.string())
            val alpn=bean.string();if(alpn.isNotEmpty()) tls.put("alpn",org.json.JSONArray(alpn.split(',').map { it.trim() }))
            val certificates=bean.string();if(certificates.isNotEmpty()) tls.put("certificate",certificates)
            tls.put("insecure",bean.bool());val fingerprint=bean.string();if(fingerprint.isNotEmpty()) tls.put("utls",JSONObject().put("enabled",true).put("fingerprint",fingerprint))
            val pub=bean.string();val short=bean.string();if(pub.isNotEmpty()) tls.put("reality",JSONObject().put("enabled",true).put("public_key",pub).put("short_id",short))
            o.put("tls",tls)
        } else require(security.isEmpty() || security=="none") { "未知TLS安全类型" }
        val ech=bean.bool();val echConfig=if(version>=3) bean.string() else if(ech) { meta.put("legacyEchPq",bean.bool()).put("legacyEchDynamic",bean.bool());bean.string() } else "";if(ech) { val tls=o.optJSONObject("tls") ?: error("ECH缺少TLS");tls.put("ech",JSONObject().put("enabled",true).put("config",org.json.JSONArray(echConfig.lines().filter { it.isNotBlank() }))) }
        val packet=bean.int();if(type==4 && packet!=0) o.put("packet_encoding",when(packet) { 1->"packetaddr";2->"xudp";else->error("未知packet编码") })
        val enabled=if(version>=2) bean.bool() else false;val padding=if(version>=2) bean.bool() else false;val muxType=if(version>=2) bean.int() else 0;val concurrency=if(version>=2) bean.int() else 0
        val mux=JSONObject().put("enabled",enabled).put("padding",padding).put("protocol",when(muxType) {0->"h2mux";1->"smux";2->"yamux";else->error("未知Mux协议")})
        if(version>=7) { meta.put("muxMode",bean.int());val max=bean.int();val min=bean.int();if(max>0) mux.put("max_connections",max);if(min>0) mux.put("min_streams",min) } else if(concurrency>0) mux.put("max_streams",concurrency)
        if(version>=8) mux.put("brutal",JSONObject().put("enabled",bean.bool()).put("up_mbps",bean.int()).put("down_mbps",bean.int()))
        if(type in setOf(4,6)) o.put("multiplex",mux)
        if(type==1) { o.put("username",bean.string());o.put("password",bean.string()) }
        if(type==6) o.put("password",bean.string())
        if(type==19) { o.put("version",bean.int());o.put("password",bean.string()) }
        require(bean.int()==0) { "未知bean尾部版本" };val name=bean.string();val custom=bean.string();val customConfig=bean.string();if(customConfig.isNotEmpty()) meta.put("customConfig",backupJson(customConfig));bean.end()
        if(custom.isNotEmpty()) { val fields=backupJson(custom);meta.put("customOutbound",fields);ConfigBuilder.mergeJson(o,fields) }
        require(order in Int.MIN_VALUE..Int.MAX_VALUE);return Node(id,group,name.ifEmpty { "$host:$port" },o.toString(),ping=ping,tx=tx,rx=rx,order=order.toInt(),status=status,metadata=meta.toString())
    }
    private fun rule(value:String):RouteRule {
        val p=ParcelReader(decoded(value));val id=p.long();val name=p.string();val config=p.string();val order=p.long();val enabled=p.int()!=0;val domains=p.string();val ip=p.string()
        val advanced=JSONObject();if(config.isNotEmpty()) advanced.put("customRule",backupJson(config))
        listOf("port","source_port","network","source_ip_cidr","protocol","rule_set").forEach { key -> val text=p.string();if(text.isNotEmpty()) advanced.put(key,text) }
        val target=p.long();val count=p.int();require(count in 0..10000);val packages=(0 until count).map { p.string() };val prioritize=p.int()!=0;p.end()
        require(order in Int.MIN_VALUE..Int.MAX_VALUE)
        val outbound=when(target) { 0L -> "proxy";-1L -> "direct";-2L -> "block";else -> { if(target<=-101) return RouteRule(id,name,domains,packages.joinToString("\n"),ip,"group:${-(target+100)}",enabled,order.toInt(),advanced.toString(),prioritize);require(target>0) { "未知旧路由目标$target" };"node:$target" } }
        return RouteRule(id,name,domains,packages.joinToString("\n"),ip,outbound,enabled,order.toInt(),advanced.toString(),prioritize)
    }
}

internal class ParcelReader(private val bytes:ByteArray) {
    private val buffer=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun int():Int { require(buffer.remaining()>=4);return buffer.int }
    fun long():Long { require(buffer.remaining()>=8);return buffer.long }
    private fun align() { val position=(buffer.position()+3) and -4;require(position<=bytes.size);buffer.position(position) }
    fun bytes():ByteArray { val n=int();require(n>=0 && n<=buffer.remaining());return ByteArray(n).also { buffer.get(it);align() } }
    fun string():String { val n=int();if(n==-1) return "";require(n>=0 && n.toLong()*2+2<=buffer.remaining());val text=ByteArray(n*2);buffer.get(text);require(buffer.short.toInt()==0);align();return text.toString(Charsets.UTF_16LE) }
    fun end() { require(!buffer.hasRemaining()) { "Parcel存在尾随数据" } }
}
internal class KryoReader(bytes:ByteArray) {
    private val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun int():Int { require(b.remaining()>=4);return b.int }
    fun long():Long { require(b.remaining()>=8);return b.long }
    private fun byte():Int { require(b.hasRemaining());return b.get().toInt() and 255 }
    fun bool():Boolean { val v=byte();require(v<=1);return v==1 }
    fun bytes(n:Int):ByteArray { require(n>=0 && n<=b.remaining());return ByteArray(n).also { b.get(it) } }
    fun varInt():Int { var value=0;for(i in 0..4) { val x=byte();if(i==4) require(x<=15);value=value or ((x and 127) shl (i*7));if(x and 128==0) return value };error("无效Kryo长度") }
    fun string():String {
        val first=byte()
        if(first and 128==0) {
            val result=StringBuilder().append(first.toChar());while(true) { val x=byte();result.append((x and 127).toChar());if(x and 128!=0) break;require(result.length<=MAX_BACKUP) };return result.toString()
        }
        var count=first and 63
        if(first and 64!=0) { var shift=6;while(true) { val x=byte();require(shift<=27);count=count or ((x and 127) shl shift);shift+=7;if(x and 128==0) break } }
        if(count<=1) return ""
        require(count<=MAX_BACKUP);val result=StringBuilder()
        repeat(count-1) { val c=byte();result.append(when { c<128 -> c.toChar();c and 224==192 -> { val second=byte();require(second and 192==128);(((c and 31) shl 6) or (second and 63)).toChar() };c and 240==224 -> { val second=byte();val third=byte();require(second and 192==128 && third and 192==128);(((c and 15) shl 12) or ((second and 63) shl 6) or (third and 63)).toChar() };else -> error("无效Kryo字符串") }) }
        return result.toString()
    }
    fun end() { require(!b.hasRemaining()) { "Kryo存在尾随或未知字段" } }
}
