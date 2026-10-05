@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.zane.zanebox.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.zane.zanebox.backup.BackupScope
import androidx.compose.foundation.layout.*
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
    when(page) {
        "general"->PreferencesPage(data,vm,onDismiss,open,form)
        "apps"->AppsEditor(data.setting("perAppPackages").lines().filter{it.isNotBlank()}.toSet(),onDismiss,modeValue=if(!data.bool("perAppEnabled"))"off" else data.setting("perAppMode","exclude"),onMode={mode->vm.edit{it.copy(settings=it.settings+("perAppEnabled" to (mode!="off").toString())+("perAppMode" to if(mode=="include")"include" else "exclude"))}}){vm.setting("perAppPackages",it.joinToString("\n"));onDismiss()}
        "groups"->UiPageList("订阅管理",onDismiss,action={UiMenu(listOf("更新全部订阅" to vm::updateAllGroups,"创建分组" to {groupEdit(null)}),"groups_menu")}) {
            if(data.groups.isEmpty())item{UiRow("暂无分组","点击右上角创建分组")}
            items(data.groups.sortedBy{it.order},key={it.id}){g->UiCard{
                UiRow(g.name,"${data.nodes.count{it.groupId==g.id}} 个节点"+(if(g.subscriptionUrl.isBlank())" · 基础分组" else " · 订阅分组"),Icons.Outlined.Folder,onClick={groupEdit(g)},modifier=Modifier.testTag("manage_group_${g.id}"),trailing={UiSwitch(g.enabled,{enabled->vm.edit{d->d.copy(groups=d.groups.map{if(it.id==g.id)it.copy(enabled=enabled)else it})}})})
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                    TextButton(onClick={subscriptionEdit(g)},modifier=Modifier.testTag("subscription_options_${g.id}")){Text(uiText("订阅选项"))}
                    TextButton(onClick={vm.updateGroup(g)},enabled=g.subscriptionUrl.isNotBlank()){Text(uiText("更新"))}
                    UiMenu(listOf("分享订阅" to {share(subscriptionShareLink(g).ifBlank{shareText(data.nodes.filter{it.groupId==g.id})})},"复制订阅链接" to {copy(subscriptionShareLink(g))},"订阅二维码" to {qr(subscriptionShareLink(g))},"复制节点配置" to {copy(shareText(data.nodes.filter{it.groupId==g.id}))},"导出节点" to {exportNodes(g.id)},"上移" to {vm.edit{d->val list=d.groups.sortedBy{it.order}.toMutableList();val i=list.indexOfFirst{it.id==g.id};if(i>0)java.util.Collections.swap(list,i,i-1);d.copy(groups=list.mapIndexed{j,v->v.copy(order=j)})}},"清空节点" to {confirmation="清空分组 ${g.name} 中的全部节点？" to {vm.clearGroup(g.id)}},"删除分组" to {if(data.bool("confirmProfileDelete",true))confirmation="删除分组 ${g.name} 及其全部节点？" to {vm.deleteGroup(g.id)}else vm.deleteGroup(g.id)}),"manage_group_menu_${g.id}")
                }
            }}
        }
        "rules"->UiPageList("路由规则",onDismiss,action={IconButton(onClick={ruleEdit(null)},modifier=Modifier.testTag("add_rule")){Icon(Icons.Outlined.Add,"添加规则")};UiMenu(listOf("重置全部规则" to {confirmation="删除全部路由规则？" to {vm.edit { it.copy(rules=emptyList()) } }},"管理路由资产" to {open("assets")}),"rules_menu")}) {
            if(data.rules.isEmpty())item{UiRow("暂无路由规则","点击右上角添加规则")}
            items(data.rules.sortedBy{it.order},key={it.id}){r->UiCard{
                UiRow(r.name,targetName(r.outbound,data),Icons.Outlined.AccountTree,onClick={ruleEdit(r)},modifier=Modifier.testTag("rule_${r.id}"),trailing={UiSwitch(r.enabled,{value->vm.edit{d->d.copy(rules=d.rules.map{if(it.id==r.id)it.copy(enabled=value)else it})}})})
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.End) {
                    UiMenu(listOf(
                        "编辑" to {ruleEdit(r)},
                        "上移" to {vm.edit { d ->
                            val list=d.rules.sortedBy{it.order}.toMutableList()
                            val i=list.indexOfFirst{it.id==r.id}
                            if(i>0)java.util.Collections.swap(list,i,i-1)
                            d.copy(rules=list.mapIndexed{j,v->v.copy(order=j)})
                        }},
                        "删除" to {
                            confirmation="删除规则 ${r.name}？" to {
                                vm.edit{d->d.copy(rules=d.rules.filter{it.id!=r.id})}
                            }
                        }
                    ))
                }
            }}
            item{UiCard{UiRow("管理路由资产","本地 GeoIP / Geosite 与规则集",Icons.Outlined.Storage,onClick={open("assets")})}}
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
                if(tab==0)item{UiCard{UiRow("STUN / NAT 测试","检测当前网络映射与过滤行为",Icons.Outlined.NetworkCheck,onClick={open("stun")},modifier=Modifier.testTag("stun_tool"));UiRow("连接监控","当前代理连接",Icons.Outlined.Cable,onClick={vm.service.refreshConnections();open("connections")});UiRow("流量统计","应用、域名与节点",Icons.Outlined.BarChart,onClick={open("traffic")});UiRow("Clash 面板","本地 YACD",Icons.Outlined.Dashboard,onClick={vm.service.openPanel();open("panel")})}}
                else item{BackupRows(data,vm,open,backup,restore)}
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
        "about"->UiPageList("关于 zanebox",onDismiss) {
            item{UiCard{UiRow("zanebox",BuildConfig.VERSION_NAME+" / "+BuildConfig.VERSION_CODE,Icons.Outlined.Info);UiRow("内核","AnyBox libcore / sing-box",Icons.Outlined.Memory);UiRow("UI 基线","AnyBox 2.1.9",Icons.Outlined.Palette)}}
            item{UiCard{UiRow("开源许可与致谢","sing-box、SagerNet、AndroidX 与 Kotlin",Icons.Outlined.Description,onClick={open("licenses")})}}
        }
        "licenses"->UiPageList("开源许可",onDismiss) {item{Text(uiText("zanebox 使用 AnyBox libcore / sing-box（GPL-3.0），AndroidX（Apache-2.0）、Kotlin（Apache-2.0）、OkHttp（Apache-2.0）、SnakeYAML（Apache-2.0）和 ZXing（Apache-2.0）。完整上游源码与许可证保存在本工程 native 目录。"));}}
    }
    confirmation?.let{(title,action)->UiAlertDialog(onDismissRequest={confirmation=null},title={Text(uiText("确认操作"))},text={Text(title)},confirmButton={TextButton(onClick={action();confirmation=null}){Text(uiText("确认"))}},dismissButton={TextButton(onClick={confirmation=null}){Text(uiText("取消"))}})}
}

