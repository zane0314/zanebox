@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.zane.zanebox.ui

import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zane.zanebox.data.AppData
import com.zane.zanebox.data.Node
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private data class SiteDiagnostic(val key:String,val name:String,val category:String,val host:String)

private val siteDiagnostics = listOf(
    SiteDiagnostic("google", "Google", "国际服务 / 搜索", "google.com"),
    SiteDiagnostic("youtube", "YouTube", "视频 / 音乐", "youtube.com"),
    SiteDiagnostic("ai", "OpenAI", "AI 服务", "chatgpt.com"),
    SiteDiagnostic("netflix", "Netflix", "视频 / 音乐", "netflix.com"),
    SiteDiagnostic("telegram", "Telegram", "社交通信", "telegram.org"),
    SiteDiagnostic("x", "X / Twitter", "社交通信", "x.com"),
)

private data class ProbeResult(
    val host:String,
    val code:Int,
    val elapsedMs:Long,
    val mode:String,
    val proxyPort:Int,
    val uid:Int,
    val error:String = "",
)

private data class GeoAssetInfo(
    val name:String,
    val path:String,
    val exists:Boolean,
    val size:Long,
    val modified:String,
)

@Composable
internal fun UiDiagnostics(page:String,data:AppData,vm:AppViewModel,onDismiss:()->Unit) {
    when(page) {
        "routing-probe" -> RoutingProbePage(data,vm,onDismiss)
        "site-cards" -> SiteCardsPage(data,vm,onDismiss)
        "ruleset-preview" -> RuleSetPreviewPage(data,onDismiss)
        "penetration" -> PenetrationPage(vm,onDismiss)
        "geo-status" -> GeoStatusPage(data,vm,onDismiss)
        else -> UiPageList(uiText("诊断"),onDismiss) {
            item { UiCard { UiRow(uiText("未知诊断页面"),page,Icons.Outlined.Info,modifier=Modifier.testTag("diagnostics_unknown")) } }
        }
    }
}

