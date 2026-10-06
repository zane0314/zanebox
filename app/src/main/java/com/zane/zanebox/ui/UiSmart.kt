package com.zane.zanebox.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zane.zanebox.data.AppData
import com.zane.zanebox.R
import com.zane.zanebox.subscription.SubscriptionClient
import kotlinx.coroutines.launch
import org.json.JSONArray

private const val KaringRuleCatalogUrl =
    "https://api.github.com/repos/KaringX/karing-ruleset/contents/ACL4SSR?ref=sing"

private data class RuleCatalogEntry(val name:String,val url:String,val page:String)

@Composable internal fun SmartPanel(data:AppData,vm:AppViewModel,form:(String,List<Pair<String,String>>,(List<String>)->Unit)->Unit,
    onRules:()->Unit,onMerges:()->Unit,onOpen:(String)->Unit) {
    var target by remember{mutableStateOf("")};var source by remember{mutableStateOf(false)}
    var ruleSource by remember{mutableStateOf("")};var updateChoice by remember{mutableStateOf("")}
    var apps by remember{mutableStateOf("")};var expanded by remember{mutableStateOf("")}
    var confirmDelete by remember{mutableStateOf("")}
    var moving by remember{mutableStateOf("")}
    val builtIn=listOf("speed" to "Speed · IP / DNS / 测速","youtube" to "YouTube","telegram" to "Telegram","netflix" to "Netflix","disney" to "Disney+","tiktok" to "TikTok","x" to "X","meta" to "Instagram / Facebook","spotify" to "Spotify","google" to "Google","ai" to "AI 应用")
    val policyOrder=com.zane.zanebox.config.smartPolicyKeys(data)
    val custom=data.settings.filterKeys{it.startsWith("smartCustom.") && it.endsWith(".name")}.map{it.key.removePrefix("smartCustom.").removeSuffix(".name") to it.value}
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        UiSection("路由规则")
        UiCard{UiRow("路由规则","前置 ${data.rules.count{it.prioritize}} · 后置 ${data.rules.count{!it.prioritize}}",Icons.Outlined.AccountTree,iconRes=R.drawable.zb_ref_ic_mingcute_route_24,onClick=onRules,modifier=Modifier.testTag("smart_rules"))}
        UiSection("分流节点组")
        UiCard{UiRow(data.merges.firstOrNull{it.id.toString()==data.setting("smartSourceMergeId")}?.name ?: data.groups.firstOrNull{it.id.toString()==data.setting("smartSourceGroupId")}?.name ?: "默认汇总组",
            "${data.nodes.count{n->data.groups.any{it.id==n.groupId && it.enabled}}} 个可用节点",Icons.Outlined.Router,iconRes=R.drawable.zb_ref_ic_smart_router,onClick={source=true},modifier=Modifier.testTag("smart_source"))}
        UiCard{UiRow("引用规则更新", "自动：${data.setting("rulesUpdateInterval","24h")} · 连接后：${data.setting("rulesUpdateDelay","30s")}",Icons.Outlined.Refresh,onClick={updateChoice="rulesUpdateInterval"},trailing={
            Row{IconButton(onClick={vm.updateAllSmartRules()},modifier=Modifier.testTag("smart_update")){Icon(Icons.Outlined.Download,"立即更新规则")};IconButton(onClick={updateChoice="rulesUpdateDelay"}){Icon(Icons.Outlined.Settings,"规则更新设置")}}
        })}
        UiCard{UiRow("管理节点汇总组","合并多个分组或指定节点为分流目标",Icons.Outlined.Hub,iconRes=R.drawable.zb_ref_ic_mingcute_group_24,onClick=onMerges,modifier=Modifier.testTag("smart_merges"))}
        UiSection("应用策略")
        val policies=(builtIn+(if(data.settings.keys.any{it=="smartRules.custom" || it=="smart.custom.target"})listOf("custom" to "自定义规则")else emptyList())+custom).toMap()
        policyOrder.mapNotNull{key->policies[key]?.let{key to it}}.forEach{(key,title)->
            val value=data.setting("smart.$key.target",if(key=="speed")"proxy" else "off")
            UiCard{UiRow(title,if(value=="off")"使用普通主节点" else targetName(value,data),when(key){"speed"->Icons.Outlined.Speed;"youtube"->Icons.Outlined.PlayCircle;"telegram"->Icons.Outlined.Send;"spotify","tiktok"->Icons.Outlined.MusicNote;"google"->Icons.Outlined.Public;"ai"->Icons.Outlined.AutoAwesome;else->Icons.Outlined.Apps},
                onClick={expanded=if(expanded==key)"" else key},onLongClick={moving=key},modifier=Modifier.testTag("smart_$key"),trailing={TextButton(onClick={target=key},modifier=Modifier.testTag("smart_target_$key")){Text(targetName(value,data))}})
                if(expanded==key) {
                    UiRow("分流目标",targetName(value,data),onClick={target=key})
                    UiRow("规则来源",data.setting("smartUrl.$key").ifBlank{if(key in builtIn.map{it.first} && !data.settings.containsKey("smartRules.$key"))"内置规则组" else "自定义域名规则"},Icons.Outlined.Description,onClick={ruleSource=key})
                    UiRow("选择应用","${data.setting("smartCustom.$key.packages").lines().count{it.isNotBlank()}} 个应用",Icons.Outlined.Apps,onClick={apps=key},modifier=Modifier.testTag("smart_apps_$key"))
                    if(custom.any{it.first==key}) {
                        UiRow("重命名",onClick={form("重命名",listOf("名称" to title)){values->require(values[0].isNotBlank());require((builtIn+custom).none{it.first!=key && it.second==values[0]}){"名称已存在"};vm.setting("smartCustom.$key.name",values[0])}})
                        UiRow("删除应用组",onClick={confirmDelete=key})
                    }
                }
            }
        }
        OutlinedButton(onClick={form("添加自定义应用组",listOf("名称" to "")){v->require(v[0].isNotBlank()){"请输入名称"};require((builtIn+custom).none{it.second==v[0]}){"名称已存在"};val key="custom_${vm.store.nextId()}";vm.setting("smartCustom.$key.name",v[0])}},modifier=Modifier.fillMaxWidth().testTag("smart_add_custom")){Icon(Icons.Outlined.Add,null);Text("添加自定义应用组")}
        if(data.setting("serviceMode","vpn")!="vpn")Text("智能应用路由依赖 VPN 模式。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
    }
    if(moving.isNotBlank())UiAlertDialog(onDismissRequest={moving=""},title={Text("移动策略")},text={Column {
        val index=policyOrder.indexOf(moving)
        TextButton(onClick={vm.moveSmartPolicy(moving,-1);moving=""},enabled=index>0,modifier=Modifier.testTag("smart_move_up")){Text(uiText("上移"))}
        TextButton(onClick={vm.moveSmartPolicy(moving,1);moving=""},enabled=index>=0 && index<policyOrder.lastIndex,modifier=Modifier.testTag("smart_move_down")){Text(uiText("下移"))}
    }},confirmButton={TextButton(onClick={moving=""}){Text(uiText("取消"))}})
    if(target.isNotBlank())TargetPicker("分流目标",data.setting("smart.$target.target",if(target=="speed")"proxy" else "off"),data,{target=""},{vm.selectSmartTarget(target,it)},smart=true)
    if(source)ChoiceDialog("分流节点组",if(data.setting("smartSourceMergeId","0")!="0")"merge:${data.setting("smartSourceMergeId")}" else "group:${data.setting("smartSourceGroupId","0")}",
        listOf("group:0" to "全部启用节点")+data.merges.map{"merge:${it.id}" to it.name}+data.groups.filter{it.enabled}.map{"group:${it.id}" to it.name},{source=false}){value->vm.edit{d->d.copy(settings=d.settings+mapOf("smartSourceMergeId" to if(value.startsWith("merge:"))value.substringAfter(':') else "0","smartSourceGroupId" to if(value.startsWith("group:"))value.substringAfter(':') else "0"))}}
    if(ruleSource.isNotBlank())RuleSourcePage(ruleSource,data,vm){ruleSource=""}
    if(updateChoice.isNotBlank())ChoiceDialog(if(updateChoice=="rulesUpdateInterval")"自动更新间隔" else "连接后检查",data.setting(updateChoice,if(updateChoice=="rulesUpdateInterval")"24h" else "30s"),
        if(updateChoice=="rulesUpdateInterval")listOf("off" to "关闭","6h" to "6 小时","12h" to "12 小时","24h" to "24 小时","3d" to "3 天","7d" to "7 天")else listOf("0s" to "立即","15s" to "15 秒","30s" to "30 秒","1m" to "1 分钟","5m" to "5 分钟"),{updateChoice=""}){vm.setting(updateChoice,it)}
    if(apps.isNotBlank())AppsEditor(data.setting("smartCustom.$apps.packages").lines().filter{it.isNotBlank()}.toSet(),{apps=""}){vm.setting("smartCustom.$apps.packages",it.joinToString("\n"));apps=""}
    if(confirmDelete.isNotBlank())UiAlertDialog(onDismissRequest={confirmDelete=""},title={Text("删除自定义应用组？")},confirmButton={TextButton(onClick={val key=confirmDelete;vm.edit{d->d.copy(settings=d.settings.filterKeys{!it.startsWith("smartCustom.$key.") && it!="smart.$key.target" && it!="smartUrl.$key" && it!="smartRules.$key"})};confirmDelete=""}){Text("删除")}},dismissButton={TextButton(onClick={confirmDelete=""}){Text("取消")}})
}