@Composable private fun PreferencesPage(data:AppData,vm:AppViewModel,onDismiss:()->Unit,open:(String)->Unit,form:(String,List<Pair<String,String>>,(List<String>)->Unit)->Unit) {
    var selecting by remember{mutableStateOf<Preference?>(null)}
    var colorPicker by remember{mutableStateOf(false)}
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
                    val summary=p.availability.ifBlank{if(p.boolean)visual?.summary.orEmpty() else p.choices.firstOrNull{it.first==value}?.second ?:value.ifBlank{if(p.key=="appTheme")"跟随皮肤" else "未设置"}}
                    fun setToggle(enabled:Boolean) {
                        if(p.key=="appendHttpProxy" && enabled && data.bool("disableMixedInbound"))vm.message.value="请先开启本地 mixed 入口"
                        else if(p.key=="disableMixedInbound" && enabled && data.bool("appendHttpProxy"))vm.message.value="请先关闭追加 HTTP 代理"
                        else if(p.key=="statsEnabled")vm.service.setTrafficEnabled(enabled) else vm.setting(p.key,enabled.toString())
                    }
                    UiRow(if(p.key=="showBottomBar")p.title else visual?.title ?:p.title,summary,modifier=Modifier.testTag("setting_${p.key}"),iconRes=visual?.icon ?:0,reserveIcon=true,minHeight=58.dp,chevron=false,summaryLines=2,
                        onClick=if(p.availability.isNotBlank())null else ({if(p.boolean)setToggle(!data.bool(p.key,p.default.toBoolean())) else if(p.key=="appTheme")colorPicker=true else if(p.choices.isNotEmpty())selecting=p else form(visual?.title ?:p.title,listOf(p.title to value)){v->validatePreference(p.key,v[0]);vm.setting(p.key,v[0])}}),
                        trailing=if(p.boolean)({Box(Modifier.size(width=48.dp,height=36.dp),contentAlignment=androidx.compose.ui.Alignment.Center){UiSwitch(data.bool(p.key,p.default.toBoolean()),::setToggle,modifier=Modifier)}})else if(p.key=="appTheme")({Box(Modifier.size(26.dp).border(2.5.dp,MaterialTheme.colorScheme.primary,androidx.compose.foundation.shape.CircleShape),contentAlignment=androidx.compose.ui.Alignment.Center){Surface(shape=androidx.compose.foundation.shape.CircleShape,color=MaterialTheme.colorScheme.primary,modifier=Modifier.size(8.dp)) {}}})else null)
                    if(index<prefs.lastIndex)HorizontalDivider(Modifier.padding(start=64.dp,end=16.dp),thickness=.5.dp,color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))
                }}
            }}
            if(title=="路由设置")item{UiCard{UiRow("选择应用", "已选 ${data.setting("perAppPackages").lines().count{it.isNotBlank()}} 个应用",Icons.Outlined.Apps,onClick={open("apps")},modifier=Modifier.testTag("select_apps"))}}
            if(title=="代理共享")item{UiCard{UiRow("局域网地址",lanAddresses());UiRow("共享代理端口",if(data.bool("shareEnabled"))data.setting("sharePort","2081")else data.setting("mixedPort","2080"))}}
        }
        item{UiSection("数据管理")}
        item{UiCard{UiRow("备份与恢复",icon=Icons.Outlined.Backup,onClick={open("backup")});UiRow("WebDAV 设置",icon=Icons.Outlined.Cloud,onClick={open("webdav-settings")})}}
        item{UiCard{UiRow("重置设置","保留节点、分组和路由规则",onClick={confirmation="将应用设置恢复默认值？" to {vm.resetSettings()}},modifier=Modifier.testTag("reset_settings"));UiRow("清除缓存",onClick={confirmation="清除应用缓存？" to {vm.clearCache()}},modifier=Modifier.testTag("clear_cache"));UiRow("恢复出厂设置","清除节点、分组、规则和设置",onClick={confirmation="清除全部应用配置？此操作会断开代理。" to {vm.factoryReset()}},modifier=Modifier.testTag("factory_reset"))}}
    }
    selecting?.let{p->ChoiceDialog(p.title,data.setting(p.key,p.default),p.choices,{selecting=null}){value->
        if(p.key=="serviceMode" && vm.service.snapshot.value.state in 1..3)vm.message.value="切换服务模式前请先断开连接"
        else runCatching {if(p.key=="launcherIcon")applyLauncherIcon(context,value);vm.setting(p.key,value)}.onFailure {vm.message.value=it.message ?: "设置失败"}
    }}
    if(colorPicker)ThemeColorDialog(data.setting("appTheme"),{colorPicker=false},{value->vm.setting("appTheme",value);colorPicker=false}) {
        colorPicker=false;form("主题颜色",listOf("#RRGGBB / #AARRGGBB" to data.setting("appTheme"))){values->validatePreference("appTheme",values[0]);vm.setting("appTheme",values[0])}
    }
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
        "appTheme"->require(value.isBlank() || value.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))){"请输入 #RRGGBB 或 #AARRGGBB，留空跟随皮肤"}
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
        item{UiCard{UiRow("路由资源更新源",providerPreference.choices.firstOrNull{it.first==data.setting("rulesProvider","0")}?.second.orEmpty(),onClick={providers=true},modifier=Modifier.testTag("asset_provider"))}}
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
        item{Text(uiText("更新前需断开代理；资源通过内核校验后替换，失败保留原文件。"),style=MaterialTheme.typography.bodySmall)}
    }
    if(providers)ChoiceDialog("路由资源更新源",data.setting("rulesProvider","0"),providerPreference.choices,{providers=false}){vm.setting("rulesProvider",it)}
}

