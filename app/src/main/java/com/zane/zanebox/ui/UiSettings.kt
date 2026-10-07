@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.zane.zanebox.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.zane.zanebox.backup.BackupScope
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zane.zanebox.BuildConfig
import com.zane.zanebox.R
import com.zane.zanebox.data.*
import org.json.JSONObject

@Composable internal fun SettingsHub(onOpen:(String)->Unit,clashApi:Boolean=false) {
    Row(Modifier.fillMaxWidth().padding(horizontal=2.dp,vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
        Text(uiText("全部设置"),style=MaterialTheme.typography.labelLarge)
        Text(uiText("已同步"),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val rows=buildList {
        add(listOf("groups","订阅管理","订阅与节点分组",R.drawable.zb_ref_ic_mingcute_group_24,"settings_groups"))
        add(listOf("general","设置","VPN、DNS、TUN 与应用行为",R.drawable.zb_ref_ic_action_settings,"settings_general"))
        if(clashApi)add(listOf("traffic","流量仪表","流量与连接监控",R.drawable.zb_ref_ic_mingcute_route_24,"settings_traffic"))
        add(listOf("logs","日志","运行日志与故障排查",R.drawable.zb_ref_ic_baseline_bug_report_24,"settings_logs"))
        add(listOf("tools","工具","网络测试与诊断工具",R.drawable.zb_ref_baseline_construction_24,"network_tools"))
        add(listOf("document","文档","上游项目文档",R.drawable.zb_ref_ic_mingcute_document_24,"settings_document"))
        add(listOf("about","关于","版本、许可与上游署名",R.drawable.zb_ref_ic_baseline_info_24,"settings_about"))
    }
    UiCard {
        rows.forEachIndexed {i,r->
            UiRow(r[1] as String,r[2] as String,onClick={onOpen(r[0] as String)},modifier=Modifier.testTag(r[4] as String),iconRes=r[3] as Int,minHeight=58.dp,titleSize=12.sp,chevron=false)
            if(i<rows.lastIndex)HorizontalDivider(Modifier.padding(start=64.dp,end=16.dp),thickness=.5.dp,color=MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable internal fun SettingsDestination(page:String,data:AppData,vm:AppViewModel,onDismiss:()->Unit,open:(String)->Unit,
    form:(String,List<Pair<String,String>>,(List<String>)->Unit)->Unit,backup:(BackupScope)->Unit,restore:()->Unit,
    groupEdit:(Group?)->Unit,ruleEdit:(RouteRule?)->Unit,mergeEdit:(MergeGroup?)->Unit,subscriptionEdit:(Group)->Unit,share:(String)->Unit,copy:(String)->Unit,qr:(String)->Unit,exportNodes:(Long?)->Unit,toggle:()->Unit) {
    val context=LocalContext.current
    var confirmation by remember{mutableStateOf<Pair<String,()->Unit>?>(null)}
    var expandedGroups by remember { mutableStateOf(emptySet<Long>()) }
    var selectingGroup by remember { mutableStateOf<Long?>(null) }
    var selectedNodes by remember { mutableStateOf(emptySet<Long>()) }
    LaunchedEffect(data.nodes) { selectedNodes=selectedNodes.intersect(data.nodes.map{it.id}.toSet()) }
    fun deleteNodes(ids:Set<Long>) {
        if(ids.isEmpty())return
        val chains=com.zane.zanebox.subscription.nodeRemovalIds(data.nodes,ids).size-ids.size
        val action={vm.deleteNodes(ids);selectedNodes=emptySet();selectingGroup=null}
        if(ids.size==1 && chains==0 && !data.bool("confirmProfileDelete"))action()
        else confirmation=("删除 ${ids.size} 个节点？"+if(chains>0)"同时删除依赖它们的 $chains 个代理链。" else "") to action
    }
    when(page) {
        "general"->PreferencesPage(data,vm,onDismiss,open,form)
        "apps"->AppsEditor(data.setting("perAppPackages").lines().filter{it.isNotBlank()}.toSet(),onDismiss,modeValue=if(!data.bool("perAppEnabled"))"off" else data.setting("perAppMode","exclude"),onMode={mode->vm.edit{it.copy(settings=it.settings+("perAppEnabled" to (mode!="off").toString())+("perAppMode" to if(mode=="include")"include" else "exclude"))}}){vm.setting("perAppPackages",it.joinToString("\n"));onDismiss()}
        "groups"->{
            val listState=LocalUiListState.current ?: androidx.compose.foundation.lazy.rememberLazyListState()
            val groups=data.groups.sortedBy{it.order}
            val expanded=expandedGroups
            val groupsById=groups.associateBy{it.id}
            val groupDrag=rememberDragSort(groups.map{"manage-group-${it.id}"},listState){vm.reorderGroups(it.map{key->key.toString().substringAfterLast('-').toLong()})}
            val byGroup=remember(data.nodes,data.groups,data.settings.filterKeys{it.contains("sort_group_") || it.startsWith("sort_mode_group_")}) {
                val grouped=data.nodes.groupBy{it.groupId}
                groups.associate{it.id to grouped[it.id].orEmpty().sortedWith(nodeComparator(nodeSortMode(data,it)))}
            }
            val nodesById=remember(data.nodes){data.nodes.associateBy{it.id}}
            val nodeDrags=groups.filter{it.id in expanded}.associate { group -> group.id to key(group.id) {
                rememberDragSort(byGroup[group.id].orEmpty().map{"manage-node-${it.id}"},listState){vm.reorderNodes(it.map{key->key.toString().substringAfterLast('-').toLong()})}
            } }
            UiPageList("订阅管理",onDismiss,action={UiMenu(listOf("更新全部订阅" to vm::updateAllGroups,"创建分组" to {groupEdit(null)}),"groups_menu")},state=listState) {
            if(data.groups.isEmpty())item{UiRow("暂无分组","点击右上角创建分组")}
            groupDrag.order.mapNotNull{groupsById[it.toString().substringAfterLast('-').toLong()]}.forEach { g->
                item(key="manage-group-${g.id}") { UiCard(Modifier.animateItem(placementSpec=if(groupDrag.dragging=="manage-group-${g.id}")null else spring()).then(groupDrag.modifier("manage-group-${g.id}"))) {
                UiRow(g.name,"${data.nodes.count{it.groupId==g.id}} 个节点"+(if(g.subscriptionUrl.isBlank())" · 基础分组" else " · 订阅分组"),Icons.Outlined.Folder,onClick={groupEdit(g)},modifier=Modifier.testTag("manage_group_${g.id}"),trailing={UiSwitch(g.enabled,{enabled->vm.edit{d->d.copy(groups=d.groups.map{if(it.id==g.id)it.copy(enabled=enabled)else it})}})})
                FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                    TextButton(onClick={expandedGroups=if(g.id in expandedGroups)expandedGroups-g.id else expandedGroups+g.id;selectingGroup=null;selectedNodes=emptySet()},modifier=Modifier.testTag("manage_group_nodes_${g.id}")){Text(uiText(if(g.id in expandedGroups)"收起节点" else "节点"))}
                    TextButton(onClick={subscriptionEdit(g)},modifier=Modifier.testTag("subscription_options_${g.id}")){Text(uiText("订阅选项"))}
                    TextButton(onClick={vm.updateGroup(g)},enabled=g.subscriptionUrl.isNotBlank()){Text(uiText("更新"))}
                    UiMenu(listOf("分享订阅" to {share(subscriptionShareLink(g).ifBlank{shareText(data.nodes.filter{it.groupId==g.id})})},"复制订阅链接" to {copy(subscriptionShareLink(g))},"订阅二维码" to {qr(subscriptionShareLink(g))},"复制节点配置" to {copy(shareText(data.nodes.filter{it.groupId==g.id}))},"导出节点" to {exportNodes(g.id)},"清空节点" to {confirmation="清空分组 ${g.name} 中的全部节点、依赖代理链？" to {vm.clearGroup(g.id)}},"删除分组" to {if(data.bool("confirmProfileDelete"))confirmation="删除分组 ${g.name} 及其全部节点、依赖代理链？" to {vm.deleteGroup(g.id)}else vm.deleteGroup(g.id)}),"manage_group_menu_${g.id}")
                }
                }}
                if(g.id in expanded) {
                    val drag=nodeDrags[g.id] ?: return@forEach
                    val nodes=drag.order.mapNotNull{nodesById[it.toString().substringAfterLast('-').toLong()]}
                    item(key="manage-actions-${g.id}") { NodeSelectionBar(selectingGroup==g.id,if(selectingGroup==g.id)selectedNodes.size else 0,"manage_${g.id}",{selectingGroup=if(selectingGroup==g.id)null else g.id;selectedNodes=emptySet()},{selectedNodes=nodes.map{it.id}.toSet()},{deleteNodes(selectedNodes)}) }
                    if(nodes.isEmpty())item { UiRow("暂无节点") }
                    items(nodes,key={"manage-node-${it.id}"}) { node -> UiCard(Modifier.animateItem(placementSpec=if(drag.dragging=="manage-node-${node.id}")null else spring()).then(drag.modifier("manage-node-${node.id}"))) {
                        UiRow(node.name,nodeTestLabel(node),onClick={selectedNodes=if(selectingGroup!=g.id)setOf(node.id)else if(node.id in selectedNodes)selectedNodes-node.id else selectedNodes+node.id;selectingGroup=g.id},modifier=Modifier.testTag("manage_node_${node.id}"),chevron=false,trailing={
                            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                                if(selectingGroup==g.id)Checkbox(node.id in selectedNodes,{checked->selectedNodes=if(checked)selectedNodes+node.id else selectedNodes-node.id},modifier=Modifier.testTag("manage_node_check_${node.id}"))
                                UiMenu(listOf("删除" to {deleteNodes(setOf(node.id))}),"manage_node_menu_${node.id}")
                            }
                        })
                    }}
                }
            }
        }
        }
        "rules"->{
            val listState=LocalUiListState.current ?: androidx.compose.foundation.lazy.rememberLazyListState()
            val rulesById=remember(data.rules){data.rules.associateBy{it.id}}
            val drags=listOf(true,false).associate { front -> front to key(front) {
                rememberDragSort(data.rules.filter{it.prioritize==front}.sortedBy{it.order}.map{"rule-${it.id}"},listState){vm.reorderRules(front,it.map{key->key.toString().substringAfterLast('-').toLong()})}
            } }
            UiPageList("路由规则",onDismiss,action={IconButton(onClick={ruleEdit(null)},modifier=Modifier.testTag("add_rule")){Icon(Icons.Outlined.Add,"添加规则")};UiMenu(listOf("重置全部规则" to {confirmation="删除全部路由规则？" to {vm.edit { it.copy(rules=emptyList()) } }},"管理路由资产" to {open("assets")}),"rules_menu")},state=listState) {
            if(data.rules.isEmpty())item{UiRow("暂无路由规则","点击右上角添加规则")}
            listOf(true,false).forEach { front->
                item{UiSection(if(front)"前置路由规则" else "后置路由规则")}
            val drag=drags.getValue(front)
            items(drag.order.mapNotNull{rulesById[it.toString().substringAfterLast('-').toLong()]},key={"rule-${it.id}"}){r->UiCard(Modifier.animateItem(placementSpec=if(drag.dragging=="rule-${r.id}")null else spring()).then(drag.modifier("rule-${r.id}"))){
                UiRow(r.name,targetName(r.outbound,data),Icons.Outlined.AccountTree,onClick={ruleEdit(r)},modifier=Modifier.testTag("rule_${r.id}"),trailing={UiSwitch(r.enabled,{value->vm.edit{d->d.copy(rules=d.rules.map{if(it.id==r.id)it.copy(enabled=value)else it})}})})
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.End) {
                    UiMenu(listOf(
                        "编辑" to {ruleEdit(r)},
                        "删除" to {
                            confirmation="删除规则 ${r.name}？" to {
                                vm.edit{d->d.copy(rules=d.rules.filter{it.id!=r.id})}
                            }
                        }
                    ))
                }
            }}
            }
            item{UiCard{UiRow("管理路由资产","本地 GeoIP / Geosite 与规则集",Icons.Outlined.Storage,onClick={open("assets")})}}
        }
        }
        "merges"->UiPageList("节点汇总组",onDismiss,action={IconButton(onClick={mergeEdit(null)},modifier=Modifier.testTag("add_merge")){Icon(Icons.Outlined.Add,"添加汇总组")}}) {
            if(data.merges.isEmpty())item{UiRow("暂无汇总组","合并多个分组或指定节点为分流目标")}
            items(data.merges,key={it.id}){m->UiCard{UiRow(m.name,"${m.nodeIds.size} 个指定节点 · ${m.groupIds.size} 个分组 · "+if(m.mode=="urltest")"自动测速" else "手动选择",Icons.Outlined.Hub,onClick={mergeEdit(m)},modifier=Modifier.testTag("merge_${m.id}"),trailing={UiMenu(listOf("编辑" to {mergeEdit(m)},"删除" to {confirmation="删除汇总组 ${m.name}？" to {vm.deleteMerge(m.id)}}))})}}
        }
        "backup"->UiPageList("备份与恢复",onDismiss) {item{BackupRows(data,vm,open,backup,restore)}}
        "webdav-settings"->UiPageList("WebDAV 设置",onDismiss) {
            items(listOf("webdavUrl" to "服务器 URL","webdavUser" to "用户名","webdavPassword" to "密码")){(key,label)->UiCard{UiRow(label,if(key=="webdavPassword")"••••••" else data.setting(key).ifBlank{"未设置"},onClick={form(label,listOf(label to data.setting(key))){vm.setting(key,it[0])}},modifier=Modifier.testTag(key))}}
            item{UiCard{UiRow("测试连接",icon=Icons.Outlined.Link,onClick={vm.listWebdav();open("webdav")})}}
        }
        "webdav"->WebdavDialog(vm,onDismiss)
        "traffic"->TrafficDialog(vm,onDismiss)
        "connections"->ConnectionsDialog(vm,onDismiss)
        "tools"->{var tab by remember{mutableIntStateOf(0)}
            UiPageList("工具",onDismiss) {
                item{TabRow(tab){Tab(tab==0,{tab=0},text={Text(uiText("网络"))},modifier=Modifier.testTag("tools_network_tab"));Tab(tab==1,{tab=1},text={Text(uiText("备份"))},modifier=Modifier.testTag("tools_backup_tab"))}}
                if(tab==0)item{UiCard{
                    UiRow("STUN / NAT 测试","检测当前网络映射与过滤行为",Icons.Outlined.NetworkCheck,onClick={open("stun")},modifier=Modifier.testTag("stun_tool"))
                    UiRow("连接监控","当前代理连接",Icons.Outlined.Cable,onClick={vm.service.refreshConnections();open("connections")})
                    UiRow("流量统计","应用、域名与节点",Icons.Outlined.BarChart,onClick={open("traffic")})
                    UiRow("Clash 面板","本地 YACD",Icons.Outlined.Dashboard,onClick={vm.service.openPanel();open("panel")})
                }} else item{BackupRows(data,vm,open,backup,restore)}
            }
        }
        "stun"->ToolsPanel(vm,onDismiss)
        "panel"->LocalPanelDialog(vm,onDismiss)
        "logs"->{val logs by vm.service.logs.collectAsStateWithLifecycle();LaunchedEffect(Unit){vm.service.refreshLogs()}
            UiPageList("日志",onDismiss,action={UiMenu(listOf("刷新" to {vm.service.refreshLogs()},"分享日志" to {share(logs.joinToString("\n"))},"发送 logcat" to {vm.service.systemLogs()},"清空日志" to {confirmation="清空当前运行日志？" to {vm.service.clearLogs()}}),"log_menu")}){
                if(logs.isEmpty())item{Text(uiText("暂无日志"))}
                items(logs){androidx.compose.foundation.text.selection.SelectionContainer{Text(it,style=MaterialTheme.typography.bodySmall)}}
            }
        }
        "routing-probe","site-cards","ruleset-preview","penetration","geo-status"->UiDiagnostics(page,data,vm,onDismiss)
        "assets"->AssetPage(data,vm,onDismiss,form)
        "document"->UiPageList("文档",onDismiss){item{UiCard{UiRow("sing-box 文档","协议与路由配置参考",Icons.Outlined.Description,onClick={context.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://sing-box.sagernet.org/")))})}}}
        "about"->UiPageList("关于 Links",onDismiss) {
            item{UiCard{UiRow("Links",BuildConfig.VERSION_NAME+" / "+BuildConfig.VERSION_CODE,Icons.Outlined.Info);UiRow("内核","AnyBox libcore / sing-box",Icons.Outlined.Memory);UiRow("UI 基线","AnyBox 2.1.9",Icons.Outlined.Palette)}}
            item{UiCard{UiRow("开源许可与致谢","sing-box、SagerNet、AndroidX 与 Kotlin",Icons.Outlined.Description,onClick={open("licenses")})}}
        }
        "licenses"->UiPageList("开源许可",onDismiss) {item{Text(uiText("Links 使用 AnyBox libcore / sing-box（GPL-3.0），AndroidX（Apache-2.0）、Kotlin（Apache-2.0）、OkHttp（Apache-2.0）、SnakeYAML（Apache-2.0）和 ZXing（Apache-2.0）。完整上游源码与许可证保存在本工程 native 目录。"));}}
    }
    confirmation?.let{(title,action)->UiAlertDialog(onDismissRequest={confirmation=null},title={Text(uiText("确认操作"))},text={Text(title)},confirmButton={TextButton(onClick={action();confirmation=null}){Text(uiText("确认"))}},dismissButton={TextButton(onClick={confirmation=null}){Text(uiText("取消"))}})}
}

@Composable private fun PreferencesPage(data:AppData,vm:AppViewModel,onDismiss:()->Unit,open:(String)->Unit,form:(String,List<Pair<String,String>>,(List<String>)->Unit)->Unit) {
    var selecting by remember{mutableStateOf<Preference?>(null)}
    var confirmation by remember{mutableStateOf<Pair<String,()->Unit>?>(null)}
    val context=LocalContext.current
    val sections=preferenceSections(data)
    UiPageList("设置",onDismiss) {
        sections.forEach{(title,prefs)->
            item{Text(uiText(title),Modifier.padding(start=4.dp,top=10.dp,bottom=0.dp),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)}
            item{Surface(shape=MaterialTheme.shapes.large,color=if(MaterialTheme.colorScheme.background.luminance()>.5f)Color.White else MaterialTheme.colorScheme.surface.copy(alpha=1f)) {
                Column { prefs.forEachIndexed{index,p->
                    val value=if(p.key=="ipv6Mode")data.setting("dnsStrategy",if(data.bool("ipv6"))"prefer_ipv4" else "ipv4_only") else data.setting(p.key,p.default)
                    val visual=preferenceAppearance[p.key]
                    val summary=if(p.key=="perAppEnabled")(if(!data.bool("perAppEnabled"))"关闭" else if(data.setting("perAppMode","exclude")=="include")"代理" else "绕过")+" · ${data.setting("perAppPackages").lines().count{it.isNotBlank()}} 个应用" else p.availability.ifBlank{if(p.boolean)visual?.summary.orEmpty() else p.choices.firstOrNull{it.first==value}?.second ?:value.ifBlank{"未设置"}}
                    fun setToggle(enabled:Boolean) {
                        if(p.key=="appendHttpProxy" && enabled && data.bool("disableMixedInbound"))vm.message.value="请先开启本地 mixed 入口"
                        else if(p.key=="disableMixedInbound" && enabled && data.bool("appendHttpProxy"))vm.message.value="请先关闭追加 HTTP 代理"
                        else if(p.key=="statsEnabled")vm.service.setTrafficEnabled(enabled) else vm.setting(p.key,enabled.toString())
                    }
                    UiRow(if(p.key=="showBottomBar")p.title else visual?.title ?:p.title,summary,modifier=Modifier.testTag("setting_${p.key}"),iconRes=visual?.icon ?:0,reserveIcon=true,minHeight=58.dp,chevron=p.key=="perAppEnabled",summaryLines=2,
                        onClick=if(p.availability.isNotBlank())null else ({if(p.key=="perAppEnabled")open("apps") else if(p.boolean)setToggle(!data.bool(p.key,p.default.toBoolean())) else if(p.choices.isNotEmpty())selecting=p else form(visual?.title ?:p.title,listOf(p.title to value)){v->validatePreference(p.key,v[0]);vm.setting(p.key,v[0])}}),
                        trailing=if(p.key=="perAppEnabled")({UiSwitch(data.bool("perAppEnabled"),null,Modifier.testTag("per_app_indicator"))})else if(p.boolean)({Box(Modifier.size(width=48.dp,height=36.dp),contentAlignment=androidx.compose.ui.Alignment.Center){UiSwitch(data.bool(p.key,p.default.toBoolean()),::setToggle,modifier=Modifier)}})else null)
                    if(index<prefs.lastIndex)HorizontalDivider(Modifier.padding(start=64.dp,end=16.dp),thickness=.5.dp,color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))
                }}
            }}
            if(title=="代理共享")item{UiCard{UiRow("局域网地址",lanAddresses());UiRow("共享代理端口",if(data.bool("shareEnabled"))data.setting("sharePort")else data.setting("mixedPort"))}}
        }
        item{UiSection("数据管理")}
        item{UiCard{UiRow("重置设置","保留节点、分组和路由规则",onClick={confirmation="将应用设置恢复默认值？" to {vm.resetSettings()}},modifier=Modifier.testTag("reset_settings"));UiRow("清除缓存",onClick={confirmation="清除应用缓存？" to {vm.clearCache()}},modifier=Modifier.testTag("clear_cache"));UiRow("恢复出厂设置","清除节点、分组、规则和设置",onClick={confirmation="清除全部应用配置？此操作会断开代理。" to {vm.factoryReset()}},modifier=Modifier.testTag("factory_reset"))}}
    }
    selecting?.let{p->ChoiceDialog(p.title,data.setting(p.key,p.default),p.choices,{selecting=null}){value->
        if(p.key=="serviceMode" && vm.service.snapshot.value.state in 1..3)vm.message.value="切换服务模式前请先断开连接"
        else runCatching {if(p.key=="launcherIcon")applyLauncherIcon(context,value);vm.setting(p.key,value)}.onFailure {vm.message.value=it.message ?: "设置失败"}
    }}

    confirmation?.let{(title,action)->UiAlertDialog(onDismissRequest={confirmation=null},title={Text(uiText("确认操作"))},text={Text(uiText(title))},confirmButton={TextButton(onClick={action();confirmation=null}){Text(uiText("确认"))}},dismissButton={TextButton(onClick={confirmation=null}){Text(uiText("取消"))}})}

}
internal fun validatePreference(key:String,value:String) {
    if(key.startsWith("nodeRegion."))require(value in listOf("","hk","us","kr","jp","sg","tw")) { "区域须为 hk/us/kr/jp/sg/tw，或留空自动" }
    when(key) {
        "mixedPort","sharePort","apiPort"->require(value.toIntOrNull() in 1..65535){"端口须为 1–65535"}
        "mtu"->require(value.toIntOrNull() in 576..65535){"MTU 须为 576–65535"}
        "testTimeout"->require(value.toIntOrNull() in 1000..60000){"超时须为 1000–60000 毫秒"}
        "testConcurrency"->require(value.toIntOrNull() in 1..16){"并发须为 1–16"}
        "muxMaxStreams"->require(value.toIntOrNull() in 1..1024){"流数须为 1–1024"}
        "urlTestTolerance"->require(value.toIntOrNull() in 0..65535){"容差须为 0–65535"}
        "urlTestInterval"->require(value.matches(Regex("[1-9][0-9]*(ms|s|m|h)"))){"间隔无效"}
        "globalCustomConfig"->if(value.isNotBlank())JSONObject(value)
        "testUrl","rulesGeositeUrl","rulesGeoipUrl"->{val u=java.net.URI(value);require(u.scheme in listOf("http","https") && !u.host.isNullOrBlank() && u.userInfo==null){ "请输入有效 URL" }}
    }
}

@Composable private fun BackupRows(data:AppData,vm:AppViewModel,open:(String)->Unit,backup:(BackupScope)->Unit,restore:()->Unit) {
    var configurations by remember{mutableStateOf(true)};var rules by remember{mutableStateOf(true)};var settings by remember{mutableStateOf(true)}
    val scope=BackupScope(configurations,rules,settings)
    fun export() { runCatching{com.zane.zanebox.backup.backupSelection(data,scope);backup(scope)}.onFailure{vm.message.value=it.message ?: "备份范围无效"} }
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        UiSection("本地备份")
        UiCard {
            UiRow("组与配置",trailing={Checkbox(configurations,{configurations=it},Modifier.testTag("backup_configurations"))})
            UiRow("路由规则",trailing={Checkbox(rules,{rules=it},Modifier.testTag("backup_rules"))})
            UiRow("设置",trailing={Checkbox(settings,{settings=it},Modifier.testTag("backup_settings"))})
            UiRow("分享备份","按所选范围分享 ZIP",Icons.Outlined.Share,onClick={vm.shareBackup(scope)},modifier=Modifier.testTag("backup_share"))
            UiRow("导出备份","节点、订阅、规则与设置",Icons.Outlined.Save,onClick=::export,modifier=Modifier.testTag("backup_export"))
            UiRow("导入备份","验证后替换当前配置",Icons.Outlined.Restore,onClick=restore,modifier=Modifier.testTag("backup_import"))
        }
        Text(uiText("恢复时会替换全部配置；未勾选的数据不包含在备份中。"),style=MaterialTheme.typography.bodySmall)
        UiSection("WebDAV")
        UiCard{UiRow("WebDAV 设置",data.setting("webdavUrl").ifBlank{"尚未配置"},Icons.Outlined.Cloud,onClick={open("webdav-settings")});UiRow("备份到 WebDAV","完整应用配置",Icons.Outlined.CloudUpload,onClick=vm::uploadWebdav);UiRow("从 WebDAV 恢复",icon=Icons.Outlined.CloudDownload,onClick={vm.listWebdav();open("webdav")})}
    }
}

@Composable private fun AssetPage(data:AppData,vm:AppViewModel,onDismiss:()->Unit,form:(String,List<Pair<String,String>>,(List<String>)->Unit)->Unit) {
    val context=LocalContext.current
    var importKind by remember{mutableStateOf("geoip")};var providers by remember{mutableStateOf(false)}
    val revision by vm.service.assetRevision.collectAsStateWithLifecycle()
    val files=remember(revision){listOf("geoip","geosite").associateWith{java.io.File(context.filesDir,"core-assets/$it.db").length()}}
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->uri?.let{vm.importAsset(importKind,it)}}
    val providerPreference=preferenceSections(data).flatMap{it.second}.first{it.key=="rulesProvider"}
    UiPageList("路由资产",onDismiss) {
        item{UiCard{UiRow("路由资源更新源",providerPreference.choices.firstOrNull{it.first==data.setting("rulesProvider")}?.second.orEmpty(),onClick={providers=true},modifier=Modifier.testTag("asset_provider"))}}
        items(listOf("geoip" to "GeoIP","geosite" to "Geosite")){(kind,title)->
            UiCard {
                UiRow(title,if((files[kind] ?:0)>0)"本地资产 · ${bytes(files[kind]!!)}" else "连接时解压内置资产",Icons.Outlined.Storage)
                UiRow("自定义 URL",data.setting(if(kind=="geoip")"rulesGeoipUrl" else "rulesGeositeUrl"),onClick={val key=if(kind=="geoip")"rulesGeoipUrl" else "rulesGeositeUrl";form("$title URL",listOf("URL" to data.setting(key))){validatePreference(key,it[0]);vm.setting(key,it[0])}})
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                    TextButton(onClick={importKind=kind;importer.launch(arrayOf("*/*"))},modifier=Modifier.testTag("asset_import_$kind")){Text(uiText("从文件导入"))}
                    TextButton(onClick={vm.downloadAsset(kind)},modifier=Modifier.testTag("asset_download_$kind")){Text(uiText("下载更新"))}
                }
            }
        }
        item{Text(uiText("可保持连接下载；下载后自动断开、校验并应用资源，再重启代理。失败恢复原文件；未连接时更新不会启动代理。"),style=MaterialTheme.typography.bodySmall)}
    }
    if(providers)ChoiceDialog("路由资源更新源",data.setting("rulesProvider"),providerPreference.choices,{providers=false}){vm.setting("rulesProvider",it)}
}

private fun lanAddresses():String=runCatching {
    java.net.NetworkInterface.getNetworkInterfaces().toList().filter{it.isUp && !it.isLoopback && !it.name.startsWith("tun")}.flatMap{network->network.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>().filter{!it.isLoopbackAddress}.map{"${network.name}: ${it.hostAddress}"}}.joinToString(" · ").ifBlank{"暂无可用地址"}
}.getOrDefault("暂无可用地址")
