@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.zane.zanebox.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zane.zanebox.data.*
import com.zane.zanebox.config.normalizeSmartTarget
import com.zane.zanebox.subscription.SubscriptionOptions
import org.json.JSONArray
import org.json.JSONObject

@Composable internal fun TextEditPage(editor:TextEditor,onDismiss:()->Unit) {
    var values by remember(editor){mutableStateOf(editor.fields.map{it.second})}
    var error by remember{mutableStateOf("")}
    UiPageList(editor.title,onDismiss,action={TextButton(onClick={runCatching{editor.save(values)}.onSuccess{onDismiss()}.onFailure{error=it.message ?: "字段无效"}},modifier=Modifier.testTag("editor_save")){Text(uiText("保存"))}}) {
        items(editor.fields.indices.toList()){i->OutlinedTextField(values[i],{v->values=values.toMutableList().apply{this[i]=v};error=""},label={Text(uiText(editor.fields[i].first))},
            visualTransformation=if(editor.fields[i].first.contains("密码"))PasswordVisualTransformation() else VisualTransformation.None,
            modifier=Modifier.fillMaxWidth().testTag("editor_field_$i"))}
        if(error.isNotBlank())item{Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("editor_error"))}
    }
}

@Composable internal fun TargetPicker(title:String,value:String,data:AppData,onDismiss:()->Unit,onChoose:(String)->Unit,smart:Boolean=false,none:Boolean=false) {
    var openGroupId by remember{mutableStateOf<Long?>(null)}
    val group=if(smart)data.groups.firstOrNull{it.id==openGroupId && it.enabled}else null
    if(group!=null) {
        ChoiceDialog(group.name,normalizeSmartTarget(value),listOf("group:${group.id}" to "整个分组")+
            data.nodes.filter{it.groupId==group.id}.map{"node:${it.id}" to it.name},{openGroupId=null}) {
            onChoose(it);onDismiss()
        }
        return
    }
    val choices=buildList {
        if(none)add("0" to "无")
        if(smart)addAll(listOf("off" to "关闭","proxy" to "代理","direct" to "直连","auto" to "自动选择"))
        else if(!none)addAll(listOf("proxy" to "代理","direct" to "直连","block" to "阻止"))
        data.groups.filter{it.enabled}.forEach{add("group:${it.id}" to "分组 · ${it.name}")}
        data.merges.forEach{add("merge:${it.id}" to "汇总组 · ${it.name}")}
        if(!smart)data.nodes.filter{n->data.groups.any{it.id==n.groupId && it.enabled}}.forEach{add("node:${it.id}" to it.name)}
    }
    val selected=if(smart && value.startsWith("node:"))data.nodes.firstOrNull{it.id==value.substringAfter(':').toLongOrNull()}?.let{"group:${it.groupId}"} ?: value
        else if(smart)normalizeSmartTarget(value)else value
    ChoiceDialog(title,selected,choices,onDismiss,dismissOnChoose=!smart) {
        if(smart && it.startsWith("group:"))openGroupId=it.substringAfter(':').toLong()
        else {onChoose(it);if(smart)onDismiss()}
    }
}
internal fun targetName(value:String,data:AppData):String = when(normalizeSmartTarget(value)) {
    "off"->"关闭";"auto"->"自动选择";"proxy"->"代理";"direct"->"直连";"block"->"阻止";"0"->"无"
    else->when(value.substringBefore(':')) {
        "node"->data.nodes.firstOrNull{it.id==value.substringAfter(':').toLongOrNull()}?.name ?: "节点已删除"
        "group"->data.groups.firstOrNull{it.id==value.substringAfter(':').toLongOrNull()}?.name ?: "分组已删除"
        "merge"->data.merges.firstOrNull{it.id==value.substringAfter(':').toLongOrNull()}?.name ?: "汇总组已删除"
        else->value
    }
}

private data class NodeField(val title:String,val path:String,val kind:String="text",val choices:List<String> = emptyList())

/** Edits a field without dropping unexposed or future protocol properties. */
internal fun updateNodeField(text:String,path:String,value:String,kind:String):String {
    val root=JSONObject(text);var target=root
    if(root.optString("type")=="hysteria2" && path=="server_ports") {
        if(value.isBlank())root.remove("server_ports") else com.zane.zanebox.subscription.SubscriptionParser.applyHysteriaPorts(root,value.replace('\n',','))
        return root.toString()
    }
    if(root.optString("type")=="hysteria2" && path=="server_port" && value.isNotBlank())root.remove("server_ports")
    if(path=="transport.type") {
        if(value.isBlank())root.remove("transport")
        else if(root.optJSONObject("transport")?.optString("type")!=value)root.put("transport",JSONObject().put("type",value))
        return root.toString()
    }
    val keys=path.split('.')
    keys.dropLast(1).forEach{key->target=target.optJSONObject(key) ?: JSONObject().also{target.put(key,it)}}
    val key=keys.last()
    if(value.isBlank() && kind!="bool")target.remove(key)
    else target.put(key,when(kind){"number"->value.toInt();"bool"->value.toBooleanStrict();"list"->JSONArray(value.split('\n',',').map{it.trim()}.filter{it.isNotEmpty()});"json"->JSONObject(value);
        "reserved"->if(value.trim().startsWith('['))JSONArray(value).also{a->require(a.length()==3 && (0 until a.length()).all{a.getInt(it) in 0..255}){"保留字节须为 3 个 0–255 整数"}} else value.trim().removeSurrounding("\"").also{require(it.matches(Regex("[A-Za-z0-9+/]{4}"))){"保留字节须为 3 字节 Base64 或整数数组"}};
        else->value})
    return root.toString()
}
private fun nodeField(text:String,path:String):String {
    var value:Any=JSONObject(text)
    path.split('.').forEach{key->value=(value as? JSONObject)?.opt(key) ?: return ""}
    return if(value is JSONArray) {if(path=="reserved")value.toString() else (0 until value.length()).joinToString("\n"){value.opt(it).toString()}} else value.toString()
}

