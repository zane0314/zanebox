package com.zane.zanebox.ui
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zane.zanebox.data.AppData
import org.json.JSONObject
@Composable internal fun SmartPanel(data:AppData,vm:AppViewModel,form:(String,List<Pair<String,String>>,(List<String>)->Unit)->Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("内建智能分流",style=MaterialTheme.typography.titleMedium)
            Text("目标 off / auto / region:hk/us/kr/jp/sg/tw / group:ID / node:ID / merge:ID",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={
                form("智能分流源合并组",listOf("合并组 ID" to data.setting("smartSourceMergeId","0"))){
                    vm.setting("smartSourceMergeId",it[0])
                }
            }){
                Text("源合并组：${data.setting("smartSourceMergeId","0")}")
            }
            listOf("youtube" to "YouTube","telegram" to "Telegram","netflix" to "Netflix","disney" to "Disney+","tiktok" to "TikTok","x" to "X","meta" to "Meta","spotify" to "Spotify","google" to "Google","ai" to "AI","custom" to "自定义").forEach {
                (key,name) ->
                Text(name+" → "+data.setting("smart.$key.target","off"))
                Row {
                    TextButton(onClick={
                        form(name,listOf("目标" to data.setting("smart.$key.target","off"),"规则源 URL" to data.setting("smartUrl.$key",""),"规则列表" to data.setting("smartRules.$key",""))){
                            v->vm.edit{
                                d->d.copy(settings=d.settings+mapOf("smart.$key.target" to v[0],"smartUrl.$key" to v[1],"smartRules.$key" to v[2]))
                            }
                        }
                    },modifier=Modifier.testTag("smart_$key")){
                        Text("编辑")
                    }
                    TextButton(onClick={
                        vm.updateSmart(key)
                    },enabled=data.setting("smartUrl.$key").isNotBlank()){
                        Text("更新列表")
                    }
                }
            }
        }
    }
}
@Composable
internal fun WebdavDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val entries by vm.webdavEntries.collectAsStateWithLifecycle()
    var pending by remember {
        mutableStateOf<com.zane.zanebox.backup.WebDavEntry?>(null)
    }
    AlertDialog(
    onDismissRequest = onDismiss,
    title = {
        Text("WebDAV 备份")
    },
    text = {
        Column {
            Row {
                TextButton(onClick = {
                    vm.listWebdav()
                }) {
                    Text("刷新")
                }
                TextButton(onClick = {
                    vm.uploadWebdav()
                }) {
                    Text("上传备份")
                }
            }
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(entries, key = {
                    it.href
                }) {
                    entry ->
                    Column {
                        Text(entry.name)
                        Text("${entry.size} 字节 · ${entry.modified}", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = {
                                pending = entry
                            }) {
                                Text("恢复")
                            }
                            TextButton(onClick = {
                                vm.deleteWebdav(entry)
                            }) {
                                Text("删除云备份")
                            }
                        }
                    }
                }
            }
        }
    },
    confirmButton = {
        TextButton(onClick = onDismiss) {
            Text("关闭")
        }
    }
    )
    pending?.let {
        entry ->
        AlertDialog(
        onDismissRequest = {
            pending = null
        },
        title = {
            Text("恢复备份？")
        },
        text = {
            Text("将用 ${entry.name} 替换当前配置。")
        },
        confirmButton = {
            TextButton(onClick = {
                vm.restoreWebdav(entry)
                pending = null
            }) {
                Text("恢复")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                pending = null
            }) {
                Text("取消")
            }
        }
        )
    }
}
@Composable internal fun TrafficDialog(vm:AppViewModel,onDismiss:()->Unit) {
    val traffic by vm.service.traffic.collectAsStateWithLifecycle()
    val root=runCatching{
        JSONObject(traffic)
    }.getOrDefault(JSONObject())
    AlertDialog(onDismissRequest=onDismiss,title={
        Text("累计流量统计")
    },text={
        Column {
            Row {
                TextButton(onClick={
                    vm.service.refreshTraffic()
                }){
                    Text("刷新")
                }
                TextButton(onClick={
                    vm.service.resetTraffic()
                }){
                    Text("清零")
                }
                TextButton(onClick={
                    vm.service.setTrafficEnabled(true)
                }){
                    Text("开启统计")
                }
                TextButton(onClick={
                    vm.service.setTrafficEnabled(false)
                }){
                    Text("停止统计")
                }
            }
            LazyColumn(Modifier.heightIn(max=400.dp)){
                listOf("apps" to "应用","domains" to "域名","nodes" to "节点").forEach {
                    (key,title)->item{
                        Text(title,style=MaterialTheme.typography.titleMedium)
                    }
                    val array=root.optJSONArray(key)
                    items(array?.length() ?: 0){
                        i->val item=array!!.getJSONObject(i)
                        Text("${item.optString("name")} ↑ ${item.optLong("tx")} B ↓ ${item.optLong("rx")} B")
                    }
                }
            }
        }
    },confirmButton={
        TextButton(onClick=onDismiss){
            Text("关闭")
        }
    })
}
@Composable internal fun ConnectionsDialog(vm:AppViewModel,onDismiss:()->Unit) {
    val connections by vm.service.connections.collectAsStateWithLifecycle()
    val array=runCatching{
        JSONObject(connections).optJSONArray("connections")
    }.getOrNull()
    AlertDialog(onDismissRequest=onDismiss,title={
        Text("连接监控")
    },text={
        Column {
            Row {
                TextButton(onClick={
                    vm.service.refreshConnections()
                }){
                    Text("刷新")
                }
                TextButton(onClick={
                    vm.service.closeAllConnections()
                }){
                    Text("关闭全部")
                }
            }
            LazyColumn(Modifier.heightIn(max=400.dp)) {
                items(array?.length() ?: 0) {
                    i -> val value=array!!.getJSONObject(i)
                    Text(value.optJSONObject("metadata")?.let{
                        it.optString("host").ifBlank{
                            it.optString("destinationIP")
                        }
                    } ?: value.toString())
                    TextButton(onClick={
                        vm.service.closeConnection(value.optString("id"))
                    }){
                        Text("关闭连接")
                    }
                }
            }
        }
    },confirmButton={
        TextButton(onClick=onDismiss){
            Text("关闭")
        }
    })
}
@Composable internal fun QrDialog(text:String,vm:AppViewModel,onDismiss:()->Unit) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null,text) {
        value=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                val matrix=com.google.zxing.MultiFormatWriter().encode(text,com.google.zxing.BarcodeFormat.QR_CODE,768,768)
                android.graphics.Bitmap.createBitmap(768,768,android.graphics.Bitmap.Config.ARGB_8888).apply {
                    val pixels=IntArray(768*768) {
                        i->if(matrix[i%768,i/768])android.graphics.Color.BLACK else android.graphics.Color.WHITE
                    }
                    setPixels(pixels,0,768,0,0,768,768)
                }
            }.getOrNull()
        }
    }
    val save=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("image/png")) {
        uri-> if(uri!=null && bitmap!=null) vm.saveQr(uri,bitmap!!)
    }
    AlertDialog(onDismissRequest=onDismiss,title={
        Text("节点二维码")
    },text={
        Column {
            bitmap?.let {
                androidx.compose.foundation.Image(it.asImageBitmap(),"节点分享二维码",Modifier.size(280.dp))
            } ?: Text("内容过长，无法生成二维码；请使用节点分享。")
            Text(text.take(200),style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={
                bitmap?.let(vm::shareQr)
            },enabled=bitmap!=null){
                Text("分享二维码")
            }
        }
    },confirmButton={
        TextButton(onClick=onDismiss){
            Text("关闭")
        }
    },dismissButton={
        TextButton(onClick={
            save.launch("zanebox-qr.png")
        },enabled=bitmap!=null){
            Text("保存图片")
        }
    })
}
@Composable internal fun SpeedDialog(vm:AppViewModel,nodeId:Long,onDismiss:()->Unit) {
    val result by vm.service.speedResult.collectAsStateWithLifecycle()
    AlertDialog(onDismissRequest=onDismiss,title={
        Text("节点速度测试")
    },text={
        Column {
            listOf("simple" to "简单下载","download" to "下载","upload" to "上传","full" to "下载 + 上传").forEach {
                (mode,label)->Button(onClick={
                    vm.service.speedTest(nodeId,mode)
                },modifier=Modifier.fillMaxWidth().testTag("speed_$mode")){
                    Text(label)
                }
            }
            Text(result.ifBlank{
                "选择测速模式；测试会产生实际流量。"
            })
        }
    },confirmButton={
        TextButton(onClick=onDismiss){
            Text("关闭")
        }
    },dismissButton={
        TextButton(onClick={
            vm.service.cancelSpeedTest()
        }){
            Text("取消测速")
        }
    })
}