@Composable
private fun RoutingProbePage(data:AppData,vm:AppViewModel,onDismiss:()->Unit) {
    val context=LocalContext.current
    val runtime by vm.service.snapshot.collectAsStateWithLifecycle()
    val exitIp by vm.ip.collectAsStateWithLifecycle()
    val connections by vm.service.connections.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var host by remember{mutableStateOf("")}
    var result by remember{mutableStateOf<ProbeResult?>(null)}
    var running by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    var captured by remember{mutableStateOf("")}
    LaunchedEffect(Unit) { vm.service.refreshConnections() }

    LaunchedEffect(connections,running) { if(running && matchingConnections(connections,normalizeProbeHost(host) ?: "").isNotEmpty())captured=connections }
    fun runProbe() {
        val normalized=normalizeProbeHost(host)
        if(normalized==null) { error="请输入有效域名（不含端口和路径）";return }
        if(runtime.state!=2) { error="请先连接代理后再检测分流";return }
        error=""
        result=null;captured=""
        running=true
        vm.service.refreshConnections()
        val mode=data.setting("serviceMode","vpn")
        val port=data.setting("mixedPort","2080").toIntOrNull() ?: 0
        val uid=runCatching { context.packageManager.getApplicationInfo(context.packageName,0).uid }.getOrDefault(Process.myUid())
        scope.launch {
            val poll=launch { while(isActive) { vm.service.refreshConnections();delay(250) } }
            try { result=withContext(Dispatchers.IO) { runHeadProbe(normalized,mode,port,uid) } }
            finally { poll.cancel();running=false;vm.service.refreshConnections() }
        }
    }

    val matches=remember(connections,captured,result?.host){matchingConnections(captured.ifBlank{connections},result?.host ?: "")}
    UiPageList(uiText("分流检测"),onDismiss,action={
        IconButton(onClick={vm.service.refreshConnections();vm.refreshIp()},modifier=Modifier.testTag("routing_probe_refresh")){Icon(Icons.Outlined.Refresh,uiText("刷新"))}
    }) {
        item { UiCard {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(uiText("真实链路检测"),style=MaterialTheme.typography.titleMedium)
                Text("${uiText("发起应用")}: ${context.packageName} · UID ${runCatching { context.packageManager.getApplicationInfo(context.packageName,0).uid }.getOrDefault(Process.myUid())}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(uiText("结果来自当前进程发起的 HTTPS HEAD 请求和 Clash API 连接记录。配置目标不会被当作实际出口。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(host,{host=it;error=""},label={Text(uiText("输入域名"))},singleLine=true,
                    modifier=Modifier.fillMaxWidth().testTag("routing_probe_domain"))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick={host="chatgpt.com"},modifier=Modifier.weight(1f).testTag("routing_probe_preset_ai")){Text("chatgpt.com")}
                    OutlinedButton(onClick={host="youtube.com"},modifier=Modifier.weight(1f).testTag("routing_probe_preset_video")){Text("youtube.com")}
                    Button(onClick=::runProbe,enabled=!running,modifier=Modifier.weight(1f).testTag("routing_probe_start")){Text(if(running)uiText("检测中") else uiText("开始检测"))}
                }
                if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("routing_probe_error"))
            }
        } }
        item { UiCard {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(uiText("运行时链路"),style=MaterialTheme.typography.titleMedium)
                Text("${uiText("服务状态")}: ${runtimeStateText(runtime.state)}")
                if(runtime.error.isNotBlank())Text("${uiText("运行时错误")}: ${runtime.error}",color=MaterialTheme.colorScheme.error)
                Text("${uiText("服务模式")}: ${if(data.setting("serviceMode","vpn")=="proxy")uiText("本地代理") else uiText("VPN")}")
                if(data.setting("serviceMode","vpn")=="proxy")Text("${uiText("HEAD 代理")}: 127.0.0.1:${data.setting("mixedPort","2080")}",style=MaterialTheme.typography.bodySmall)
                else Text(uiText(if(runtime.state==2)"HEAD 通过当前 VPN 承载；未使用本地 HTTP 代理。" else "代理未连接，尚未执行 VPN 链路检测。"),style=MaterialTheme.typography.bodySmall)
                Text("${uiText("实际出口 IP")}: ${exitIp.ifBlank{uiText("尚未查询")}}",style=MaterialTheme.typography.bodySmall)
                if(result!=null)ProbeResultCard(result!!)
            }
        } }
        if(result!=null) {
            item { UiCard {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(uiText("API 连接关联"),style=MaterialTheme.typography.titleMedium)
                    if(matches.isEmpty())Text(uiText("当前 API 尚未返回该域名的唯一活动连接；不能据此判定分流成功。"),color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("routing_probe_api_unmatched"))
                    else matches.forEachIndexed { index,row -> ApiConnectionDetails(row,"api_match_$index") }
                }
            } }
        }
    }
}

@Composable
private fun ProbeResultCard(result:ProbeResult) {
    val state=when {
        result.error.isNotBlank() -> uiText("请求失败")
        result.code in 200..399 -> uiText("收到 HTTP 响应")
        else -> uiText("收到 HTTP 错误")
    }
    Text("${result.host} · $state · ${result.elapsedMs} ms",modifier=Modifier.testTag("routing_probe_result"))
    if(result.error.isNotBlank())Text("${uiText("错误")}: ${result.error}",color=MaterialTheme.colorScheme.error)
    else Text("HTTP ${result.code} · UID ${result.uid} · ${if(result.mode=="proxy")"127.0.0.1:${result.proxyPort}" else uiText("VPN 承载")}",style=MaterialTheme.typography.bodySmall)
}

