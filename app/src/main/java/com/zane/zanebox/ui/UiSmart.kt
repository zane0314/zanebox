package com.zane.zanebox.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.spring
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zane.zanebox.data.AppData
import com.zane.zanebox.config.smartTarget
import com.zane.zanebox.R
import com.zane.zanebox.subscription.SubscriptionClient
import kotlinx.coroutines.launch
import org.json.JSONArray

internal fun smartDomainRule(line:String):Pair<String,String>? {
    val text=line.trim()
    if(text.isBlank() || text.startsWith('#') || text.startsWith("//"))return null
    val fields=text.split(',').map{it.trim()}
    return when {
        fields.size>=2 && fields[0].uppercase() in listOf("DOMAIN","DOMAIN-SUFFIX","DOMAIN-KEYWORD")->fields[0].uppercase() to fields[1]
        fields.size==1 && text.matches(Regex("[\\p{L}\\p{N}*_.-]+"))->"DOMAIN-SUFFIX" to text
        else->null
    }
}

private const val KaringRuleCatalogUrl =
    "https://api.github.com/repos/KaringX/karing-ruleset/contents/ACL4SSR?ref=sing"

private data class RuleCatalogEntry(val name:String,val url:String,val page:String)

@Composable internal fun SmartPanel(data:AppData,vm:AppViewModel,form:(String,List<Pair<String,String>>,(List<String>)->Unit)->Unit,
    onRules:()->Unit,onMerges:()->Unit,onOpen:(String)->Unit,modifier:Modifier=Modifier) {
    val installedPackages=rememberInstalledPackageNames()
    var target by remember{mutableStateOf("")}
    var ruleSource by remember{mutableStateOf("")};var updateChoice by remember{mutableStateOf("")}
    var apps by remember{mutableStateOf("")};var expanded by remember{mutableStateOf("")}
    var confirmDelete by remember{mutableStateOf("")}
    val builtIn=listOf("speed" to "Speed · IP / DNS / 测速","youtube" to "YouTube","telegram" to "Telegram","netflix" to "Netflix","disney" to "Disney+","tiktok" to "TikTok","x" to "X","meta" to "Instagram / Facebook","spotify" to "Spotify","google" to "Google","ai" to "AI 应用")
    val policyOrder=com.zane.zanebox.config.smartPolicyKeys(data)
    val custom=data.settings.filterKeys{it.startsWith("smartCustom.") && it.endsWith(".name")}.map{it.key.removePrefix("smartCustom.").removeSuffix(".name") to it.value}
    val policies=(builtIn+(if(data.settings.keys.any{it=="smartRules.custom" || it=="smart.custom.target"})listOf("custom" to "自定义规则")else emptyList())+custom).toMap()
    val testing by vm.service.testingNodes.collectAsStateWithLifecycle()
    val listState=rememberLazyListState()
    val drag=rememberDragSort(policyOrder.map{"smart-policy-$it"},listState){keys->vm.reorderSmartPolicies(keys.map{it.toString().removePrefix("smart-policy-")})}
    LazyColumn(modifier.fillMaxSize().testTag("page_list"),state=listState,contentPadding=PaddingValues(horizontal=16.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item(key="smart-rules-section"){UiSection("路由规则")}
        item(key="smart-rules"){UiCard{UiRow("路由规则",uiText("前置 %s · 后置 %s",data.rules.count{it.prioritize},data.rules.count{!it.prioritize}),Icons.Outlined.AccountTree,iconRes=R.drawable.zb_ref_ic_mingcute_route_24,onClick=onRules,modifier=Modifier.testTag("smart_rules"))}}
        item(key="smart-groups-section"){UiSection("分流节点组")}
        item(key="smart-source"){UiCard{UiRow("自动选择范围",uiText("全部启用订阅组 · %s 个可用节点",com.zane.zanebox.config.ConfigBuilder.smartTargetNodeIds(data,"auto").size),Icons.Outlined.Router,iconRes=R.drawable.zb_ref_ic_smart_router,chevron=false,modifier=Modifier.testTag("smart_source"))}}
        item(key="smart-update"){UiCard{UiRow("引用规则更新", uiText("自动：%s · 连接后：%s",data.setting("rulesUpdateInterval"),data.setting("rulesUpdateDelay","30s")),Icons.Outlined.Refresh,onClick={updateChoice="rulesUpdateInterval"},trailing={
            Row{IconButton(onClick={vm.updateAllSmartRules()},modifier=Modifier.testTag("smart_update")){Icon(Icons.Outlined.Download,uiText("立即更新规则"))};IconButton(onClick={updateChoice="rulesUpdateDelay"}){Icon(Icons.Outlined.Settings,uiText("规则更新设置"))}}
        })}}
        item(key="smart-merges"){UiCard{UiRow("管理节点汇总组","合并多个分组或指定节点为分流目标",Icons.Outlined.Hub,iconRes=R.drawable.zb_ref_ic_mingcute_group_24,onClick=onMerges,modifier=Modifier.testTag("smart_merges"))}}
        item(key="smart-policies-section"){UiSection("应用策略")}
        drag.order.forEach { itemKey->
            val key=itemKey.toString().removePrefix("smart-policy-")
            val title=policies[key] ?: return@forEach
            item(key=itemKey) {
                val value=smartTarget(data,key)
                UiCard(Modifier.animateItem(placementSpec=if(drag.dragging==itemKey)null else spring()).then(drag.modifier(itemKey)).testTag("smart_card_$key")) {
                    val node=smartLowestNode(data,value,testing)
                    SmartPolicyRow(if(custom.any{it.first==key})title else uiText(title),when(key){"speed"->Icons.Outlined.Speed;"youtube"->Icons.Outlined.PlayCircle;"telegram"->Icons.Outlined.Send;"spotify","tiktok"->Icons.Outlined.MusicNote;"google"->Icons.Outlined.Public;"ai"->Icons.Outlined.AutoAwesome;else->Icons.Outlined.Apps},
                        node,targetLabel(value,data),smartLatencyLabel(data,value,testing),key,onClick={expanded=if(expanded==key)"" else key},onLatency={vm.service.testTarget(value,key)},onTarget={target=key})
                    if(expanded==key) {
                        val bundled=remember(key,vm){com.zane.zanebox.config.builtinSmartRuleText(key){path->vm.getApplication<android.app.Application>().assets.open(path).bufferedReader().use{it.readText()}}}
                        val usesBuiltin=key in com.zane.zanebox.config.builtinSmartRuleFiles && data.setting("smartUrl.$key").isBlank() && (!data.settings.containsKey("smartRules.$key") || com.zane.zanebox.config.isBuiltinSmartRuleText(data.setting("smartRules.$key"),bundled))
                        UiRow("分流目标",targetLabel(value,data),translateSubtitle=false,onClick={target=key},summaryLines=Int.MAX_VALUE,modifier=Modifier.testTag("smart_goal_$key"))
                        node?.let{UiRow("最低延迟节点",it,translateSubtitle=false,chevron=false,summaryLines=Int.MAX_VALUE,modifier=Modifier.testTag("smart_current_$key"))}
                        UiRow("规则来源",data.setting("smartUrl.$key").ifBlank{uiText(if(usesBuiltin)"内置兼容规则组" else "自定义域名规则")},Icons.Outlined.Description,translateSubtitle=false,onClick={ruleSource=key})
                        UiRow("选择应用",if(installedPackages==null)uiText("正在读取应用") else uiText("%s 个有效应用",com.zane.zanebox.config.effectiveSmartPackages(data,key,installedPackages).size),Icons.Outlined.Apps,onClick={apps=key},modifier=Modifier.testTag("smart_apps_$key"))
                        if(custom.any{it.first==key}) {
                            UiRow("重命名",onClick={form("重命名",listOf("名称" to title)){values->require(values[0].isNotBlank());require((builtIn+custom).none{it.first!=key && it.second==values[0]}){"名称已存在"};vm.setting("smartCustom.$key.name",values[0])}})
                            UiRow("删除应用组",onClick={confirmDelete=key})
                        }
                    }
                }
            }
        }
        item(key="smart-add-custom"){OutlinedButton(onClick={form("添加自定义应用组",listOf("名称" to "")){v->require(v[0].isNotBlank()){"请输入名称"};require((builtIn+custom).none{it.second==v[0]}){"名称已存在"};vm.task {val key="custom_${vm.store.nextId()}";vm.store.putSetting("smartCustom.$key.name",v[0]);vm.message.value="已保存"}}},modifier=Modifier.fillMaxWidth().testTag("smart_add_custom")){Icon(Icons.Outlined.Add,null);Text(uiText("添加自定义应用组"))}}
        if(data.setting("serviceMode")!="vpn")item(key="smart-vpn-hint"){Text(uiText("智能应用路由依赖 VPN 模式。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("smart_vpn_hint"))}
    }
    if(target.isNotBlank())TargetPicker("分流目标",smartTarget(data,target),data,{target=""},{vm.selectSmartTarget(target,it)},smart=true)
    if(ruleSource.isNotBlank())RuleSourcePage(ruleSource,data,vm){ruleSource=""}
    if(updateChoice.isNotBlank())ChoiceDialog(if(updateChoice=="rulesUpdateInterval")"自动更新间隔" else "连接后检查",data.setting(updateChoice,if(updateChoice=="rulesUpdateInterval")"24h" else "30s"),
        if(updateChoice=="rulesUpdateInterval")listOf("off" to "关闭","6h" to "6 小时","12h" to "12 小时","24h" to "24 小时","3d" to "3 天","7d" to "7 天")else listOf("0s" to "立即","15s" to "15 秒","30s" to "30 秒","1m" to "1 分钟","5m" to "5 分钟"),{updateChoice=""}){vm.setting(updateChoice,it)}
    if(apps.isNotBlank()) {
        val appKey=apps
        AppsEditor(com.zane.zanebox.config.appPackages(data.setting("smartCustom.$appKey.packages")),{apps=""},scopeData=data,title=uiText("选择应用 · %s",data.setting("smartCustom.$appKey.name").ifBlank{appKey})) { packages->
            vm.edit(onSaved={apps=""},autoApply=false){it.copy(settings=it.settings+("smartCustom.$appKey.packages" to packages.sorted().joinToString("\n")))}
        }
    }
    if(confirmDelete.isNotBlank())UiAlertDialog(onDismissRequest={confirmDelete=""},title={Text(uiText("删除自定义应用组？"))},confirmButton={TextButton(onClick={val key=confirmDelete;vm.edit{d->d.copy(settings=d.settings.filterKeys{!it.startsWith("smartCustom.$key.") && it!="smart.$key.target" && it!="smartUrl.$key" && it!="smartRules.$key"})};confirmDelete=""}){Text(uiText("删除"))}},dismissButton={TextButton(onClick={confirmDelete=""}){Text(uiText("取消"))}})
}

/** Two lines: name + group-lowest latency, then the concrete node + the one target button. Names ellipsize, the latency value is always complete; large fonts grow the row. */
@Composable private fun SmartPolicyRow(title:String,icon:androidx.compose.ui.graphics.vector.ImageVector,node:String?,targetName:String,latency:String?,key:String,
    onClick:()->Unit,onLatency:()->Unit,onTarget:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).testTag("smart_$key").heightIn(min=68.dp).padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
        Icon(icon,null,Modifier.size(24.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.width(14.dp))
        BoxWithConstraints(Modifier.weight(1f)) {
            val half=maxWidth*0.5f
            Column(Modifier.fillMaxWidth()) {
                TitleWithLatency(
                    title={Text(title,fontSize=13.sp,lineHeight=18.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)},
                    latency=latency?.let { label -> {
                        TextButton(onClick=onLatency,modifier=Modifier.defaultMinSize(minHeight=48.dp).testTag("smart_latency_$key"),contentPadding=PaddingValues(horizontal=8.dp)) {
                            LatencyText(uiText(label))
                        }
                    } })
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    if(node!=null)Text(node,Modifier.weight(1f).testTag("smart_node_$key"),fontSize=11.sp,lineHeight=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                    else Spacer(Modifier.weight(1f))
                    TextButton(onClick=onTarget,modifier=Modifier.widthIn(max=half).testTag("smart_target_$key"),contentPadding=PaddingValues(start=8.dp,end=4.dp)) {
                        Text(targetName,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f,fill=false))
                        Icon(Icons.Outlined.ArrowDropDown,null,Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/** The value keeps its full natural width (one unbreakable line), measured from a finite intrinsic width. */
@Composable private fun LatencyText(value:String) {
    Layout({ Text(value,fontSize=12.sp,softWrap=false) }) { measurables,_ ->
        val part=measurables.single().let { it.measure(Constraints.fixedWidth(it.maxIntrinsicWidth(Constraints.Infinity))) }
        layout(part.width,part.height) { part.placeRelative(0,0) }
    }
}

/**
 * Title and latency share a line only while the latency keeps its full natural width and the title keeps a readable minimum;
 * otherwise the latency moves under the title, right-aligned. The value is never given less than it needs.
 */
@Composable private fun TitleWithLatency(title:@Composable ()->Unit,latency:(@Composable ()->Unit)?) {
    if(latency==null) { title();return }
    Layout({ title();latency() },Modifier.fillMaxWidth()) { measurables,constraints ->
        val width=constraints.maxWidth
        val action=measurables[1].measure(Constraints(maxWidth=width))
        val side=width-action.width>=88.dp.roundToPx()
        val head=measurables[0].measure(Constraints(maxWidth=if(side)width-action.width else width))
        if(side) {
            val height=maxOf(head.height,action.height)
            layout(width,height) { head.placeRelative(0,(height-head.height)/2);action.placeRelative(width-action.width,(height-action.height)/2) }
        } else layout(width,head.height+action.height) { head.placeRelative(0,0);action.placeRelative(width-action.width,head.height) }
    }
}

@Composable internal fun SmartMenu(data:AppData,vm:AppViewModel,onOpen:(String)->Unit) {
    UiMenu(listOf(
        (if(data.setting("routeMode")=="global")"退出全局模式" else "全局模式") to {vm.setting("routeMode",if(data.setting("routeMode")=="global")"rule" else "global")},
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
    val language=LocalUiLanguage.current
    val initialUrl=data.setting("smartUrl.$serviceKey")
    val initialRules=remember(serviceKey,data.settings) {
        val bundled=com.zane.zanebox.config.builtinSmartRuleText(serviceKey){path->vm.getApplication<android.app.Application>().assets.open(path).bufferedReader().use{it.readText()}}
        val saved=data.setting("smartRules.$serviceKey")
        if(serviceKey in com.zane.zanebox.config.builtinSmartRuleFiles && data.setting("smartUrl.$serviceKey").isBlank() && (!data.settings.containsKey("smartRules.$serviceKey") || com.zane.zanebox.config.isBuiltinSmartRuleText(saved,bundled)))com.zane.zanebox.config.supportedBuiltinSmartRules(bundled) else saved
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
    var rawEditor by remember{mutableStateOf(false)}
    var domainIndex by remember{mutableStateOf<Int?>(null)}
    var domainType by remember{mutableStateOf("DOMAIN-SUFFIX")}
    var domainValue by remember{mutableStateOf("")}
    var typePicker by remember{mutableStateOf(false)}
    var domainInput by remember{mutableStateOf(false)}
    var domainError by remember{mutableStateOf("")}
    val domainTypes=listOf("DOMAIN" to "完整域名 · DOMAIN","DOMAIN-SUFFIX" to "域名后缀 · DOMAIN-SUFFIX","DOMAIN-KEYWORD" to "域名关键词 · DOMAIN-KEYWORD")
    val ruleLines=remember(rules){rules.lines()}
    fun editDomain(index:Int) {
        val match=ruleLines.getOrNull(index)?.let(::smartDomainRule)
        domainIndex=index;domainType=match?.first ?: "DOMAIN-SUFFIX";domainValue=match?.second.orEmpty();domainError="";typePicker=true
    }
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
        val requestRules=rules
        val baseline=data
        fetching=true
        scope.launch {
            try {
                val fetchedRules=SubscriptionClient.fetchSmartRules(requestUrl,data.settings)
                require(url.trim()==requestUrl && rules==requestRules) { "规则来源或编辑内容已变更，已保留当前规则，请重新获取" }
                vm.edit(onSaved={rules=fetchedRules},onError={error=it}) {d->
                    com.zane.zanebox.config.applySmartRuleUpdate(d,serviceKey,baseline,fetchedRules,sourceUrl=requestUrl)
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
        TextButton(onClick={save(false)},enabled=!busy&&!fetching,modifier=Modifier.testTag("rule_source_save")){Text(uiText("保存"))}
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
                    val compatibility=remember(serviceKey,rules,language){com.zane.zanebox.config.ConfigBuilder.ruleCompatibilityWarnings(serviceKey,rules){service,type->translateText("%s：此规则格式暂不支持 %s；该类条目保留但不参与匹配，其余有效规则仍生效",language,service,type)}}
                    if(compatibility.isNotEmpty())Text(compatibility.joinToString("\n"),color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("rule_source_compatibility"))
                    if(error.isNotBlank())Text(uiText(error),color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("rule_source_error"))
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                        Button(onClick=::fetchAndReplace,enabled=!busy&&!fetching,modifier=Modifier.testTag("rule_source_replace")){Text(uiText("获取并替换"))}
                    }
                    Text(uiText("保存失败会保留当前编辑内容；获取并替换会使用现有订阅 HTTP 管线。"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item{UiCard{
            UiRow("自定义规则",uiText("%s 条",ruleLines.count{it.isNotBlank() && !it.trim().startsWith('#') && !it.trim().startsWith("//")}),Icons.Outlined.AccountTree,trailing={
                TextButton(onClick={editDomain(-1)},modifier=Modifier.testTag("rule_domain_add")){Text(uiText("添加"))}
            })
            UiRow("规则文本 / 批量编辑",if(rawEditor)"收起文本，返回选择式编辑" else "保留注释、IP 规则及其他原始内容",Icons.Outlined.Code,onClick={rawEditor=!rawEditor},modifier=Modifier.testTag("rule_source_raw"))
        }}
        if(rawEditor)item{
            OutlinedTextField(rules,{rules=it;error=""},label={Text(uiText("自定义规则（每行域名或 DOMAIN / IP-CIDR）"))},
                modifier=Modifier.fillMaxWidth().testTag("rule_source_rules"),minLines=8,maxLines=16)
        } else items(ruleLines.indices.filter{ruleLines[it].isNotBlank() && !ruleLines[it].trim().startsWith('#') && !ruleLines[it].trim().startsWith("//")},key={it}) { index ->
            val line=ruleLines[index];val match=smartDomainRule(line)
            UiCard{UiRow(match?.second ?: line,domainTypes.firstOrNull{it.first==match?.first}?.second ?: "原始规则 · 在批量编辑中修改",Icons.Outlined.Description,translateTitle=false,
                onClick=if(match!=null)({editDomain(index)})else ({rawEditor=true}),modifier=Modifier.testTag("rule_domain_$index"),summaryLines=2)}
        }
    }

    if(typePicker)ChoiceDialog("域名匹配类型",domainType,domainTypes,{typePicker=false}){domainType=it;typePicker=false;domainInput=true}
    if(domainInput)UiAlertDialog(onDismissRequest={domainInput=false},title={Text(uiText(if(domainIndex==-1)"添加域名规则" else "编辑域名规则"))},text={
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            UiRow("匹配类型",domainTypes.first{it.first==domainType}.second,onClick={typePicker=true},modifier=Modifier.testTag("rule_domain_type"))
            OutlinedTextField(domainValue,{domainValue=it;domainError=""},label={Text(uiText(if(domainType=="DOMAIN-KEYWORD")"关键词" else "域名"))},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("rule_domain_value"))
            if(domainError.isNotBlank())Text(uiText(domainError),color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={TextButton(onClick={
        val value=domainValue.trim()
        if(value.isBlank() || value.any{it==',' || it.isWhitespace()})domainError="请输入单个域名或关键词，不需要填写规则前缀"
        else {
            val index=domainIndex ?: -1
            val lines=ruleLines.toMutableList()
            val tail=lines.getOrNull(index)?.split(',')?.drop(2).orEmpty()
            val line=(listOf(domainType,value)+tail).joinToString(",")
            if(index in lines.indices)lines[index]=line else lines.add(line)
            rules=lines.joinToString("\n");error="";domainInput=false
        }
    },modifier=Modifier.testTag("rule_domain_confirm")){Text(uiText(if(domainIndex==-1)"添加" else "替换"))}},dismissButton={TextButton(onClick={domainInput=false}){Text(uiText("取消"))}})

    if(catalogOpen)UiAlertDialog(onDismissRequest={catalogOpen=false},title={Text(uiText("Karing 在线规则目录"))},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(catalogQuery,{catalogQuery=it},label={Text(uiText("搜索 Karing 规则"))},singleLine=true,
                modifier=Modifier.fillMaxWidth().testTag("rule_catalog_search"))
            if(catalogLoading)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center){CircularProgressIndicator(Modifier.size(28.dp))}
            if(catalogError.isNotBlank())Text(uiText(catalogError),color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("rule_catalog_error"))
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