@Composable
internal fun SubscriptionOptionsDialog(group:com.zane.zanebox.data.Group,vm:AppViewModel,onDismiss:()->Unit) {
    val original=remember(group.id,group.options){runCatching{JSONObject(group.options)}.getOrDefault(JSONObject())}
    var auto by remember(group.id){mutableStateOf(original.optBoolean("autoUpdate",false))}
    var minutes by remember(group.id){mutableStateOf(original.optInt("autoUpdateDelay",1440).toString())}
    var connectedOnly by remember(group.id){mutableStateOf(original.optBoolean("updateWhenConnectedOnly",false))}
    var dedup by remember(group.id){mutableStateOf(original.optBoolean("deduplication",false))}
    var resolve by remember(group.id){mutableStateOf(original.optBoolean("forceResolve",false))}
    var agent by remember(group.id){mutableStateOf(original.optString("customUserAgent"))}
    var mode by remember(group.id){mutableIntStateOf(original.optInt("filterMode",0))}
    var regex by remember(group.id){mutableStateOf(original.optString("filterRegex"))}
    var resolver by remember(group.id){mutableStateOf(original.optString("serverDnsResolver"))}
    var error by remember {mutableStateOf("")}
    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text("订阅选项 · ${group.name}")},
        text={
            LazyColumn(Modifier.heightIn(max=480.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(group.subscriptionUrl.isBlank()) item {Text("此组暂无订阅 URL；在组编辑中设置 URL 后选项生效。",style=MaterialTheme.typography.bodySmall)}
                item {OptionSwitch("自动更新",auto,{auto=it},"subscription_auto_update")}
                item {OutlinedTextField(minutes,{minutes=it},label={Text("更新间隔（分钟）")},modifier=Modifier.fillMaxWidth().testTag("subscription_interval"))}
                item {OptionSwitch("仅连接时更新",connectedOnly,{connectedOnly=it},"subscription_connected_only")}
                item {OptionSwitch("按协议身份去重",dedup,{dedup=it},"subscription_dedup")}
                item {OptionSwitch("更新时强制解析服务器 IP",resolve,{resolve=it},"subscription_resolve")}
                item {OutlinedTextField(agent,{agent=it},label={Text("自定义 User-Agent（空为默认）")},modifier=Modifier.fillMaxWidth().testTag("subscription_user_agent"))}
                item {
                    Text("节点名称过滤")
                    Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        listOf("关闭","保留匹配","排除匹配").forEachIndexed { index,label ->
                            FilterChip(selected=mode==index,onClick={mode=index},label={Text(label)},modifier=Modifier.testTag("subscription_filter_$index"))
                        }
                    }
                }
                item {OutlinedTextField(regex,{regex=it},label={Text("正则表达式（find 匹配）")},modifier=Modifier.fillMaxWidth().testTag("subscription_regex"))}
                item {OutlinedTextField(resolver,{resolver=it},label={Text("服务器地址 DNS（空为默认）")},modifier=Modifier.fillMaxWidth().testTag("subscription_dns"))}
                item {Text("保存选项后可手动更新。解析、正则或过滤失败会保留原节点；自动更新使用同一流水线。",style=MaterialTheme.typography.bodySmall)}
                if(error.isNotBlank())item{Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("subscription_options_error"))}
            }
        },
        confirmButton={
            TextButton(onClick={
                runCatching {
                    val interval=minutes.toInt();require(interval>0){"更新间隔须为正整数"}
                    if(mode!=0 && regex.isNotBlank())Regex(regex)
                    require(!agent.contains('\r') && !agent.contains('\n')){"User-Agent 不能包含换行"}
                    val options=JSONObject(original.toString()).put("autoUpdate",auto).put("autoUpdateDelay",interval)
                        .put("updateWhenConnectedOnly",connectedOnly).put("deduplication",dedup).put("forceResolve",resolve)
                        .put("customUserAgent",agent).put("filterMode",mode).put("filterRegex",regex).put("serverDnsResolver",resolver)
                    vm.edit {d->d.copy(groups=d.groups.map{if(it.id==group.id)it.copy(options=options.toString())else it})}
                    onDismiss()
                }.onFailure{error=it.message ?: "选项无效"}
            },modifier=Modifier.testTag("subscription_options_save")){Text("保存")}
        },
        dismissButton={TextButton(onClick=onDismiss){Text("取消")}}
    )
}

@Composable
private fun OptionSwitch(label:String,value:Boolean,onChange:(Boolean)->Unit,tag:String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label,Modifier.weight(1f))
        Switch(value,onChange,Modifier.testTag(tag))
    }
}