@Composable
private fun SiteCardsPage(data:AppData,vm:AppViewModel,onDismiss:()->Unit) {
    val tests by vm.service.testResults.collectAsStateWithLifecycle()
    val runtime by vm.service.snapshot.collectAsStateWithLifecycle()
    val activeNodes=remember(data){siteDiagnostics.flatMap { com.zane.zanebox.config.ConfigBuilder.smartTargetNodeIds(data,data.setting("smart.${it.key}.target","off")) }.distinct()}
    UiPageList(uiText("站点分流卡片"),onDismiss,action={
        IconButton(onClick={vm.service.testNodes(activeNodes)},enabled=activeNodes.isNotEmpty(),modifier=Modifier.testTag("site_cards_refresh")){Icon(Icons.Outlined.Refresh,uiText("刷新"))}
    }) {
        item { UiCard { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(uiText("站点分流状态"),style=MaterialTheme.typography.titleMedium)
            Text("${uiText("运行状态")}: ${runtimeStateText(runtime.state)}")
            Text(uiText("卡片目标与规则数量来自当前配置；延迟只显示已有 ServiceClient 测速结果。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(activeNodes.isEmpty())Text(uiText("没有可测速节点"),color=MaterialTheme.colorScheme.error)
        } } }
        items(siteDiagnostics) { site ->
            val target=data.setting("smart.${site.key}.target","off")
            val ruleText=data.setting("smartRules.${site.key}")
            val candidateIds=com.zane.zanebox.config.ConfigBuilder.smartTargetNodeIds(data,target)
            val pings=candidateIds.mapNotNull{tests[it]}.filter{it>0}
            val failed=candidateIds.any{tests[it]?.let{ping->ping<=0}==true}
            val health=when {
                pings.isNotEmpty() -> "${pings.min()} ms"
                failed -> uiText("检测失败")
                target in listOf("direct","block") -> uiText("无需节点测速")
                candidateIds.isEmpty() -> uiText("没有可用节点")
                else -> uiText("未检测")
            }
            UiCard { UiRow(site.name,"${site.category} · ${site.host} · ${uiText("目标")}: ${targetName(target,data)} · ${uiText("规则")}: ${ruleLineCount(ruleText)} · $health",Icons.Outlined.Public,
                modifier=Modifier.testTag("site_card_${site.key}"),chevron=false,trailing={Text(health,color=if(pings.isNotEmpty())MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}) }
        }
    }
}

@Composable
private fun RuleSetPreviewPage(data:AppData,onDismiss:()->Unit) {
    val previews=remember(data.settings,data.rules){buildRulePreviews(data)}
    UiPageList(uiText("规则集预览"),onDismiss) {
        item { UiCard { Text(uiText("仅展示当前配置与已加载正文；此页面不会把 URL 或规则条数当作远程检测成功。"),Modifier.padding(16.dp),style=MaterialTheme.typography.bodySmall) } }
        items(previews) { preview ->
            UiCard {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text(preview.title,style=MaterialTheme.typography.titleSmall)
                    Text(preview.subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${uiText("状态")}: ${preview.status} · ${uiText("规则")}: ${preview.count}")
                    preview.sample.forEach { line -> Text(line,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis) }
                }
            }
        }
        if(previews.isEmpty())item{UiCard{Text(uiText("当前没有可预览规则"),Modifier.padding(16.dp))}}
    }
}

@Composable
private fun PenetrationPage(vm:AppViewModel,onDismiss:()->Unit) {
    val connections by vm.service.connections.collectAsStateWithLifecycle()
    var query by remember{mutableStateOf("")}
    LaunchedEffect(Unit) { vm.service.refreshConnections() }
    val rows=remember(connections,query){
        val all=parseConnections(connections)
        val q=query.trim().lowercase()
        if(q.isBlank())all else all.filter{connectionSearchText(it).contains(q)}
    }
    UiPageList(uiText("域名穿透"),onDismiss,action={
        IconButton(onClick=vm.service::refreshConnections,modifier=Modifier.testTag("penetration_refresh")){Icon(Icons.Outlined.Refresh,uiText("刷新"))}
    }) {
        item { UiCard {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(uiText("当前 API 连接"),style=MaterialTheme.typography.titleMedium)
                Text(uiText("三层视图来自实时连接元数据：入站 → 域名与规则 → 出口链路。没有活动连接时不会生成默认链路。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(query,{query=it},label={Text(uiText("过滤域名、规则或连接 ID"))},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("penetration_filter"))
            }
        } }
        if(rows.isEmpty())item{UiCard{Text(uiText("API 未返回匹配的活动连接"),Modifier.padding(16.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}}
        items(rows) { row -> UiCard { ApiConnectionDetails(row,"penetration_${row.optString("id")}") } }
    }
}

@Composable
private fun ApiConnectionDetails(row:JSONObject,testTag:String) {
    val meta=row.optJSONObject("metadata") ?: JSONObject()
    val host=connectionHost(row).ifBlank{uiText("未知域名")}
    val inbound=meta.optString("type").ifBlank{row.optString("inbound").ifBlank{uiText("未知入站")}}
    val network=meta.optString("network").ifBlank{uiText("未知网络")}
    val source=meta.optString("sourceIP").ifBlank{"?"}+":"+meta.optString("sourcePort").ifBlank{"?"}
    val destination=meta.optString("destinationIP").ifBlank{"?"}+":"+meta.optString("destinationPort").ifBlank{"?"}
    val rule=row.optString("rule").ifBlank{uiText("核心未提供")}
    val payload=row.optString("rulePayload").ifBlank{uiText("核心未提供")}
    val chains=runCatching { row.optJSONArray("chains") }.getOrNull()?.let{a->(0 until a.length()).map{a.optString(it)}.filter{it.isNotBlank()}}.orEmpty().ifEmpty {
        row.optString("chain").takeIf{it.isNotBlank()}?.let{listOf(it)} ?: emptyList()
    }
    Column(Modifier.padding(16.dp).testTag(testTag),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(host,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
        Text("${uiText("Tier 1 入站")}: $inbound · $network · $source")
        val process=meta.optString("process").ifBlank{meta.optString("processPath").substringAfterLast('/')} 
        if(process.isNotBlank())Text("${uiText("进程")}: $process · UID ${meta.optString("uid").ifBlank{uiText("未知")}}",style=MaterialTheme.typography.bodySmall)
        Text("${uiText("Tier 2 域名与规则")}: $host → $rule")
        Text("${uiText("规则内容")}: $payload",style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)
        Text("${uiText("目标")}: $destination",style=MaterialTheme.typography.bodySmall)
        Text("${uiText("Tier 3 出口链路")}: ${if(chains.isEmpty())uiText("核心未提供") else chains.joinToString(" → ")}")
        Text("${uiText("连接 ID")}: ${row.optString("id").ifBlank{uiText("未知")}}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GeoStatusPage(data:AppData,vm:AppViewModel,onDismiss:()->Unit) {
    val context=LocalContext.current
    val runtime by vm.service.snapshot.collectAsStateWithLifecycle()
    var revision by remember{mutableStateOf(0)}
    val assets=remember(revision){geoAssets(context)}
    UiPageList(uiText("Geo 状态"),onDismiss,action={IconButton(onClick={revision++},modifier=Modifier.testTag("geo_status_refresh")){Icon(Icons.Outlined.Refresh,uiText("刷新"))}}) {
        item { UiCard { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(uiText("core-assets 实际文件"),style=MaterialTheme.typography.titleMedium)
            Text("${uiText("服务状态")}: ${runtimeStateText(runtime.state)}")
            Text("${uiText("更新源配置")}: ${data.setting("rulesProvider","0")}",style=MaterialTheme.typography.bodySmall)
            Text("${uiText("GeoIP URL")}: ${data.setting("rulesGeoipUrl").ifBlank{uiText("未配置")}}",style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text("${uiText("Geosite URL")}: ${data.setting("rulesGeositeUrl").ifBlank{uiText("未配置")}}",style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
        } } }
        items(assets) { asset ->
            UiCard { UiRow(asset.name,if(asset.exists)"${uiText("存在")}: ${bytes(asset.size)} · ${asset.modified}" else uiText("缺失"),Icons.Outlined.Storage,modifier=Modifier.testTag("geo_asset_${asset.name}"),chevron=false,trailing={Text(if(asset.exists)uiText("存在") else uiText("缺失"),color=if(asset.exists)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)}) }
        }
    }
}

private fun runHeadProbe(host:String,mode:String,port:Int,uid:Int):ProbeResult {
    val started=SystemClock.elapsedRealtime()
    if(mode=="proxy" && port !in 1..65535)return ProbeResult(host,-1,0,mode,port,uid,"本地代理端口无效")
    return try {
        val builder=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).retryOnConnectionFailure(false)
        if(mode=="proxy")builder.proxy(Proxy(Proxy.Type.HTTP,InetSocketAddress("127.0.0.1",port))) else builder.proxy(Proxy.NO_PROXY)
        val request=Request.Builder().url("https://$host").head().header("User-Agent","zanebox-routing-probe").build()
        builder.build().newCall(request).execute().use { response ->
            ProbeResult(host,response.code,SystemClock.elapsedRealtime()-started,mode,port,uid)
        }
    } catch(e:CancellationException) {
        throw e
    } catch(e:Exception) {
        ProbeResult(host,-1,SystemClock.elapsedRealtime()-started,mode,port,uid,e.message ?: e.javaClass.simpleName)
    }
}

private fun normalizeProbeHost(value:String):String? {
    var text=value.trim()
    if(text.startsWith("https://",true))text=text.substring(8)
    else if(text.startsWith("http://",true))text=text.substring(7)
    if(text.any{it=='/' || it=='?' || it=='#' || it==':'})return null
    text=text.trimEnd('.').lowercase(Locale.ROOT)
    return text.takeIf{it.length in 1..253 && it.matches(Regex("(?i)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}"))}
}

private fun runtimeStateText(state:Int):String = when(state) {
    1->"正在连接"
    2->"代理已连接"
    3->"正在断开"
    4->"连接失败"
    else->"代理未连接"
}

private fun parseConnections(text:String):List<JSONObject> = runCatching {
    val rows=JSONObject(text).optJSONArray("connections") ?: return@runCatching emptyList()
    (0 until minOf(rows.length(),512)).mapNotNull{rows.optJSONObject(it)}
}.getOrDefault(emptyList())

private fun connectionHost(row:JSONObject):String {
    val meta=row.optJSONObject("metadata") ?: JSONObject()
    return listOf(meta.optString("host"),meta.optString("destinationHost"),meta.optString("destHost"),meta.optString("destinationIP"))
        .firstOrNull{it.isNotBlank()}?.let(::displayHost).orEmpty()
}

private fun displayHost(value:String):String = value.trim().substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringBeforeLast(':').lowercase(Locale.ROOT)

private fun connectionSearchText(row:JSONObject):String = buildString {
    append(row.optString("id")).append(' ')
    append(connectionHost(row)).append(' ')
    append(row.optString("rule")).append(' ')
    append(row.optString("rulePayload")).append(' ')
    append(row.optJSONObject("metadata")?.toString().orEmpty())
}.lowercase(Locale.ROOT)

private fun matchingConnections(text:String,host:String):List<JSONObject> {
    if(host.isBlank())return emptyList()
    return parseConnections(text).filter{displayHost(connectionHost(it))==host.lowercase(Locale.ROOT)}
}

private data class RulePreview(val title:String,val subtitle:String,val count:Int,val status:String,val sample:List<String>)

private fun buildRulePreviews(data:AppData):List<RulePreview> {
    val result=mutableListOf<RulePreview>()
    val presets=listOf("geosite:cn","geoip:cn","Global.list","Domestic.list")
    presets.forEach { key ->
        val text=data.setting("smartRules.$key")
        result += RulePreview(key,"基线规则集",ruleLineCount(text),if(text.isBlank())"当前未加载正文" else "已加载当前正文",ruleSamples(text))
    }
    data.settings.filterKeys{it.startsWith("smartRules.")}.toSortedMap().forEach { (key,text) ->
        val service=key.removePrefix("smartRules.")
        if(presets.contains(service))return@forEach
        val url=data.setting("smartUrl.$service")
        result += RulePreview(service,"智能应用规则${if(url.isBlank())"" else " · $url"}",ruleLineCount(text),when { text.isNotBlank() && url.isNotBlank()->"已加载远程正文";text.isNotBlank()->"已加载自定义正文";url.isNotBlank()->"仅配置 URL，尚未加载正文";else->"未配置" },ruleSamples(text))
    }
    data.rules.sortedBy{it.order}.forEach { rule ->
        val sets=runCatching { JSONObject(rule.advanced).opt("rule_set") }.getOrNull()?.let{value->when(value){is JSONArray->(0 until value.length()).map{value.optString(it)};null,JSONObject.NULL->emptyList();else->listOf(value.toString())}}.orEmpty().filter{it.isNotBlank()}
        if(sets.isNotEmpty())result += RulePreview(rule.name,"路由规则: ${rule.outbound}",sets.size,if(rule.enabled)"已启用" else "已停用",sets.take(5))
    }
    return result
}

private fun ruleLineCount(text:String):Int = text.lineSequence().count{val line=it.trim();line.isNotEmpty()&&!line.startsWith('#')&&!line.startsWith("//")}

private fun ruleSamples(text:String):List<String> = text.lineSequence().map{it.trim()}.filter{it.isNotEmpty()&&!it.startsWith('#')&&!it.startsWith("//")}.take(5).toList()

private fun geoAssets(context:Context):List<GeoAssetInfo> {
    val root=java.io.File(context.filesDir,"core-assets")
    val formatter=SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault())
    return listOf("GeoIP" to "geoip.db","Geosite" to "geosite.db").map { (title,fileName) ->
        val file=java.io.File(root,fileName)
        val exists=file.isFile && file.length()>0
        GeoAssetInfo(title,file.absolutePath,exists,if(exists)file.length() else 0,if(exists)formatter.format(Date(file.lastModified())) else "无修改时间")
    }
}