private fun lanAddresses():String=runCatching {
    java.net.NetworkInterface.getNetworkInterfaces().toList().filter{it.isUp && !it.isLoopback && !it.name.startsWith("tun")}.flatMap{network->network.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>().filter{!it.isLoopbackAddress}.map{"${network.name}: ${it.hostAddress}"}}.joinToString(" · ").ifBlank{"暂无可用地址"}
}.getOrDefault("暂无可用地址")

@Composable private fun ThemeColorDialog(current:String,onDismiss:()->Unit,onChoose:(String)->Unit,custom:()->Unit) {
    UiAlertDialog(modifier=Modifier.semantics{testTagsAsResourceId=true}.testTag("theme_color_picker"),onDismissRequest=onDismiss,title={Text(uiText("主题颜色"))},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        themePresetColors.chunked(5).forEachIndexed{row,colors->Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            colors.forEachIndexed{column,hex-> val color=Color(("FF"+hex.removePrefix("#")).toLong(16))
                IconButton(onClick={onChoose(hex)},modifier=Modifier.size(48.dp).semantics{contentDescription=hex}.testTag("theme_color_${row*5+column+1}")) {
                    Surface(shape=androidx.compose.foundation.shape.CircleShape,color=color,modifier=Modifier.size(32.dp)) {if(current==hex)Icon(Icons.Outlined.Check,null,tint=Color.White)}
                }
            }
        }}
        TextButton(onClick={onChoose("")},modifier=Modifier.testTag("theme_color_skin")){Text(uiText("跟随皮肤"))}
        TextButton(onClick=custom,modifier=Modifier.testTag("theme_color_custom")){Text(uiText("自定义颜色"))}
    }},confirmButton={TextButton(onClick=onDismiss){Text(uiText("取消"))}})
}