internal fun jsonFieldText(root:JSONObject,key:String):String {
    fun text(value:Any?):String=when(value) {
        null,JSONObject.NULL->""
        is JSONArray->(0 until value.length()).joinToString("\n"){value.opt(it).toString()}
        is JSONObject->value.toString(2)
        else->value.toString()
    }
    return if(key in listOf("port","source_port"))listOf(text(root.opt(key)),text(root.opt("${key}_range"))).filter{it.isNotBlank()}.joinToString("\n") else text(root.opt(key))
}
internal fun updateRuleField(text:String,key:String,value:String):String=JSONObject(text).apply {
    if(key in listOf("port","source_port"))remove("${key}_range")
    if(value.isBlank())remove(key) else put(key,if(key=="customRule")JSONObject(value) else value.trim())
}.toString()

@Composable internal fun NodeEditor(node:Node?,data:AppData,initialProtocol:String="vless",onDismiss:()->Unit,saveError:String="",saving:Boolean=false,onSave:(Node)->Unit) {
    var name by remember{mutableStateOf(node?.name ?: "")}
    var outbound by remember{mutableStateOf(node?.outbound?.let{raw->JSONObject(raw).apply{if(optString("type")=="ssh" && has("username")){if(!has("user"))put("user",get("username"));remove("username")};com.zane.zanebox.config.ConfigBuilder.normalizeTransportHost(this)}.toString()} ?: (if(initialProtocol=="chain")"{\"type\":\"chain\",\"node_ids\":[]}" else "{\"type\":\"vless\",\"server_port\":443}"))}
    var group by remember{mutableLongStateOf(node?.groupId ?: data.browseGroupId.takeIf{id->data.groups.any{it.id==id}} ?: data.groups.firstOrNull{it.enabled}?.id ?:0L)}
    var editing by remember{mutableStateOf<NodeField?>(null)}
    var trojanGo by remember{mutableStateOf(node?.metadata?.let{JSONObject(it).optString("editorProtocol")=="trojan-go"} ?:false)}
    var typeChoice by remember{mutableStateOf(false)};var groupChoice by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    val type=JSONObject(outbound).optString("type")
    val protocols=listOf("socks","http","shadowsocks","vmess","vless","trojan","trojan-go","hysteria","hysteria2","tuic","anytls","ssh","wireguard","juicity","snell","shadowtls","chain","custom")
    fun field(title:String,path:String,kind:String="text",choices:List<String> = emptyList())=NodeField(title,path,kind,choices)
    val fields=buildList {
        if(type in listOf("chain","custom"))return@buildList
        add(field("服务器地址","server"));add(field("服务器端口","server_port","number"))
        if(type in listOf("vmess","vless","tuic","juicity"))add(field("UUID","uuid"))
        if(type in listOf("socks","http","naive","mieru"))add(field("用户名","username"))
        if(type=="ssh")add(field("用户名","user"))
        if(type in listOf("shadowsocks","shadowsocksr","trojan","hysteria2","tuic","anytls","socks","http","ssh","naive","mieru","juicity","shadowtls"))add(field("密码","password","password"))
        if(type in listOf("shadowsocks","shadowsocksr"))add(field("加密方法","method",choices=listOf("2022-blake3-aes-128-gcm","2022-blake3-aes-256-gcm","aes-128-gcm","aes-256-gcm","chacha20-ietf-poly1305","none")))
        if(type=="vmess"){add(field("加密方式","security",choices=listOf("auto","aes-128-gcm","chacha20-poly1305","none","zero")));add(field("Alter ID","alter_id","number"))}
        if(type=="vless")add(field("Flow","flow",choices=listOf("","xtls-rprx-vision")))
        if(type in listOf("vmess","vless"))add(field("数据包编码","packet_encoding",choices=listOf("","xudp","packetaddr")))
        if(type=="socks")add(field("SOCKS 版本","version",choices=listOf("4","4a","5")))
        if(type in listOf("hysteria","hysteria2")) {add(field("上传带宽（Mbps）","up_mbps","number"));add(field("下载带宽（Mbps）","down_mbps","number"));add(field("服务器端口范围","server_ports","list"))}
        if(type=="hysteria2"){add(field("混淆类型","obfs.type",choices=listOf("","salamander")));add(field("混淆密码","obfs.password","password"))}
        if(type=="hysteria"){add(field("认证字符串","auth_str"));add(field("混淆","obfs"))}
        if(type=="tuic"){add(field("拥塞控制","congestion_control",choices=listOf("cubic","new_reno","bbr")));add(field("UDP 中继模式","udp_relay_mode",choices=listOf("native","quic")));add(field("零 RTT 握手","zero_rtt_handshake","bool"));add(field("心跳间隔","heartbeat"))}
        if(type=="ssh"){add(field("私钥","private_key","list"));add(field("私钥口令","private_key_passphrase","password"));add(field("主机公钥","host_key","list"))}
        if(type=="wireguard"){add(field("本机地址","local_address","list"));add(field("私钥","private_key","password"));add(field("对端公钥","peer_public_key"));add(field("预共享密钥","pre_shared_key","password"));add(field("MTU","mtu","number"));add(field("保留字节","reserved","reserved"))}
        if(type=="shadowsocksr"){add(field("协议","protocol"));add(field("协议参数","protocol_param"));add(field("混淆","obfs"));add(field("混淆参数","obfs_param"))}
        if(type=="snell"){add(field("PSK","psk","password"));add(field("版本","version","number"))}
        if(type=="shadowtls")add(field("版本","version","number"))
        if(type in listOf("vmess","vless","trojan")) {
            add(field("传输协议","transport.type",choices=listOf("","ws","http","httpupgrade","grpc","xhttp")))
            when(nodeField(outbound,"transport.type")) {
                "ws","httpupgrade","xhttp"->{add(field("路径","transport.path"));add(field("Host",if(nodeField(outbound,"transport.type")=="ws")"transport.headers.Host" else "transport.host"));if(nodeField(outbound,"transport.type")=="ws"){add(field("最大提前数据","transport.max_early_data","number"));add(field("提前数据请求头","transport.early_data_header_name"))}}
                "http"->{add(field("Host","transport.host","list"));add(field("路径","transport.path"))}
                "grpc"->add(field("服务名称","transport.service_name"))
            }
        }
        if(type !in listOf("wireguard","shadowsocksr","snell","ssh")) {
            add(field("启用 TLS","tls.enabled","bool"))
            if(nodeField(outbound,"tls.enabled")=="true") {
                add(field("服务器名称（SNI）","tls.server_name"));add(field("ALPN","tls.alpn","list"));add(field("允许不安全 TLS","tls.insecure","bool"));add(field("证书","tls.certificate","list"))
                add(field("启用 uTLS","tls.utls.enabled","bool"));if(nodeField(outbound,"tls.utls.enabled")=="true")add(field("uTLS 指纹","tls.utls.fingerprint",choices=listOf("chrome","firefox","safari","ios","android","edge","random","randomized")))
                add(field("启用 Reality","tls.reality.enabled","bool"));if(nodeField(outbound,"tls.reality.enabled")=="true"){add(field("Reality 公钥","tls.reality.public_key"));add(field("Reality Short ID","tls.reality.short_id"))}
                add(field("启用 ECH","tls.ech.enabled","bool"));if(nodeField(outbound,"tls.ech.enabled")=="true")add(field("ECH 配置","tls.ech.config","list"))
            }
        }
        if(type in listOf("shadowsocks","vmess","vless","trojan")) {
            add(field("启用连接复用","multiplex.enabled","bool"));if(nodeField(outbound,"multiplex.enabled")=="true") {
                add(field("复用协议","multiplex.protocol",choices=listOf("h2mux","smux","yamux")));add(field("最大连接数","multiplex.max_connections","number"));add(field("最小流数","multiplex.min_streams","number"));add(field("最大流数","multiplex.max_streams","number"));add(field("填充","multiplex.padding","bool"))
            }
        }
        add(field("TCP Fast Open","tcp_fast_open","bool"));add(field("UDP 分片","udp_fragment","bool"));add(field("连接超时","connect_timeout"))
    }
    UiPageList(if(node==null && type=="chain")"创建链式代理" else if(node==null)"手动添加" else "编辑节点",{if(!saving)onDismiss()},action={TextButton(enabled=!saving,onClick={
        runCatching{val o=JSONObject(outbound);require(name.isNotBlank()){ "请输入节点名称" };when(type) {
            "chain"->require((o.optJSONArray("node_ids")?.length() ?:0)>0){"请选择链式节点"}
            "custom"->com.zane.zanebox.config.ConfigBuilder.validate(o.getJSONObject("config").toString())
            else->{require(o.optString("server").isNotBlank()){ "请输入服务器地址" };if(type=="hysteria2" && (o.optJSONArray("server_ports")?.length() ?:0)>0) {
                val ports=o.getJSONArray("server_ports");com.zane.zanebox.subscription.SubscriptionParser.applyHysteriaPorts(o,(0 until ports.length()).joinToString(","){ports.getString(it)})
            } else require(o.optInt("server_port") in 1..65535){"端口须为 1–65535"}}
        }
        val link=if(node?.outbound==outbound)node.shareLink.takeIf{it.isNotBlank()}?.substringBefore('#')?.plus("#"+java.net.URLEncoder.encode(name,"UTF-8").replace("+","%20")) ?: "" else ""
        val metadata=JSONObject(node?.metadata ?: "{}").apply{if(trojanGo)put("editorProtocol","trojan-go")else remove("editorProtocol")};onSave((node ?:Node(0,group,name,outbound)).copy(name=name,groupId=group,outbound=o.toString(),shareLink=link,metadata=metadata.toString()))}.onFailure{error=it.message ?: "字段无效"}
    },modifier=Modifier.testTag("node_save")){Text(uiText("保存"))}}) {
        item{if(error.isNotBlank() || saveError.isNotBlank())Text(error.ifBlank{saveError},color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("node_error"))}
        item{OutlinedTextField(name,{name=it;error=""},label={Text(uiText("节点名称"))},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("node_name"))}
        item{UiCard{UiRow("协议",if(trojanGo)"Trojan-Go · TLS / WS" else type.uppercase(),Icons.Outlined.Cable,onClick=if(node==null)({typeChoice=true})else null);UiRow("分组",data.groups.firstOrNull{it.id==group}?.name ?: "手动节点",Icons.Outlined.Folder,onClick={groupChoice=true})}}
        if(type in listOf("shadowsocksr","mieru","naive"))item{Text("当前内核不提供 ShadowsocksR、Mieru、NaïveProxy 外部插件功能。已有配置可备份和导出，不能作为可用节点保存。",style=MaterialTheme.typography.bodySmall)}
        if(trojanGo)item{Text(uiText("Trojan-Go 使用原生 TLS / WebSocket；外部插件与额外加密层不可用。"),style=MaterialTheme.typography.bodySmall)}
        if(type=="chain") {
            item{UiSection("链式节点 · 从出口到前置选择")}
            items(data.nodes.filter{it.id!=node?.id},key={"chain${it.id}"}){n->
                val a=JSONObject(outbound).optJSONArray("node_ids") ?:JSONArray()
                val ids=(0 until a.length()).map{a.getLong(it)}
                UiCard{UiRow(n.name,onClick={outbound=JSONObject(outbound).put("node_ids",JSONArray(if(n.id in ids)ids-n.id else ids+n.id)).toString()},trailing={Checkbox(n.id in ids,{outbound=JSONObject(outbound).put("node_ids",JSONArray(if(it)ids+n.id else ids-n.id)).toString()})})}
            }
        }
        if(type=="custom")item{UiCard{UiRow("自定义配置","完整 sing-box 配置",Icons.Outlined.Code,onClick={editing=field("自定义配置","config","json")})}}
        item{UiSection("代理设置")}
        item{UiCard { fields.forEachIndexed{index,f->val value=nodeField(outbound,f.path)
            UiRow(f.title,if(f.kind=="password" && value.isNotBlank())"••••••" else if(f.kind=="bool")"" else value.ifBlank{"未设置"},onClick=if(f.kind=="bool")null else ({editing=f}),minHeight=58.dp,chevron=false,
                modifier=Modifier.testTag("node_field_${f.path}"),trailing=if(f.kind=="bool")({UiSwitch(value=="true",{outbound=updateNodeField(outbound,f.path,it.toString(),f.kind)},modifier=Modifier)})else null)
            if(index<fields.lastIndex)HorizontalDivider(Modifier.padding(horizontal=16.dp),thickness=.5.dp,color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))
        }}}
        item{UiCard{UiRow("高级配置", "编辑完整节点配置",Icons.Outlined.Code,onClick={editing=field("高级配置","","outbound")})}}
    }
    if(typeChoice)ChoiceDialog("协议",if(trojanGo)"trojan-go" else type,protocols.map{it to when(it){"chain"->"链式代理";"custom"->"自定义配置";"trojan-go"->"Trojan-Go（TLS / WS）";else->it.uppercase()}},{typeChoice=false}){trojanGo=it=="trojan-go";outbound=JSONObject().put("type",if(trojanGo)"trojan" else it).apply{if(trojanGo)put("tls",JSONObject().put("enabled",true));if(it=="custom")put("config",JSONObject()) else if(it=="chain")put("node_ids",JSONArray())else put("server",nodeField(outbound,"server")).put("server_port",when(it){"socks"->1080;"http"->8080;"ssh"->22;"wireguard"->51820;else->443})}.toString()}
    if(groupChoice)ChoiceDialog("分组",group.toString(),listOf("0" to "手动节点")+data.groups.map{it.id.toString() to it.name},{groupChoice=false}){group=it.toLong()}
    editing?.let{f->
        if(f.choices.isNotEmpty())ChoiceDialog(f.title,nodeField(outbound,f.path),f.choices.map{it to it.ifBlank{"无"}},{editing=null}){outbound=updateNodeField(outbound,f.path,it,f.kind)}
        else TextEditPage(TextEditor(f.title,listOf(f.title to if(f.kind=="outbound")JSONObject(outbound).toString(2) else nodeField(outbound,f.path))){v->
            if(f.kind=="outbound"){val o=JSONObject(v[0]);require(o.optString("type").isNotBlank());outbound=o.toString()}else outbound=updateNodeField(outbound,f.path,v[0],f.kind)
        }){editing=null}
    }
}