@Composable internal fun SmartMenu(data:AppData,vm:AppViewModel,onOpen:(String)->Unit) {
    UiMenu(listOf(
        (if(data.setting("routeMode","rule")=="global")"退出全局模式" else "全局模式") to {vm.setting("routeMode",if(data.setting("routeMode","rule")=="global")"rule" else "global")},
        "路由规则" to {onOpen("rules")},
        "分流检测" to {onOpen("routing-probe")},
        "站点分流卡片" to {onOpen("site-cards")},
        "规则集预览" to {onOpen("ruleset-preview")},
        "域名穿透" to {onOpen("penetration")},
        "Geo 状态" to {onOpen("geo-status")},
        "路由资产" to {onOpen("assets")},
        "流量统计" to {onOpen("traffic")},
        "节点汇总组" to {onOpen("merges")},
        "备份与恢复" to {onOpen("backup")}),"smart_menu")
}

@Composable
private fun RuleSourcePage(serviceKey:String,data:AppData,vm:AppViewModel,onDismiss:()->Unit) {
    val initialUrl=data.setting("smartUrl.$serviceKey")
    val initialRules=remember(serviceKey,data.settings) {
        if(data.settings.containsKey("smartRules.$serviceKey"))data.setting("smartRules.$serviceKey")
        else if(data.setting("smartUrl.$serviceKey").isBlank())com.zane.zanebox.config.builtinSmartRuleFiles[serviceKey]?.joinToString("\n") { name->vm.getApplication<android.app.Application>().assets.open("anybox-rules/$name.list").bufferedReader().use{it.readText()} }.orEmpty()
        else ""
    }
    var url by remember(serviceKey,initialUrl){mutableStateOf(initialUrl)}
    var rules by remember(serviceKey,initialRules){mutableStateOf(initialRules)}
    var error by remember{mutableStateOf("")}
    var catalogError by remember{mutableStateOf("")}
    var catalog by remember{mutableStateOf<List<RuleCatalogEntry>>(emptyList())}
    var catalogQuery by remember{mutableStateOf("")}
    var catalogOpen by remember{mutableStateOf(false)}
    var catalogLoading by remember{mutableStateOf(false)}
    var fetching by remember{mutableStateOf(false)}
    var confirmDelete by remember{mutableStateOf(false)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()

    fun validateUrl():Boolean {
        val candidate=url.trim()
        if(candidate.isBlank())return true
        return runCatching { validatePreference("testUrl",candidate) }.onFailure { error=it.message ?: "规则源 URL 无效" }.isSuccess
    }
    fun save(close:Boolean) {
        error=""
        if(!validateUrl())return
        val savedUrl=url.trim()
        val savedRules=rules
        vm.edit(onSaved={if(close)onDismiss()},onError={error=it}) {d->
            d.copy(settings=d.settings+mapOf("smartUrl.$serviceKey" to savedUrl,"smartRules.$serviceKey" to savedRules))
        }
    }
    fun fetchAndReplace() {
        error=""
        if(url.trim().isBlank()){error="请先填写规则源 URL";return}
        if(!validateUrl())return
        val requestUrl=url.trim()
        fetching=true
        scope.launch {
            try {
                val fetchedRules=SubscriptionClient.fetchSmartRules(requestUrl,data.settings)
                rules=fetchedRules
                vm.edit(onSaved={},onError={error=it}) {d->
                    d.copy(settings=d.settings+mapOf("smartUrl.$serviceKey" to requestUrl,"smartRules.$serviceKey" to fetchedRules,"smartUpdated.$serviceKey" to System.currentTimeMillis().toString()))
                }
            } catch(e:Exception) {
                error=e.message ?: "规则源获取失败"
            } finally { fetching=false }
        }
    }
    fun loadCatalog() {
        catalogOpen=true
        if(catalog.isNotEmpty() || catalogLoading)return
        catalogError=""
        catalogLoading=true
        scope.launch {
            try {
                val body=SubscriptionClient.fetch(KaringRuleCatalogUrl,settings=data.settings).body
                val json=JSONArray(body)
                catalog=(0 until json.length()).mapNotNull { index ->
                    val entry=json.optJSONObject(index) ?: return@mapNotNull null
                    if(entry.optString("type")!="file")return@mapNotNull null
                    val name=entry.optString("name").trim()
                    val download=entry.optString("download_url").trim()
                    if(name.isBlank() || download.isBlank())null else RuleCatalogEntry(name,download,entry.optString("html_url"))
                }.sortedBy{it.name.lowercase()}
                if(catalog.isEmpty())catalogError="目录没有可用规则文件"
            } catch(e:Exception) {
                catalogError=e.message ?: "Karing 目录加载失败"
            } finally { catalogLoading=false }
        }
    }
    val filteredCatalog=remember(catalog,catalogQuery){
        val query=catalogQuery.trim().lowercase()
        if(query.isBlank())catalog else catalog.filter{it.name.lowercase().contains(query) || it.url.lowercase().contains(query)}
    }

    UiPageList("分流规则来源",onDismiss,action={
        TextButton(onClick={confirmDelete=true},enabled=!busy,modifier=Modifier.testTag("rule_source_delete")){Text(uiText("删除"))}
    }) {
        item {
            UiCard {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("${serviceKey} · ${uiText("规则源")}",style=MaterialTheme.typography.titleMedium)
                    OutlinedTextField(url,{url=it;error=""},label={Text(uiText("规则源 URL"))},singleLine=true,
                        modifier=Modifier.fillMaxWidth().testTag("rule_source_url"))
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                        OutlinedButton(onClick=::loadCatalog,enabled=!busy&&!fetching,modifier=Modifier.weight(1f).testTag("rule_catalog")) {
                            Icon(Icons.Outlined.Search,null);Spacer(Modifier.width(6.dp));Text(uiText("在线目录 / Karing 搜索"))
                        }
                        if(fetching)CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)
                    }
                    OutlinedTextField(rules,{rules=it;error=""},label={Text(uiText("自定义规则（每行域名或 DOMAIN / IP-CIDR）"))},
                        modifier=Modifier.fillMaxWidth().testTag("rule_source_rules"),minLines=8,maxLines=16)
                    if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("rule_source_error"))
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                        TextButton(onClick={save(false)},enabled=!busy&&!fetching,modifier=Modifier.testTag("rule_source_save")){Text(uiText("保存"))}
                        Button(onClick=::fetchAndReplace,enabled=!busy&&!fetching,modifier=Modifier.testTag("rule_source_replace")){Text(uiText("获取并替换"))}
                    }
                    Text(uiText("保存失败会保留当前编辑内容；获取并替换会使用现有订阅 HTTP 管线。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if(catalogOpen)UiAlertDialog(onDismissRequest={catalogOpen=false},title={Text(uiText("Karing 在线规则目录"))},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(catalogQuery,{catalogQuery=it},label={Text(uiText("搜索 Karing 规则"))},singleLine=true,
                modifier=Modifier.fillMaxWidth().testTag("rule_catalog_search"))
            if(catalogLoading)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center){CircularProgressIndicator(Modifier.size(28.dp))}
            if(catalogError.isNotBlank())Text(catalogError,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("rule_catalog_error"))
            LazyColumn(Modifier.heightIn(max=420.dp)) {
                items(filteredCatalog,key={it.url}) { entry ->
                    Row(Modifier.fillMaxWidth().padding(vertical=4.dp).testTag("rule_catalog_item_${entry.name}"),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable{url=entry.url;catalogOpen=false;error=""}) {
                            Text(entry.name,maxLines=1)
                            if(entry.page.isNotBlank())Text(entry.page,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.labelSmall,maxLines=1)
                        }
                        TextButton(onClick={url=entry.url;catalogOpen=false;error=""},modifier=Modifier.testTag("rule_catalog_replace_${entry.name}")){Text(uiText("替换"))}
                    }
                }
            }
            if(!catalogLoading && catalog.isNotEmpty() && filteredCatalog.isEmpty())Text(uiText("没有匹配的 Karing 规则"),modifier=Modifier.testTag("rule_catalog_empty"))
        }
    },confirmButton={TextButton(onClick={catalogOpen=false}){Text(uiText("关闭"))}})

    if(confirmDelete)UiAlertDialog(onDismissRequest={confirmDelete=false},title={Text(uiText("删除规则源？"))},text={Text(uiText("删除后将清除该应用的 URL 与已保存规则。"))},
        confirmButton={TextButton(onClick={
            confirmDelete=false
            vm.edit(onSaved=onDismiss,onError={error=it}) {d->
                d.copy(settings=d.settings.filterKeys { key -> key!="smartUrl.$serviceKey" && key!="smartUpdated.$serviceKey" }+("smartRules.$serviceKey" to ""))
            }
        },enabled=!busy,modifier=Modifier.testTag("rule_source_delete_confirm")){Text(uiText("删除"))}},
        dismissButton={TextButton(onClick={confirmDelete=false}){Text(uiText("取消"))}})
}
