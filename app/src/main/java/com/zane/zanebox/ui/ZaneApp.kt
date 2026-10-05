@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.zane.zanebox.ui
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zane.zanebox.data.*
import org.json.JSONObject
private data class Editor(val title:String,val fields:List<Pair<String,String>>,val save:(List<String>)->Unit)
@Composable fun ZaneApp(vm: AppViewModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val runtime by vm.service.snapshot.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val ip by vm.ip.collectAsStateWithLifecycle()
    var now by remember {
        mutableLongStateOf(android.os.SystemClock.elapsedRealtime())
    }
    LaunchedEffect(runtime.started,runtime.state) {
        while(runtime.state==2) {
            now=android.os.SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val context=LocalContext.current
    var page by remember {
        mutableIntStateOf(0)
    }
    var editor by remember {
        mutableStateOf<Editor?>(null)
    }
    var search by remember {
        mutableStateOf("")
    }
    var collapsed by remember {
        mutableStateOf(emptySet<Long>())
    }
    var confirm by remember {
        mutableStateOf<Pair<String,()->Unit>?>(null)
    }
    var showApps by remember {
        mutableStateOf(false)
    }
    var showLogs by remember {
        mutableStateOf(false)
    }
    var showDav by remember {
        mutableStateOf(false)
    }
    var showTraffic by remember {
        mutableStateOf(false)
    }
    var showConnections by remember {
        mutableStateOf(false)
    }
    var showPanel by remember { mutableStateOf(false) }
    var showTools by remember { mutableStateOf(false) }
    var subscriptionOptions by remember { mutableStateOf<Group?>(null) }
    var qr by remember {
        mutableStateOf<String?>(null)
    }
    var speedNode by remember {
        mutableStateOf<Long?>(null)
    }
    val snackbar=remember {
        SnackbarHostState()
    }
    LaunchedEffect(message) {
        if(message.isNotBlank()) {
            snackbar.showSnackbar(message)
            vm.message.value=""
        }
    }
    val vpnPermission=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if(it.resultCode==android.app.Activity.RESULT_OK) vm.service.start() else vm.message.value="VPN 授权未批准"
    }
    val scan=rememberLauncherForActivityResult(com.journeyapps.barcodescanner.ScanContract()) {
        result -> result.contents?.let {
            vm.importText(it,data.browseGroupId)
        }
    }
    val importFile=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(vm::readText)
    }
    val exportFile=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
        it?.let(vm::export)
    }
    val backupFile=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
        it?.let(vm::backup)
    }
    val restoreFile=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(vm::restore)
    }
    fun form(title:String,vararg fields:Pair<String,String>,save:(List<String>)->Unit) {
        editor=Editor(title,fields.toList(),save)
    }
    fun toggleConnection() {
        if (runtime.state == 2 || runtime.state == 1) vm.service.stop()
        else if (runtime.state != 3) {
            if (data.setting("serviceMode", "vpn") == "proxy") vm.service.start()
            else {
                val permission = android.net.VpnService.prepare(context)
                if (permission == null) vm.service.start() else vpnPermission.launch(permission)
            }
        }
    }
    fun share(text:String) {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),"分享节点"))
    }
    fun nodeForm(n:Node?=null) {
        form(if(n==null) "添加手动节点" else "编辑节点","名称" to (n?.name ?: "新节点"),"sing-box outbound JSON" to (n?.outbound ?: "{\"type\":\"socks\",\"server\":\"\",\"server_port\":1080}"),"分享链接（可选）" to (n?.shareLink ?: "")) {
            v ->
            runCatching {
                JSONObject(v[1])
                require(v[0].isNotBlank())
            }.onFailure {
                vm.message.value="请输入名称和有效 outbound JSON"
            }.onSuccess {
                vm.edit {
                    d -> val group=n?.groupId ?: d.browseGroupId.takeIf {
                        id -> d.groups.any {
                            it.id==id
                        }
                    } ?: d.groups.filter { it.enabled }.minByOrNull { it.order }?.id ?: vm.store.nextId()
                    val value=(n ?: Node(vm.store.nextId(),group,v[0],v[1],order=d.nodes.size)).copy(name=v[0],outbound=v[1],shareLink=v[2])
                    d.copy(groups=if(d.groups.any {
                        it.id==group
                    }) d.groups else d.groups+Group(group,"手动节点"),nodes=d.nodes.filter {
                        it.id!=value.id
                    }+value)
                }
            }
        }
    }
    fun protocolForm(n:Node?=null) {
        val original=runCatching {
            JSONObject(n?.outbound ?: "{}")
        }.getOrDefault(JSONObject())
        val tls=original.optJSONObject("tls")
        form("节点协议字段","名称" to (n?.name ?: "新节点"),"协议（vless/vmess/trojan/shadowsocks/socks/http/hysteria2/tuic/anytls）" to original.optString("type","vless"),"服务器" to original.optString("server"),"端口" to original.optInt("server_port",443).toString(),"UUID / 用户名" to original.optString("uuid",original.optString("username")),"密码" to original.optString("password"),"TLS true/false" to (tls?.optBoolean("enabled",false) ?: false).toString(),"TLS SNI" to (tls?.optString("server_name") ?: ""),"Shadowsocks 加密方法" to original.optString("method","aes-128-gcm")) {
            v ->
            runCatching {
                require(v[0].isNotBlank() && v[2].isNotBlank())
                val port=v[3].toInt()
                require(port in 1..65535)
                val enabled=v[6].toBooleanStrict()
                val outbound=JSONObject(original.toString()).put("type",v[1]).put("server",v[2]).put("server_port",port)
                if(v[1] in listOf("vless","vmess","tuic")) outbound.put("uuid",v[4]) else if(v[4].isNotBlank())outbound.put("username",v[4])
                if(v[5].isNotBlank())outbound.put("password",v[5])
                if(v[1]=="shadowsocks")outbound.put("method",v[8])
                if(enabled || tls!=null)outbound.put("tls",JSONObject(tls?.toString() ?: "{}").put("enabled",enabled).put("server_name",v[7]))
                vm.edit {
                    d -> val group=n?.groupId ?: d.browseGroupId.takeIf{
                        id->d.groups.any{
                            it.id==id
                        }
                    } ?: d.groups.filter { it.enabled }.minByOrNull { it.order }?.id ?: vm.store.nextId()
                    val value=(n ?: Node(vm.store.nextId(),group,v[0],outbound.toString(),order=d.nodes.size)).copy(name=v[0],outbound=outbound.toString(),shareLink="")
                    d.copy(groups=if(d.groups.any{
                        it.id==group
                    })d.groups else d.groups+Group(group,"手动节点"),nodes=d.nodes.filter{
                        it.id!=value.id
                    }+value)
                }
            }.onFailure {
                vm.message.value="字段无效：${it.message}"
            }
        }
    }
    fun groupForm(g:Group?=null) {
        form("订阅 / 节点组","名称" to (g?.name ?: "新订阅"),"订阅 URL（手动组可留空）" to (g?.subscriptionUrl ?: ""),"前置代理节点 ID（0 无）" to (g?.frontProxy?.toString() ?: "0"),"落地代理节点 ID（0 无）" to (g?.landingProxy?.toString() ?: "0"),"组高级选项 JSON" to (g?.options ?: "{}")) {
            v ->
            runCatching {
                require(v[0].isNotBlank())
                JSONObject(v[4])
                val value=(g ?: Group(vm.store.nextId(),v[0])).copy(name=v[0],subscriptionUrl=v[1],frontProxy=v[2].toLong(),landingProxy=v[3].toLong(),options=v[4])
                vm.saveGroup(value,fetch=v[1].isNotBlank() && (g==null || g.subscriptionUrl!=v[1]))
            }.onFailure{
                vm.message.value="名称、节点 ID 或 JSON 无效"
            }
        }
    }
    fun ruleForm(r:RouteRule?=null) {
        form("分流规则","名称" to (r?.name ?: "新规则"),"域名（每行一个）" to (r?.domains ?: ""),"应用包名（每行一个）" to (r?.packages ?: ""),"IP CIDR（每行一个）" to (r?.ipCidrs ?: ""),"目标：proxy/direct/block/node:ID/group:ID/merge:ID" to (r?.outbound ?: "proxy"),"高级匹配 JSON（network/port/source/source_port/rule_set/protocol 等）" to (r?.advanced ?: "{}"),"优先于智能分流 true/false" to (r?.prioritize?.toString() ?: "false")) {
            v -> vm.edit {
                d -> val value=(r ?: RouteRule(vm.store.nextId(),v[0])).copy(name=v[0],domains=v[1],packages=v[2],ipCidrs=v[3],outbound=v[4],advanced=JSONObject(v[5]).toString(),prioritize=v[6].toBooleanStrict())
                d.copy(rules=d.rules.filter {
                    it.id!=value.id
                }+value)
            }
        }
    }
    fun mergeForm(m:MergeGroup?=null) {
        form("合并节点组","名称" to (m?.name ?: "新合并组"),"模式 selector / urltest" to (m?.mode ?: "selector"),"节点 ID（逗号分隔）" to (m?.nodeIds?.joinToString(",") ?: ""),"节点组 ID（逗号分隔）" to (m?.groupIds?.joinToString(",") ?: ""),"默认节点 ID" to (m?.selectedId?.toString() ?: "0")) {
            v ->
            runCatching {
                require(v[1] in listOf("selector","urltest"))
                val ids:(String)->List<Long> ={
                    s -> s.split(',', '\n').filter {
                        it.isNotBlank()
                    }.map {
                        it.trim().toLong()
                    }
                }
                val value=MergeGroup(m?.id ?: vm.store.nextId(),v[0],ids(v[2]),ids(v[3]),v[1],v[4].toLong())
                vm.edit {
                    it.copy(merges=it.merges.filter {
                        old -> old.id!=value.id
                    }+value)
                }
            }.onFailure {
                vm.message.value="模式或 ID 无效"
            }
        }
    }
    val dark=when(data.setting("theme","system")) {
        "dark"->true
        "light"->false
        else->isSystemInDarkTheme()
    }
    val originalDensity=LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(originalDensity.density,data.setting("fontScale","1.0").toFloatOrNull()?.coerceIn(0.5f,2f) ?: 1f)) {
        MaterialTheme(colorScheme=if(dark) darkColorScheme(primary=Color(0xff2675e8)) else lightColorScheme(primary=Color(0xff2675e8),background=Color(0xfff5f7fb),surface=Color.White)) {
            Scaffold(topBar={
                TopAppBar(title={
                    Text("zanebox")
                },actions={
                    if(busy) CircularProgressIndicator(Modifier.size(24.dp))
                    TextButton(onClick={
                        vm.service.reload()
                    },modifier=Modifier.testTag("apply_changes")) {
                        Text("应用修改")
                    }
                })
            },snackbarHost={
                SnackbarHost(snackbar)
            },bottomBar={
                HomeToolbar(
                    page = page,
                    state = runtime.state,
                    elapsedSeconds = if(runtime.state == 2 && runtime.started > 0) ((now-runtime.started).coerceAtLeast(0)/1000) else 0,
                    onPage = { page = it },
                    onToggle = { page = 0; toggleConnection() }
                )
            }) {
                padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal=16.dp).testTag("page_list"),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(vertical=12.dp)) {
                    if(page==0) {
                        item {
                            Panel {
                                Text(listOf("已停止","连接中","已连接","停止中","连接失败").getOrElse(runtime.state){
                                    "未知状态"
                                },style=MaterialTheme.typography.titleLarge,modifier=Modifier.testTag("connection_state"))
                                Text("↑ ${bytes(runtime.txRate)}/s   ↓ ${bytes(runtime.rxRate)}/s")
                                Text("上传 ${bytes(runtime.txTotal)} · 下载 ${bytes(runtime.rxTotal)}")
                                if(runtime.state==2 && runtime.started>0) Text("连接时长 ${((now-runtime.started).coerceAtLeast(0)/1000)} 秒")
                                if(runtime.error.isNotBlank()) Text(runtime.error,color=MaterialTheme.colorScheme.error)
                                if(runtime.state==2) Text("配置更改后，请点击右上方应用修改。",style=MaterialTheme.typography.bodySmall)
                                Row {
                                    TextButton(onClick=vm::refreshIp,modifier=Modifier.testTag("exit_ip")){
                                        Text(ip.ifBlank{
                                            "查询出口 IP"
                                        })
                                    }
                                }
                            }
                        }
                        item {
                            LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                item {
                                    FilterChip(data.browseGroupId==0L,{
                                        vm.setting("browseGroupId","0")
                                    },label={
                                        Text("全部节点")
                                    },modifier=Modifier.testTag("group_all"))
                                }
                                items(data.groups.sortedBy{
                                    it.order
                                },key={
                                    it.id
                                }) {
                                    g -> FilterChip(data.browseGroupId==g.id,{
                                        vm.setting("browseGroupId",g.id.toString())
                                    },label={
                                        Text(g.name)
                                    },modifier=Modifier.testTag("group_${g.id}"))
                                }
                            }
                        }
                        item {
                            OutlinedTextField(search,{
                                search=it
                            },label={
                                Text("搜索节点")
                            },modifier=Modifier.fillMaxWidth().testTag("node_search"))
                        }
                        item {
                            FlowActions(listOf("扫码" to {
                                scan.launch(com.journeyapps.barcodescanner.ScanOptions().setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE).setPrompt("扫描节点二维码").setBeepEnabled(false))
                            },"导入文本" to {
                                form("导入文本","链接 / Base64 / Clash / sing-box" to "") {
                                    vm.importText(it[0],data.browseGroupId)
                                }
                            },"剪贴板" to {
                                val cb=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                vm.importText(cb.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty(),data.browseGroupId)
                            },"导入文件" to {
                                importFile.launch(arrayOf("*/*"))
                            },"导入 URL" to {
                                groupForm()
                            },"手动节点" to {
                                protocolForm()
                            },"添加组" to {
                                groupForm()
                            },"导出" to {
                                exportFile.launch("zanebox-nodes.txt")
                            },"全部测速" to {
                                vm.service.testNodes(data.nodes.map{
                                    it.id
                                })
                            },"取消测速" to {
                                vm.service.cancelTests()
                            }))
                        }
                        item {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                listOf("rule" to "规则","global" to "全局","direct" to "直连").forEach {
                                    (key,label) -> FilterChip(selected=data.setting("routeMode","rule")==key,onClick={
                                        vm.setting("routeMode",key)
                                    },label={
                                        Text(label)
                                    })
                                }
                            }
                        }
                        data.groups.filter {
                            data.browseGroupId==0L || it.id==data.browseGroupId
                        }.sortedBy {
                            it.order
                        }.forEach {
                            g ->
                            item(key="group${g.id}") {
                                Panel {
                                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                        Text("${g.name} · ${data.nodes.count { it.groupId==g.id }}",Modifier.weight(1f).clickable {
                                            vm.setting("browseGroupId",g.id.toString())
                                            collapsed=if(g.id in collapsed) collapsed-g.id else collapsed+g.id
                                        },style=MaterialTheme.typography.titleMedium)
                                        Switch(g.enabled,{
                                            value->vm.edit{
                                                d->d.copy(groups=d.groups.map{
                                                    if(it.id==g.id)it.copy(enabled=value)else it
                                                })
                                            }
                                        },modifier=Modifier.semantics{
                                            contentDescription="启用组 ${g.name}"
                                        })
                                    }
                                    val sortMode=nodeSortMode(data,g)
                                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                        FilterChip(selected=sortMode==NodeSortMode.DEFAULT,onClick={vm.setting("sort_group_${g.id}","false")},label={Text("默认顺序")},modifier=Modifier.testTag("sort_default_${g.id}"))
                                        FilterChip(selected=sortMode==NodeSortMode.LATENCY,onClick={vm.setting("sort_group_${g.id}","true")},label={Text("延迟升序")},modifier=Modifier.testTag("sort_latency_${g.id}"))
                                        FilterChip(selected=sortMode==NodeSortMode.NAME,onClick={vm.edit { d->d.copy(settings=(d.settings-"sort_group_${g.id}")+("sort_mode_group_${g.id}" to "name")) }},label={Text("名称顺序")},modifier=Modifier.testTag("sort_name_${g.id}"))
                                    }
                                    TextButton(onClick={subscriptionOptions=g},modifier=Modifier.testTag("subscription_options_${g.id}")){Text("订阅选项")}
                                    Text("组 ID ${g.id}"+if(g.userInfo.isNotBlank()) " · ${g.userInfo}" else "",style=MaterialTheme.typography.bodySmall)
                                    FlowActions(listOf("编辑" to {
                                        groupForm(g)
                                    },"分享组" to {
                                        share(shareText(data.nodes.filter{
                                            it.groupId==g.id
                                        }))
                                    },"更新" to {
                                        vm.updateGroup(g)
                                    },"测速" to {
                                        vm.service.testNodes(data.nodes.filter{
                                            it.groupId==g.id
                                        }.map{
                                            it.id
                                        })
                                    },"上移" to {
                                        vm.edit {
                                            d -> val ordered=d.groups.sortedBy{
                                                it.order
                                            }.toMutableList()
                                            val i=ordered.indexOfFirst{
                                                it.id==g.id
                                            }
                                            if(i>0) java.util.Collections.swap(ordered,i,i-1)
                                            d.copy(groups=ordered.mapIndexed{
                                                index,x->x.copy(order=index)
                                            })
                                        }
                                    },"删除" to {
                                        confirm="删除组及其节点 ${g.name}？" to {
                                            vm.deleteGroup(g.id)
                                        }
                                    }))
                                }
                            }
                            if(g.id !in collapsed) items(data.nodes.filter {
                                it.groupId==g.id && (search.isBlank() || it.name.contains(search,true))
                            }.sortedWith(nodeComparator(nodeSortMode(data,g))),key={
                                "node${it.id}"
                            }) {
                                n -> Panel {
                                    Row(Modifier.fillMaxWidth().clickable(enabled=g.enabled) {
                                        vm.service.selectNode(n.id)
                                    }.testTag("node_${n.id}"),horizontalArrangement=Arrangement.SpaceBetween) {
                                        Text((if(data.selectedNodeId==n.id) "● " else "○ ")+n.name,Modifier.weight(1f))
                                        Text(if(n.ping>=0) "${n.ping} ms" else if(n.ping == -2) "失败" else "未测试")
                                    }
                                    Text("ID ${n.id} · ${runCatching{JSONObject(n.outbound).optString("type")}.getOrDefault("")}",style=MaterialTheme.typography.bodySmall)
                                    FlowActions(listOf("测速" to {
                                        vm.service.testNodes(listOf(n.id))
                                    },"协议字段" to {
                                        protocolForm(n)
                                    },"高级 JSON" to {
                                        nodeForm(n)
                                    },"分享" to {
                                        share(shareText(listOf(n)))
                                    },"二维码" to {
                                        qr=shareText(listOf(n))
                                    },"速度测试" to {
                                        speedNode=n.id
                                    },"节点区域" to {
                                        form("节点区域","hk/us/kr/jp/sg/tw（留空自动）" to data.setting("nodeRegion.${n.id}","")){
                                            vm.setting("nodeRegion.${n.id}",it[0])
                                        }
                                    },"复制" to {
                                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(n.name,shareText(listOf(n))))
                                        vm.message.value="已复制"
                                    },"上移" to {
                                        vm.edit{
                                            d->val list=d.nodes.filter{
                                                it.groupId==g.id
                                            }.sortedBy{
                                                it.order
                                            }.toMutableList()
                                            val i=list.indexOfFirst{
                                                it.id==n.id
                                            }
                                            if(i>0)java.util.Collections.swap(list,i,i-1)
                                            val order=list.mapIndexed{
                                                index,x->x.id to index
                                            }.toMap()
                                            d.copy(nodes=d.nodes.map{
                                                if(it.id in order)it.copy(order=order.getValue(it.id))else it
                                            })
                                        }
                                    },"删除" to {
                                        confirm="删除节点 ${n.name}？" to {
                                            vm.deleteNode(n.id)
                                        }
                                    }))
                                }
                            }
                        }
                        if(data.nodes.isEmpty()) item {
                            Text("点击导入文本、导入文件或手动节点开始使用。")
                        }
                    }
                    if(page==1) {
                        item {
                            SmartPanel(data,vm) {
                                title,fields,save -> editor=Editor(title,fields,save)
                            }
                        }
                        item {
                            Text("智能分流",style=MaterialTheme.typography.headlineSmall)
                            Text("按顺序匹配规则；应用修改会保留默认节点。")
                        }
                        item {
                            FlowActions(listOf("添加规则" to {
                                ruleForm()
                            },"添加合并组" to {
                                mergeForm()
                            }))
                        }
                        items(data.rules.sortedBy{
                            it.order
                        },key={
                            "rule${it.id}"
                        }) {
                            r -> Panel {
                                Row {
                                    Text(r.name,Modifier.weight(1f))
                                    Switch(r.enabled,{
                                        value->vm.edit{
                                            d->d.copy(rules=d.rules.map{
                                                if(it.id==r.id)it.copy(enabled=value)else it
                                            })
                                        }
                                    })
                                }
                                Text("→ ${r.outbound}\n${listOf(r.domains,r.packages,r.ipCidrs).filter{it.isNotBlank()}.joinToString("\n")}")
                                FlowActions(listOf("编辑" to {
                                    ruleForm(r)
                                },"上移" to {
                                    vm.edit{
                                        d->val l=d.rules.sortedBy{
                                            it.order
                                        }.toMutableList()
                                        val i=l.indexOfFirst{
                                            it.id==r.id
                                        }
                                        if(i>0)java.util.Collections.swap(l,i,i-1)
                                        d.copy(rules=l.mapIndexed{
                                            index,x->x.copy(order=index)
                                        })
                                    }
                                },"删除" to {
                                    vm.edit{
                                        d->d.copy(rules=d.rules.filter{
                                            it.id!=r.id
                                        })
                                    }
                                }))
                            }
                        }
                        items(data.merges,key={
                            "merge${it.id}"
                        }) {
                            m -> Panel {
                                Text("${m.name} · ${m.mode}",style=MaterialTheme.typography.titleMedium)
                                Text("ID ${m.id} · 节点 ${m.nodeIds.joinToString()} · 组 ${m.groupIds.joinToString()}")
                                FlowActions(listOf("编辑" to {
                                    mergeForm(m)
                                },"删除" to {
                                    vm.deleteMerge(m.id)
                                }))
                            }
                        }
                        item {
                            Text("可用节点："+data.nodes.joinToString {
                                "${it.name} (${it.id})"
                            })
                            Text("可用组："+data.groups.joinToString {
                                "${it.name} (${it.id})"
                            })
                        }
                    }
                    if(page==2) {
                        item {
                            Text("设置",style=MaterialTheme.typography.headlineSmall)
                        }
                        item {
                            TextButton(onClick={showTools=true},modifier=Modifier.testTag("network_tools")) {
                                Text("网络工具 · STUN / NAT")
                            }
                        }
                        item {
                            AdvancedSettings(data, vm, runtime.state in 1..3) { title, fields, save ->
                                editor = Editor(title, fields, save)
                            }
                        }
                        listOf("dnsRemote" to "远程 DNS","dnsDirect" to "直连 DNS","tunStack" to "TUN 栈（mixed/system/gvisor）","mtu" to "MTU","mixedPort" to "本地代理端口","sharePort" to "共享端口","apiPort" to "API 端口","testUrl" to "测速 URL","testTimeout" to "测速超时（毫秒）","testConcurrency" to "测速并发","logLevel" to "日志级别","theme" to "主题（system/light/dark）","fontScale" to "字体倍率").forEach {
                            (key,label) -> item {
                                Panel {
                                    Row(Modifier.fillMaxWidth().clickable{
                                        form(label,label to data.setting(key,defaults[key].orEmpty())) {
                                            v-> val numberKeys=setOf("mtu","mixedPort","sharePort","apiPort","testTimeout","testConcurrency")
                                            if(key in numberKeys && (v[0].toIntOrNull() ?: 0)<=0) vm.message.value="请输入有效正整数" else vm.setting(key,v[0])
                                        }
                                    }) {
                                        Column(Modifier.weight(1f)){
                                            Text(label)
                                            Text(data.setting(key,defaults[key].orEmpty()),style=MaterialTheme.typography.bodySmall)
                                        }
                                        Text("›")
                                    }
                                }
                            }
                        }
                        listOf("ipv6" to "IPv6","sniff" to "流量嗅探","allowLan" to "允许局域网","shareEnabled" to "代理分享","clashApi" to "Clash API","autoStart" to "开机启动","networkReset" to "网络变化时重置连接","perAppEnabled" to "分应用代理").forEach {
                            (key,label) -> item {
                                Panel {
                                    Row {
                                        Text(label,Modifier.weight(1f))
                                        Switch(data.bool(key,key=="sniff" || key=="networkReset"),{
                                            vm.setting(key,it.toString())
                                        },Modifier.testTag("setting_$key"))
                                    }
                                }
                            }
                        }
                        item {
                            Panel {
                                Text("分应用模式：${data.setting("perAppMode","exclude")}")
                                FlowActions(listOf("选择应用" to {
                                    showApps=true
                                },"切换包含/排除" to {
                                    vm.setting("perAppMode",if(data.setting("perAppMode","exclude")=="exclude")"include" else "exclude")
                                },"包名编辑" to {
                                    form("分应用包名","每行一个包名" to data.setting("perAppPackages","")){
                                        vm.setting("perAppPackages",it[0])
                                    }
                                }))
                            }
                        }
                        item {
                            Panel {
                                FlowActions(listOf("查看日志" to {
                                    vm.service.refreshLogs()
                                    showLogs=true
                                },"导出备份" to {
                                    backupFile.launch("zanebox-backup.zip")
                                },"恢复备份" to {
                                    restoreFile.launch(arrayOf("*/*"))
                                },"WebDAV 备份" to {
                                    vm.listWebdav()
                                    showDav=true
                                },"流量统计" to {
                                    vm.service.refreshTraffic()
                                    showTraffic=true
                                },"YACD 面板" to {vm.service.openPanel(); showPanel=true},"连接监控" to {
                                    vm.service.refreshConnections()
                                    showConnections=true
                                },"WebDAV 设置" to {
                                    form("WebDAV","服务器 URL" to data.setting("webdavUrl",""),"用户名" to data.setting("webdavUser",""),"密码" to data.setting("webdavPassword","")){
                                        v->vm.edit{
                                            d->d.copy(settings=d.settings+mapOf("webdavUrl" to v[0],"webdavUser" to v[1],"webdavPassword" to v[2]))
                                        }
                                    }
                                }))
                            }
                        }
                    }
                }
                editor?.let {
                    e -> EditDialog(e,onDismiss={
                        editor=null
                    }) {
                        values -> e.save(values)
                        editor=null
                    }
                }
                confirm?.let {
                    c -> AlertDialog(onDismissRequest={
                        confirm=null
                    },title={
                        Text("确认删除")
                    },text={
                        Text(c.first)
                    },confirmButton={
                        TextButton(onClick={
                            c.second()
                            confirm=null
                        }){
                            Text("删除")
                        }
                    },dismissButton={
                        TextButton(onClick={
                            confirm=null
                        }){
                            Text("取消")
                        }
                    })
                }
                val pending by vm.pendingImport.collectAsStateWithLifecycle()
                pending?.let { p -> PendingImportDialog(p,onConfirm=vm::confirmImport,onDismiss=vm::cancelImport) }
                if(showApps) AppsDialog(data.setting("perAppPackages","").lines().toSet(),onDismiss={
                    showApps=false
                }) {
                    vm.setting("perAppPackages",it.joinToString("\n"))
                    showApps=false
                }
                if(showDav) WebdavDialog(vm){
                    showDav=false
                }
                if(showTraffic) TrafficDialog(vm){
                    showTraffic=false
                }
                subscriptionOptions?.let { group -> SubscriptionOptionsDialog(group,vm){subscriptionOptions=null} }
                if(showTools) ToolsPanel(vm) {showTools=false}
                if(showPanel) LocalPanelDialog(vm) {showPanel=false}
        if(showConnections) ConnectionsDialog(vm){
                    showConnections=false
                }
                qr?.let {
                    value -> QrDialog(value,vm){
                        qr=null
                    }
                }
                speedNode?.let {
                    id -> SpeedDialog(vm,id){
                        speedNode=null
                    }
                }
                if(showLogs) {
                    val logs by vm.service.logs.collectAsStateWithLifecycle()
                    AlertDialog(onDismissRequest={
                        showLogs=false
                    },title={
                        Text("运行日志")
                    },text={
                        LazyColumn(Modifier.heightIn(max=440.dp)){
                            items(logs){
                                Text(it,style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    },confirmButton={
                        TextButton(onClick={
                            showLogs=false
                        }){
                            Text("关闭")
                        }
                    },dismissButton={
                        TextButton(onClick={
                            share(logs.joinToString("\n"))
                        }){
                            Text("分享")
                        }
                    })
                }
            }
        }
    }
}
private val defaults=mapOf("dnsRemote" to "https://1.1.1.1/dns-query","dnsDirect" to "local","tunStack" to "mixed","mtu" to "1500","mixedPort" to "2080","sharePort" to "2081","apiPort" to "9090","testUrl" to "https://www.gstatic.com/generate_204","testTimeout" to "10000","testConcurrency" to "4","logLevel" to "info","theme" to "system","fontScale" to "1.0")
@Composable private fun Panel(content:@Composable ColumnScope.()->Unit) {
    Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp),content=content)
    }
}
@Composable private fun FlowActions(actions:List<Pair<String,()->Unit>>) {
    Column {
        actions.chunked(3).forEach {
            chunk -> Row(Modifier.fillMaxWidth()) {
                chunk.forEach {
                    (name,action) -> TextButton(onClick=action,modifier=Modifier.weight(1f).semantics{
                        contentDescription=name
                    }) {
                        Text(name)
                    }
                }
            }
        }
    }
}
@Composable private fun EditDialog(editor:Editor,onDismiss:()->Unit,save:(List<String>)->Unit) {
    var values by remember(editor) {
        mutableStateOf(editor.fields.map{
            it.second
        })
    }
    AlertDialog(onDismissRequest=onDismiss,title={
        Text(editor.title)
    },text={
        LazyColumn(Modifier.heightIn(max=480.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(editor.fields.indices.toList()) {
                i -> OutlinedTextField(values[i],{
                    value->values=values.toMutableList().apply{
                        this[i]=value
                    }
                },label={
                    Text(editor.fields[i].first)
                },visualTransformation=if(editor.fields[i].first.contains("密码")) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,modifier=Modifier.fillMaxWidth().testTag("editor_field_$i"))
            }
        }
    },confirmButton={
        TextButton(onClick={
            save(values)
        },modifier=Modifier.testTag("editor_save")){
            Text("保存")
        }
    },dismissButton={
        TextButton(onClick=onDismiss){
            Text("取消")
        }
    })
}
@Composable private fun PendingImportDialog(pending:PendingImport,onConfirm:()->Unit,onDismiss:()->Unit) {
    val (title,body,action)=when(pending) {
        is PendingImport.Subscription -> Triple("添加外部订阅？","名称：${pending.name}\n地址：${pending.url.take(200)}\n\n确认后会立即下载该订阅。","添加")
        is PendingImport.Nodes -> Triple("导入外部节点？","将导入 ${pending.report.nodes.size} 个节点"+(if(pending.report.skipped>0) "，跳过 ${pending.report.skipped} 条无法识别的内容" else "")+"：\n"+pending.report.nodes.take(5).joinToString("\n") { "· "+it.name.take(60) }+(if(pending.report.nodes.size>5) "\n……" else ""),"导入")
        is PendingImport.Backup -> Triple("用外部备份替换全部数据？","备份含 ${pending.data.groups.size} 个组、${pending.data.nodes.size} 个节点、${pending.data.rules.size} 条规则。\n\n恢复会覆盖当前所有节点、订阅和设置，且无法撤销。","替换")
    }
    AlertDialog(onDismissRequest=onDismiss,title={ Text(title) },text={ Text(body) },confirmButton={
        TextButton(onClick=onConfirm,modifier=Modifier.testTag("import_confirm")) { Text(action) }
    },dismissButton={
        TextButton(onClick=onDismiss,modifier=Modifier.testTag("import_cancel")) { Text("取消") }
    })
}
@Composable private fun AppsDialog(initial:Set<String>,onDismiss:()->Unit,save:(Set<String>)->Unit) {
    val context=LocalContext.current
    var selected by remember {
        mutableStateOf(initial)
    }
    var query by remember {
        mutableStateOf("")
    }
    val apps by produceState<List<Pair<String,String>>>(emptyList()) {
        value=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            context.packageManager.getInstalledApplications(0).map{
                context.packageManager.getApplicationLabel(it).toString() to it.packageName
            }.sortedBy{
                it.first
            }
        }
    }
    AlertDialog(onDismissRequest=onDismiss,title={
        Text("选择应用")
    },text={
        Column {
            OutlinedTextField(query,{
                query=it
            },label={
                Text("搜索名称或包名")
            })
            LazyColumn(Modifier.height(360.dp)) {
                items(apps.filter{
                    query.isBlank() || it.first.contains(query,true) || it.second.contains(query,true)
                },key={
                    it.second
                }) {
                    (name,pkg)->Row(Modifier.fillMaxWidth().clickable{
                        selected=if(pkg in selected)selected-pkg else selected+pkg
                    }) {
                        Checkbox(pkg in selected,{
                            selected=if(it)selected+pkg else selected-pkg
                        })
                        Column{
                            Text(name)
                            Text(pkg,style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    },confirmButton={
        TextButton(onClick={
            save(selected)
        }){
            Text("保存")
        }
    },dismissButton={
        TextButton(onClick=onDismiss){
            Text("取消")
        }
    })
}
private fun bytes(value:Long):String = when {
    value>=1073741824 -> "%.1f GB".format(value/1073741824.0)
    value>=1048576 -> "%.1f MB".format(value/1048576.0)
    value>=1024 -> "%.1f KB".format(value/1024.0)
    else -> "$value B"
}