@Composable internal fun GroupEditor(group:Group?,data:AppData,onDismiss:()->Unit,saveError:String="",saving:Boolean=false,onSave:(Group)->Unit) {
    val original=remember(group?.id,group?.options){runCatching{JSONObject(group?.options ?: "{}")} .getOrDefault(JSONObject())}
    var name by remember(group?.id){mutableStateOf(group?.name ?: "")}
    var url by remember(group?.id){mutableStateOf(group?.subscriptionUrl ?: "")}
    var groupType by remember(group?.id,group?.options){mutableIntStateOf(original.optInt("type",if(group?.subscriptionUrl.isNullOrBlank())0 else 1))}
    var sortOrder by remember(group?.id,group?.options){mutableIntStateOf(if(group==null)0 else when(nodeSortMode(data,group)){NodeSortMode.NAME->1;NodeSortMode.LATENCY->2;else->0})}
    var selector by remember(group?.id,group?.options){mutableStateOf(original.optBoolean("groupIsSelector",original.optBoolean("isSelector",false)))}
    var front by remember(group?.id){mutableLongStateOf(group?.frontProxy ?:0)}
    var landing by remember(group?.id){mutableLongStateOf(group?.landingProxy ?:0)}
    var autoUpdate by remember(group?.id,group?.options){mutableStateOf(original.optBoolean("autoUpdate",false))}
    var autoUpdateDelay by remember(group?.id,group?.options){mutableStateOf(original.optInt("autoUpdateDelay",1440).toString())}
    var connectedOnly by remember(group?.id,group?.options){mutableStateOf(original.optBoolean("updateWhenConnectedOnly",false))}
    var customUserAgent by remember(group?.id,group?.options){mutableStateOf(original.optString("customUserAgent"))}
    var filterMode by remember(group?.id,group?.options){mutableIntStateOf(original.optInt("filterMode",0))}
    var filterRegex by remember(group?.id,group?.options){mutableStateOf(original.optString("filterRegex"))}
    var deduplication by remember(group?.id,group?.options){mutableStateOf(original.optBoolean("deduplication",false))}
    var forceResolve by remember(group?.id,group?.options){mutableStateOf(original.optBoolean("forceResolve",false))}
    var serverDnsResolver by remember(group?.id,group?.options){mutableStateOf(original.optString("serverDnsResolver"))}
    var target by remember{mutableStateOf("")};var choice by remember{mutableStateOf("")};var error by remember{mutableStateOf("")}
    val filterLabels=listOf("关闭","保留匹配","排除匹配")
    fun save() {
        runCatching {
            require(name.isNotBlank()){ "请输入分组名称" }
            require(sortOrder in 0..2) { "分组排序无效" }
            require(groupType in 0..1){ "分组类型无效" }
            if(groupType==1)require(url.isNotBlank()){ "订阅分组必须填写订阅 URL" }
            if(url.isNotBlank()){
                val u=java.net.URI(url.trim())
                require(u.scheme in listOf("https","http") && !u.host.isNullOrBlank()){ "请输入有效订阅 URL" }
            }
            val options=JSONObject(original.toString())
                .put("type",groupType).put("groupIsSelector",selector).put("groupOrder",sortOrder).put("nodeSortOrder",sortOrder)
                .put("isSelector",selector)
                .put("autoUpdate",autoUpdate).put("autoUpdateDelay",autoUpdateDelay.toInt())
                .put("updateWhenConnectedOnly",connectedOnly).put("customUserAgent",customUserAgent)
                .put("filterMode",filterMode).put("filterRegex",filterRegex)
                .put("deduplication",deduplication).put("forceResolve",forceResolve)
                .put("serverDnsResolver",serverDnsResolver)
            SubscriptionOptions.parse(options.toString())
            onSave((group ?:Group(0,name)).copy(name=name,subscriptionUrl=url.trim(),frontProxy=front,landingProxy=landing,options=options.toString()))
        }.onFailure{error=it.message ?: "字段无效"}
    }
    UiPageList(if(group==null)"添加订阅" else "编辑分组",{if(!saving)onDismiss()},action={TextButton(enabled=!saving,onClick=::save,modifier=Modifier.testTag("group_save")){Text(uiText("保存"))}}) {
        item{OutlinedTextField(name,{name=it},label={Text(uiText("分组名称"))},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("group_name"))}
        item{OutlinedTextField(url,{url=it},label={Text(uiText("订阅 URL"))},supportingText={Text(uiText("基础分组可留空"))},modifier=Modifier.fillMaxWidth().testTag("group_url"))}
        item{UiCard{
            UiRow("分组类型",if(groupType==1)"订阅分组" else "基础分组",Icons.Outlined.Folder,onClick={choice="type"},modifier=Modifier.testTag("group_type"))
            UiRow("节点排序",listOf("原始顺序","按名称","按延迟")[sortOrder.coerceIn(0,2)],onClick={choice="order"},modifier=Modifier.testTag("group_order"))
            UiRow("选择器",if(selector)"启用" else "关闭",Icons.Outlined.Tune,onClick={selector=!selector},trailing={UiSwitch(selector,{selector=it},modifier=Modifier.testTag("group_selector"))},modifier=Modifier.testTag("group_selector_row"))
        }}
        item{UiSection("链式代理")}
        item{UiCard{UiRow("前置代理",targetName(if(front==0L)"0" else "node:$front",data),onClick={target="front"},modifier=Modifier.testTag("group_front_proxy"));UiRow("后置代理（落地）",targetName(if(landing==0L)"0" else "node:$landing",data),onClick={target="landing"},modifier=Modifier.testTag("group_landing_proxy"))}}
        if(groupType==1 || url.isNotBlank()) {
            item{UiSection("订阅更新")}
            item{UiCard{
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp)){Text(uiText("自动更新"),Modifier.weight(1f));UiSwitch(autoUpdate,{autoUpdate=it},modifier=Modifier.testTag("group_auto_update"))}
                OutlinedTextField(autoUpdateDelay,{autoUpdateDelay=it},label={Text(uiText("自动更新间隔（分钟）"))},singleLine=true,modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp).testTag("group_auto_update_delay"))
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp)){Text(uiText("仅连接时更新"),Modifier.weight(1f));UiSwitch(connectedOnly,{connectedOnly=it},modifier=Modifier.testTag("group_connected_only"))}
                OutlinedTextField(customUserAgent,{customUserAgent=it},label={Text(uiText("订阅 User-Agent"))},singleLine=true,modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp).testTag("group_user_agent"))
            }}
            item{UiCard{
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp)){Text(uiText("强制解析服务器 IP"),Modifier.weight(1f));UiSwitch(forceResolve,{forceResolve=it},modifier=Modifier.testTag("group_force_resolve"))}
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp)){Text(uiText("按协议身份去重"),Modifier.weight(1f));UiSwitch(deduplication,{deduplication=it},modifier=Modifier.testTag("group_deduplication"))}
                OutlinedTextField(serverDnsResolver,{serverDnsResolver=it},label={Text(uiText("服务器地址 DNS（空为默认）"))},singleLine=true,modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp).testTag("group_server_dns"))
            }}
            item{UiCard{
                UiRow("节点名称过滤",filterLabels[filterMode.coerceIn(filterLabels.indices)],Icons.Outlined.FilterList,onClick={choice="filter"},modifier=Modifier.testTag("group_filter_mode"))
                OutlinedTextField(filterRegex,{filterRegex=it},label={Text(uiText("过滤正则表达式"))},modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp).testTag("group_filter_regex"))
            }}
        }
        if(error.isNotBlank() || saveError.isNotBlank())item{Text(error.ifBlank{saveError},color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("group_error"))}
    }
    if(target.isNotBlank())ChoiceDialog(if(target=="front")"前置代理" else "落地代理",(if(target=="front")front else landing).toString(),listOf("0" to "无")+data.nodes.map{it.id.toString() to it.name},{target=""}){if(target=="front")front=it.toLong()else landing=it.toLong()}
    when(choice) {
        "type"->ChoiceDialog("分组类型",groupType.toString(),listOf("0" to "基础分组","1" to "订阅分组"),{choice=""}){groupType=it.toInt();choice=""}
        "order"->ChoiceDialog("节点排序",sortOrder.toString(),listOf("0" to "原始顺序","1" to "按名称","2" to "按延迟"),{choice=""}){sortOrder=it.toInt();choice=""}
        "filter"->ChoiceDialog("节点名称过滤",filterMode.toString(),filterLabels.mapIndexed{index,label->index.toString() to label},{choice=""}){filterMode=it.toInt();choice=""}
    }
}

@Composable internal fun RuleEditor(rule:RouteRule?,data:AppData,onDismiss:()->Unit,saveError:String="",saving:Boolean=false,onSave:(RouteRule)->Unit) {
    var name by remember{mutableStateOf(rule?.name ?: "")};var domains by remember{mutableStateOf(rule?.domains ?: "")}
    var packages by remember{mutableStateOf(rule?.packages ?: "")};var ips by remember{mutableStateOf(rule?.ipCidrs ?: "")}
    var target by remember{mutableStateOf(rule?.outbound ?: "proxy")};var priority by remember{mutableStateOf(rule?.prioritize ?:false)}
    var advanced by remember{mutableStateOf(rule?.advanced ?:"{}")};var choose by remember{mutableStateOf(false)};var apps by remember{mutableStateOf(false)}
    var editAdvanced by remember{mutableStateOf(false)};var editRouteField by remember{mutableStateOf("")};var error by remember{mutableStateOf("")}
    var positionChoice by remember{mutableStateOf(false)}
    val matching=runCatching{JSONObject(advanced)}.getOrDefault(JSONObject())
    val routePort=jsonFieldText(matching,"port");val routeSource=jsonFieldText(matching,"source_ip_cidr")
    val routeSourcePort=jsonFieldText(matching,"source_port");val routeRuleset=jsonFieldText(matching,"rule_set")
    val routeNetwork=jsonFieldText(matching,"network");val routeProtocol=jsonFieldText(matching,"protocol")
    val customRule=jsonFieldText(matching,"customRule")
    val editors=remember{mutableMapOf<String,String>()}
    val editorSaves=remember{mutableMapOf<String,(String)->Unit>()}
    fun editValue(title:String,value:String,onValue:(String)->Unit) { editRouteField=title;editors[title]=value;editorSaves[title]=onValue }
    UiPageList(if(rule==null)"添加规则" else "编辑规则",{if(!saving)onDismiss()},action={TextButton(enabled=!saving,onClick={runCatching{
        require(name.isNotBlank()){ "请输入规则名称" }
        com.zane.zanebox.config.ConfigBuilder.normalizeAdvanced(JSONObject(advanced))
        onSave((rule ?:RouteRule(0,name)).copy(name=name,domains=domains,packages=packages,ipCidrs=ips,outbound=target,prioritize=priority,advanced=advanced))
    }.onFailure{error=it.message ?: "字段无效"}},modifier=Modifier.testTag("rule_save")){Text(uiText("保存"))}}) {
        item{OutlinedTextField(name,{name=it},label={Text(uiText("规则名称"))},modifier=Modifier.fillMaxWidth().testTag("rule_name"))}
        item{UiCard{UiRow("出站",targetName(target,data),Icons.Outlined.AccountTree,onClick={choose=true},modifier=Modifier.testTag("rule_target"));UiRow("规则位置",if(priority)"前置 · 在应用策略之前" else "后置 · 在应用策略之后",onClick={positionChoice=true},modifier=Modifier.testTag("rule_position"))}}
        item{OutlinedTextField(domains,{domains=it},label={Text(uiText("域名（每行一个）"))},modifier=Modifier.fillMaxWidth().testTag("rule_domains"))}
        item{OutlinedTextField(ips,{ips=it},label={Text(uiText("IP CIDR（每行一个）"))},modifier=Modifier.fillMaxWidth().testTag("rule_ips"))}
        item{UiCard{UiRow("应用",if(packages.isBlank())"所有应用" else "已选择 ${packages.lines().count{it.isNotBlank()}} 个应用",Icons.Outlined.Apps,onClick={apps=true})}}
        item{OutlinedTextField(packages,{packages=it},label={Text(uiText("应用包名（每行一个）"))},modifier=Modifier.fillMaxWidth().testTag("rule_packages"))}
        item{UiSection("路由匹配")}
        item{UiCard{
            UiRow("目标端口",routePort.ifBlank{"未设置"},Icons.Outlined.Tune,onClick={editValue("目标端口",routePort){advanced=updateRuleField(advanced,"port",it)}},modifier=Modifier.testTag("rule_port"))
            UiRow("来源 IP",routeSource.ifBlank{"未设置"},Icons.Outlined.Home,onClick={editValue("来源 IP",routeSource){advanced=updateRuleField(advanced,"source_ip_cidr",it)}},modifier=Modifier.testTag("rule_source"))
            UiRow("来源端口",routeSourcePort.ifBlank{"未设置"},Icons.Outlined.Tune,onClick={editValue("来源端口",routeSourcePort){advanced=updateRuleField(advanced,"source_port",it)}},modifier=Modifier.testTag("rule_source_port"))
            UiRow("规则集",routeRuleset.ifBlank{"未设置"},Icons.Outlined.Storage,onClick={editValue("规则集",routeRuleset){advanced=updateRuleField(advanced,"rule_set",it)}},modifier=Modifier.testTag("rule_ruleset"))
            UiRow("网络",routeNetwork.ifBlank{"未设置"},Icons.Outlined.Settings,onClick={editValue("网络",routeNetwork){advanced=updateRuleField(advanced,"network",it)}},modifier=Modifier.testTag("rule_network"))
            UiRow("协议",routeProtocol.ifBlank{"未设置"},Icons.Outlined.Tune,onClick={editValue("协议",routeProtocol){advanced=updateRuleField(advanced,"protocol",it)}},modifier=Modifier.testTag("rule_protocol"))
            UiRow("自定义规则",if(customRule.isBlank())"未设置" else "已设置 JSON",Icons.Outlined.Code,onClick={editValue("自定义规则",customRule){advanced=updateRuleField(advanced,"customRule",it)}},modifier=Modifier.testTag("rule_custom_rule"))
            UiRow("其他高级匹配",advanced.takeIf{it!="{}"} ?: "无",Icons.Outlined.Tune,onClick={editAdvanced=true},modifier=Modifier.testTag("rule_advanced"))
        }}
        if(error.isNotBlank() || saveError.isNotBlank())item{Text(error.ifBlank{saveError},color=MaterialTheme.colorScheme.error)}
    }
    if(positionChoice)ChoiceDialog("规则位置",priority.toString(),listOf("true" to "前置 · 在应用策略之前","false" to "后置 · 在应用策略之后"),{positionChoice=false}){priority=it.toBoolean()}
    if(choose)TargetPicker("出站",target,data,{choose=false},{target=it})
    if(apps)AppsEditor(packages.lines().filter{it.isNotBlank()}.toSet(),{apps=false}){packages=it.joinToString("\n");apps=false}
    if(editAdvanced)TextEditPage(TextEditor("高级匹配",listOf("匹配参数 JSON" to advanced)){JSONObject(it[0]);advanced=it[0]}){editAdvanced=false}
    if(editRouteField.isNotBlank()) {
        val title=editRouteField;val value=editors[title].orEmpty();val save=editorSaves[title]
        TextEditPage(TextEditor(title,listOf(title to value)){values->
            val key=when(title){"目标端口"->"port";"来源端口"->"source_port";"网络"->"network";else->""}
            if(key.isNotBlank() && values[0].isNotBlank())com.zane.zanebox.config.ConfigBuilder.normalizeAdvanced(JSONObject().put(key,values[0]))
            if(title=="自定义规则" && values[0].isNotBlank())JSONObject(values[0])
            save?.invoke(values[0]);editRouteField=""
        }){editRouteField=""}
    }
}

@Composable internal fun MergeEditor(merge:MergeGroup?,data:AppData,onDismiss:()->Unit,saveError:String="",saving:Boolean=false,onSave:(MergeGroup)->Unit) {
    var name by remember{mutableStateOf(merge?.name ?:"")};var mode by remember{mutableStateOf(merge?.mode ?:"selector")}
    var nodes by remember{mutableStateOf(merge?.nodeIds?.toSet() ?:emptySet())};var groups by remember{mutableStateOf(merge?.groupIds?.toSet() ?:emptySet())}
    var selected by remember{mutableLongStateOf(merge?.selectedId ?:0L)};var choose by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")}
    UiPageList(if(merge==null)"添加节点汇总组" else "编辑节点汇总组",{if(!saving)onDismiss()},action={TextButton(enabled=!saving,onClick={
        if(name.isBlank())error="请输入汇总组名称" else if(nodes.isEmpty() && groups.isEmpty())error="请选择分组或节点" else onSave(MergeGroup(merge?.id ?:0,name,nodes.toList(),groups.toList(),mode,selected))
    },modifier=Modifier.testTag("merge_save")){Text(uiText("保存"))}}) {
        item{OutlinedTextField(name,{name=it},label={Text(uiText("汇总组名称"))},modifier=Modifier.fillMaxWidth().testTag("merge_name"))}
        item{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilterChip(mode=="selector",{mode="selector"},label={Text(uiText("手动选择"))});FilterChip(mode=="urltest",{mode="urltest"},label={Text(uiText("自动测速"))})}}
        item{UiCard{UiRow("默认节点",data.nodes.firstOrNull{it.id==selected}?.name ?:"自动选择",onClick={choose=true})}}
        item{UiSection("订阅分组")}
        items(data.groups,key={"g${it.id}"}){g->UiCard{UiRow(g.name,modifier=Modifier.testTag("merge_group_${g.id}"),onClick={groups=if(g.id in groups)groups-g.id else groups+g.id},trailing={Checkbox(g.id in groups,{groups=if(it)groups+g.id else groups-g.id})})}}
        item{UiSection("指定节点")}
        items(data.nodes,key={"n${it.id}"}){n->UiCard{UiRow(n.name,modifier=Modifier.testTag("merge_node_${n.id}"),onClick={nodes=if(n.id in nodes)nodes-n.id else nodes+n.id},trailing={Checkbox(n.id in nodes,{nodes=if(it)nodes+n.id else nodes-n.id})})}}
        if(error.isNotBlank() || saveError.isNotBlank())item{Text(error.ifBlank{saveError},color=MaterialTheme.colorScheme.error)}
    }
    if(choose)ChoiceDialog("默认节点",selected.toString(),listOf("0" to "自动选择")+data.nodes.filter{it.id in nodes || it.groupId in groups}.map{it.id.toString() to it.name},{choose=false}){selected=it.toLong()}
}

private data class InstalledApp(val name:String,val packageName:String,val system:Boolean,val uid:Int)

private val installedAppIconCache=object: LruCache<String,Bitmap>(2*1024*1024) {
    override fun sizeOf(key:String,value:Bitmap)=value.byteCount
}

private fun loadInstalledAppIcon(context:android.content.Context,packageName:String,sizePx:Int):Bitmap? = runCatching {
    context.packageManager.getApplicationIcon(packageName).let { drawable ->
        Bitmap.createBitmap(sizePx,sizePx,Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.setBounds(0,0,bitmap.width,bitmap.height)
            drawable.draw(Canvas(bitmap))
        }
    }
}.getOrNull()

@Composable private fun InstalledAppIcon(packageName:String) {
    val context=LocalContext.current
    val sizePx=with(LocalDensity.current){40.dp.roundToPx().coerceAtLeast(1)}
    val cacheKey="$packageName@$sizePx"
    val bitmap by produceState<Bitmap?>(installedAppIconCache.get(cacheKey),cacheKey) {
        if(value==null) value=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            installedAppIconCache.get(cacheKey) ?: loadInstalledAppIcon(context,packageName,sizePx)?.also { installedAppIconCache.put(cacheKey,it) }
        }
    }
    Box(Modifier.size(40.dp).testTag("app_icon_$packageName"),contentAlignment=Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(),null,Modifier.fillMaxSize().testTag("app_icon_loaded_$packageName")) }
            ?: Icon(Icons.Outlined.Apps,null,Modifier.size(24.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun InstalledAppRow(app:InstalledApp,selected:Boolean,onSelected:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().testTag("app_${app.packageName}").clickable{onSelected(!selected)}.heightIn(min=68.dp).padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
        InstalledAppIcon(app.packageName)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(uiText(app.name),fontSize=13.sp,lineHeight=18.sp,fontWeight=FontWeight.SemiBold)
            Text(uiText(app.packageName),fontSize=11.sp,lineHeight=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        Checkbox(selected,onCheckedChange=onSelected)
    }
}

internal fun autoProxyPackages(installed:Map<String,Int>,names:Set<String>,bypass:Boolean):Set<String> = installed.filter { (name,uid)->(name in names || uid==1000)!=bypass }.keys

@Composable internal fun AppsEditor(initial:Set<String>,onDismiss:()->Unit,modeValue:String="off",onMode:((String)->Unit)?=null,save:(Set<String>)->Unit) {
    var selected by remember{mutableStateOf(initial)};var query by remember{mutableStateOf("")};var showSystem by remember{mutableStateOf(false)};var mode by remember(modeValue){mutableStateOf(modeValue)}
    var auto by remember { mutableStateOf(false) };var note by remember { mutableStateOf("") }
    val context=LocalContext.current
    val clipboard=context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val apps by produceState<List<InstalledApp>>(emptyList()) {
        value=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            context.packageManager.getInstalledApplications(0).map {
                InstalledApp(context.packageManager.getApplicationLabel(it).toString(),it.packageName,(it.flags and ApplicationInfo.FLAG_SYSTEM)!=0,it.uid)
            }.sortedBy{it.name}
        }
    }
    val visible=apps.filter{app->(showSystem || !app.system) && (query.isBlank() || app.name.contains(query,true) || app.packageName.contains(query,true))}
    val visiblePackages=visible.map{it.packageName}.toSet()
    fun chooseMode(value:String){mode=value;onMode?.invoke(value)}
    UiPageList("选择应用",onDismiss,action={TextButton(onClick={save(selected)},modifier=Modifier.testTag("apps_save")){Text(uiText("保存"))}}) {
        item{UiCard{
            UiRow("已选择 ${selected.size} 个应用",note,modifier=Modifier.testTag("apps_selection_count"))
            if(onMode!=null)UiRow("自动选择代理应用",if(apps.isEmpty())"正在加载应用列表" else "按 AnyBox 内置名单与当前代理模式替换选择",onClick=if(apps.isEmpty())null else ({auto=true}),modifier=Modifier.testTag("apps_auto"))
            if(onMode!=null) { UiSection("应用代理模式")
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                listOf("off" to "关闭","include" to "代理","exclude" to "绕过").forEach{(value,label)->FilterChip(mode==value,{chooseMode(value)},label={Text(uiText(label))},modifier=Modifier.testTag("apps_mode_$value"))}
            }}
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.End){
                TextButton(onClick={selected=selected.toMutableSet().apply{visiblePackages.forEach{pkg->if(!add(pkg))remove(pkg)}}.toSet()},modifier=Modifier.testTag("apps_invert")){Text(uiText("反选"))}
                TextButton(onClick={selected=emptySet()},modifier=Modifier.testTag("apps_clear")){Text(uiText("清空"))}
                TextButton(onClick={clipboard?.setPrimaryClip(ClipData.newPlainText("zanebox-apps",selected.sorted().joinToString("\n")))},modifier=Modifier.testTag("apps_copy")){Text(uiText("复制"))}
                TextButton(onClick={
                    val text=clipboard?.primaryClip?.let{clip->if(clip.itemCount>0)clip.getItemAt(0).coerceToText(context).toString() else ""}.orEmpty()
                    val valid=Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*")
                    selected=selected+(text.split(Regex("[\\s,;]+" )).map{it.trim()}.filter{it.matches(valid)})
                },modifier=Modifier.testTag("apps_import")){Text(uiText("导入"))}
            }
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp)){Text(uiText("显示系统应用"),Modifier.weight(1f));UiSwitch(showSystem,{showSystem=it},modifier=Modifier.testTag("apps_system"))}
        }}
        item{OutlinedTextField(query,{query=it},label={Text(uiText("搜索名称或包名"))},modifier=Modifier.fillMaxWidth().testTag("apps_search"))}
        items(visible,key={it.packageName}){app->UiCard{InstalledAppRow(app,app.packageName in selected){checked->selected=if(checked)selected+app.packageName else selected-app.packageName}}}
    }
    if(auto)UiAlertDialog(onDismissRequest={auto=false},title={Text("自动选择代理应用？")},text={Text("将按内置名单选择已安装应用，并替换当前选择。")},confirmButton={TextButton(onClick={
        runCatching { context.assets.open("proxy_packagename.txt").bufferedReader().use { it.readLines() }.map { it.trim() }.toSet() }.onSuccess { names->selected=autoProxyPackages(apps.associate { it.packageName to it.uid },names,mode=="exclude");note="已自动选择 ${selected.size} 个已安装应用" }.onFailure { note="自动选择失败，原选择已保留" };auto=false
    },modifier=Modifier.testTag("apps_auto_confirm")){Text("选择")}},dismissButton={TextButton(onClick={auto=false}){Text("取消")}})
}

@Composable internal fun NodeInfo(node:Node,data:AppData,vm:AppViewModel,onDismiss:()->Unit) {
    val testing by vm.service.testingNodes.collectAsStateWithLifecycle()
    val current=data.nodes.firstOrNull { it.id==node.id } ?: node
    val o=JSONObject(current.outbound)
    UiAlertDialog(onDismissRequest=onDismiss,title={Text(uiText("节点详情"))},text={
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max=420.dp).testTag("node_info_list")) {
            items(listOf("名称" to current.name,"协议" to o.optString("type"),"地址" to o.optString("server"),"端口" to o.optInt("server_port").takeIf{it>0}?.toString().orEmpty(),"分组" to (data.groups.firstOrNull{it.id==current.groupId}?.name ?:""),"最近测试" to if(node.id in testing)"测试中" else nodeTestLabel(current))){(key,value)->UiRow(key,value)}
        }
    },confirmButton={TextButton(onClick=onDismiss){Text(uiText("关闭"))}},dismissButton={TextButton(onClick={vm.service.testNodes(listOf(node.id))},enabled=data.groups.any{it.id==node.groupId && it.enabled}){Text(uiText("测试延迟"))}})
}
