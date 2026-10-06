@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.zane.zanebox.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import com.zane.zanebox.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zane.zanebox.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import com.zane.zanebox.runtime.RuntimeSnapshot
import org.json.JSONObject

internal data class TextEditor(val title:String,val fields:List<Pair<String,String>>,val save:(List<String>)->Unit)
internal fun homeRuntimeSnapshots(source:Flow<RuntimeSnapshot>)=source.distinctUntilChangedBy {Triple(it.state,it.generation,it.error)}

@Composable fun ZaneApp(vm:AppViewModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val runtime by remember(vm){homeRuntimeSnapshots(vm.service.snapshot)}.collectAsStateWithLifecycle(initialValue=vm.service.snapshot.value)
    val testing by vm.service.testingNodes.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val pending by vm.pendingImport.collectAsStateWithLifecycle()
    val ip by vm.ip.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var subpage by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var editor by remember { mutableStateOf<TextEditor?>(null) }
    var confirm by remember { mutableStateOf<Pair<String,()->Unit>?>(null) }
    var editorSaveError by remember{mutableStateOf("")}
    var editorSaving by remember{mutableStateOf(false)}
    var backupScope by remember{mutableStateOf(com.zane.zanebox.backup.BackupScope())}
    var info by remember{mutableStateOf<Node?>(null)}
    var initialProtocol by remember { mutableStateOf("vless") }
    var groupProxy by remember { mutableStateOf("") }
    var proxyGroupId by remember { mutableStateOf<Long?>(null) }
    var menuGroupId by remember { mutableStateOf<Long?>(null) }
    var nodeEdit by remember { mutableStateOf(false) };var editNode by remember { mutableStateOf<Node?>(null) }
    var groupEdit by remember { mutableStateOf(false) };var editGroup by remember { mutableStateOf<Group?>(null) }
    var ruleEdit by remember { mutableStateOf(false) };var editRule by remember { mutableStateOf<RouteRule?>(null) }
    var mergeEdit by remember { mutableStateOf(false) };var editMerge by remember { mutableStateOf<MergeGroup?>(null) }
    var subscriptionOptions by remember { mutableStateOf<Group?>(null) }
    var qr by remember { mutableStateOf<String?>(null) }
    var exportGroup by remember { mutableStateOf<Long?>(null) }
    var speedNode by remember { mutableStateOf<Long?>(null) }
    var selector by remember { mutableStateOf(false) }
    var dirty by rememberSaveable { mutableStateOf(false) }
    val snackbar=remember { SnackbarHostState() }
    val subpageLists=remember{mutableMapOf<String,androidx.compose.foundation.lazy.LazyListState>()}
    val context=LocalContext.current
    LaunchedEffect(vm) {
        vm.message.filter { it.isNotBlank() }.collectLatest { message ->
            vm.message.compareAndSet(message,"")
            if(message.contains("应用修改"))dirty=true
            snackbar.showSnackbar(message)
        }
    }
    LaunchedEffect(data.setting("hideFromRecentApps")) {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).appTasks.forEach{runCatching{it.setExcludeFromRecents(data.bool("hideFromRecentApps"))}}
    }
    LaunchedEffect(data.setting("launcherIcon","prism")) { runCatching{applyLauncherIcon(context,data.setting("launcherIcon","prism"))}.onFailure{vm.message.value=it.message ?: "图标切换失败"} }
    LaunchedEffect(runtime.generation,runtime.state) { if(runtime.state==2)dirty=false }
    fun form(title:String,fields:List<Pair<String,String>>,save:(List<String>)->Unit) {editor=TextEditor(title,fields,save)}
    fun share(text:String) {if(text.isBlank()){vm.message.value="没有可分享的内容";return};runCatching{context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),"分享节点"))}.onFailure { vm.message.value=it.message ?: "分享失败" }}
    fun copy(text:String) {if(text.isBlank()){vm.message.value="没有可复制的内容";return};(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("zanebox",text));vm.message.value="已复制"}
    fun showQr(text:String) {if(text.isBlank())vm.message.value="没有可生成二维码的内容" else qr=text}
    fun nodeForm(n:Node?=null,protocol:String="vless") {initialProtocol=protocol;editNode=n;editorSaveError="";nodeEdit=true}
    fun groupForm(g:Group?=null) {editGroup=g;editorSaveError="";groupEdit=true}
    fun ruleForm(r:RouteRule?=null) {editRule=r;editorSaveError="";ruleEdit=true}
    fun mergeForm(m:MergeGroup?=null) {editMerge=m;editorSaveError="";mergeEdit=true}
    val vpn=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if(it.resultCode==android.app.Activity.RESULT_OK)vm.service.start() else vm.message.value="VPN 授权未批准"
    }
    fun toggle() {
        if(runtime.state==2 || runtime.state==1)vm.service.stop()
        else if(runtime.state!=3) {
            if(data.nodes.none{n->data.groups.any{it.id==n.groupId && it.enabled}}) {vm.message.value="请先在列表中选择节点";return}
            val permission=if(data.setting("serviceMode","vpn")=="vpn")android.net.VpnService.prepare(context) else null
            if(permission==null)vm.service.start() else vpn.launch(permission)
        }
    }
    val scan=rememberLauncherForActivityResult(com.journeyapps.barcodescanner.ScanContract()){it.contents?.let {text->vm.importText(text,data.browseGroupId)}}
    val importFile=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){it?.let(vm::readText)}
    val exportFile=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){it?.let { uri->vm.export(uri,exportGroup) }}
    fun exportNodes(groupId:Long?) {if(data.nodes.none { groupId==null || it.groupId==groupId }){vm.message.value="没有可导出的节点";return};exportGroup=groupId;exportFile.launch("zanebox-nodes.txt")}
    val backupFile=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){it?.let{uri->vm.backup(uri,backupScope)}}
    val restoreFile=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){it?.let {uri->confirm="恢复备份将替换当前全部配置，是否继续？" to {vm.restore(uri)}}}
    val addActions=listOf("扫码导入" to {scan.launch(com.journeyapps.barcodescanner.ScanOptions().setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE).setPrompt("扫描节点二维码").setBeepEnabled(false))},
        "从剪贴板导入" to {val cb=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;vm.importText(cb.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty(),data.browseGroupId)},
        "从文件导入" to {importFile.launch(arrayOf("*/*"))},"手动添加" to {nodeForm()},"创建链式代理" to {nodeForm(protocol="chain")},
        "导入文本" to {form("导入文本",listOf("链接 / Base64 / Clash / sing-box" to "")){vm.importText(it[0],data.browseGroupId)}})
    val currentGroup=data.groups.firstOrNull{it.id==data.browseGroupId}
    val enabledIds=remember(data.groups){data.groups.filter{it.enabled}.map{it.id}.toSet()}
    val activeNodes=remember(data.nodes,enabledIds){data.nodes.filter{it.groupId in enabledIds}}
    fun proxyActions(group:Group):List<Pair<String,()->Unit>> =listOf("前置代理" to {proxyGroupId=group.id;groupProxy="front"},"后置代理（落地）" to {proxyGroupId=group.id;groupProxy="landing"})
    val groupActions=currentGroup?.let(::proxyActions).orEmpty()
    val moreActions:List<Pair<String,()->Unit>> =groupActions+listOf("更新当前订阅" to {if(currentGroup==null)vm.message.value="请先选择订阅分组" else vm.updateGroup(currentGroup)},
        "测试当前分组延迟" to {vm.service.testNodes(activeNodes.filter{currentGroup==null || it.groupId==currentGroup.id}.map{it.id})},
        "全部更新（全部订阅）" to vm::updateAllGroups,
        "全部测速（全部分组）" to {vm.service.testNodes(data.nodes.filter{n->data.groups.any{it.id==n.groupId && it.enabled}}.map{it.id})},"取消测速" to {vm.service.cancelTests()},
        "默认顺序" to {data.groups.filter{currentGroup==null || it.id==currentGroup.id}.forEach{vm.setting("sort_group_${it.id}","false")}},
        "延迟升序" to {data.groups.filter{currentGroup==null || it.id==currentGroup.id}.forEach{vm.setting("sort_group_${it.id}","true")}},
        "添加订阅" to {groupForm()},"订阅管理" to {subpage="groups"},"跳转分组…" to {selector=true},"导出节点" to {exportNodes(null)})+if(data.bool("showBottomBar",true))emptyList() else listOf("设置" to {page=2},"智能分流" to {page=1},"连接 / 断开" to {toggle()})
    val displayNodes=remember(data.nodes,data.groups,data.browseGroupId,search,data.settings){data.nodes.filter{((data.browseGroupId==0L && it.groupId in enabledIds) || it.groupId==data.browseGroupId) && (search.isBlank() || it.name.contains(search,true))}
                                .sortedWith(Comparator{a,b->val g=data.groups.firstOrNull{it.id==a.groupId};if(a.groupId!=b.groupId && currentGroup==null)(g?.order ?:0).compareTo(data.groups.firstOrNull{it.id==b.groupId}?.order ?:0) else nodeComparator(g?.let{nodeSortMode(data,it)} ?: NodeSortMode.DEFAULT).compare(a,b)})}
    BackHandler(page!=0 && subpage.isBlank()) {page=0}
    ZaneTheme(data) {
        CompositionLocalProvider(LocalUiSnackbar provides snackbar,LocalUiBusy provides busy,LocalUiReload provides (if(dirty && runtime.state==2)({vm.service.reload()})else null)) {
        Box(Modifier.fillMaxSize().semantics{testTagsAsResourceId=true}) {
            UiBackdrop(Modifier.fillMaxSize(),home=page==0)
            NativeHomeAppearance(page==0) {
            val floatingHomeBar=page==0 && data.bool("showBottomBar",true)
            var homeBarHeight by remember {mutableStateOf(0.dp)}
            val density=androidx.compose.ui.platform.LocalDensity.current
            val homeBarOverlap=(homeBarHeight-WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()).coerceAtLeast(0.dp)
            Scaffold(containerColor=androidx.compose.ui.graphics.Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface,
                topBar={if(page!=0)TopAppBar(expandedHeight=56.dp,title={Text(uiText(if(page==1)"智能分流" else "设置"),fontSize=20.sp,modifier=Modifier.padding(start=16.dp))},
                    navigationIcon={IconButton(onClick={page=0},modifier=Modifier.testTag("main_back")){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回")}},
                    actions={if(page==1)SmartMenu(data,vm){subpage=it}},colors=TopAppBarDefaults.topAppBarColors(containerColor=androidx.compose.ui.graphics.Color.Transparent))},
                snackbarHost={if(subpage.isBlank() && !nodeEdit && !groupEdit && !ruleEdit && !mergeEdit && editor==null && info==null && subscriptionOptions==null && speedNode==null)SnackbarHost(snackbar,Modifier.padding(bottom=if(floatingHomeBar)homeBarOverlap else 0.dp))},
                bottomBar={if(!floatingHomeBar && dirty && runtime.state==2)ApplyChangesRow({vm.service.reload()},Modifier.navigationBarsPadding())}) {padding->
                LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("page_list"),
                    contentPadding=PaddingValues(start=16.dp,end=16.dp,top=if(page==0)2.dp else 0.dp,bottom=(if(floatingHomeBar)homeBarOverlap else 0.dp)+(if(page==0)8.dp else 16.dp)),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    when(page) {
                        0->{
                            item { Row(Modifier.fillMaxWidth().padding(top=2.dp,bottom=12.dp),verticalAlignment=Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    Text(uiText("节点"),fontSize=26.sp,fontWeight=FontWeight.Bold)
                                    val dotColor=if(runtime.state==2)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    val stateText=uiText(listOf("代理未连接","连接中","代理已连接","断开中","连接失败").getOrElse(runtime.state){"同步中"})
                                    Text(androidx.compose.ui.text.buildAnnotatedString { pushStyle(androidx.compose.ui.text.SpanStyle(color=dotColor));append("● ");pop();append(stateText) },fontSize=14.sp,modifier=Modifier.testTag("connection_state"))
                                    Text(uiText("已选")+"："+(data.nodes.firstOrNull{it.id==data.selectedNodeId}?.name ?: uiText("未选择节点")),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                                }
                                FilledIconButton(onClick={searching=!searching},modifier=Modifier.size(48.dp).testTag("search_toggle"),colors=IconButtonDefaults.filledIconButtonColors(containerColor=if(MaterialTheme.colorScheme.background.luminance()>.5f)androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.surface.copy(alpha=1f))){Icon(painterResource(R.drawable.zb_ref_abc_ic_search_api_material),"搜索节点")}
                                Spacer(Modifier.width(6.dp))
                                Box {var add by remember{mutableStateOf(false)}
                                    FilledIconButton(onClick={add=true},modifier=Modifier.size(48.dp).testTag("add_nodes"),colors=IconButtonDefaults.filledIconButtonColors(containerColor=if(MaterialTheme.colorScheme.background.luminance()>.5f)androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.surface.copy(alpha=1f))){Icon(painterResource(R.drawable.zb_ref_ic_action_note_add),"添加节点")}
                                    DropdownMenu(add,{add=false}){addActions.forEach{(title,action)->DropdownMenuItem(text={Text(uiText(title))},onClick={add=false;action()})}}
                                }
                                Spacer(Modifier.width(6.dp));UiMenu(moreActions,"node_menu",circle=true)
                            } }
                            if(searching)item{OutlinedTextField(search,{search=it},label={Text(uiText("搜索节点"))},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("node_search"),trailingIcon={IconButton(onClick={search="";searching=false}){Icon(Icons.Outlined.Close,"关闭搜索")}})}
                            if(data.groups.isNotEmpty()) item { LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                item{HomeGroupChip(uiText("全部"),data.browseGroupId==0L,{vm.setting("browseGroupId","0")},modifier=Modifier.testTag("group_all"))}
                                items(data.groups.sortedBy{it.order},key={it.id}){g->HomeGroupChip(g.name,data.browseGroupId==g.id,{vm.setting("browseGroupId",g.id.toString())},onLongClick={menuGroupId=g.id},active=g.enabled,modifier=Modifier.testTag("group_${g.id}"))}
                            } }
                            item {HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))}
                            val nodes=displayNodes
                            items(nodes,key={it.id},contentType={"node"}){n->
                                val enabled=n.groupId in enabledIds
                                val showAddress=data.bool("alwaysShowAddress")
                                val subtitle=remember(n.outbound,showAddress){runCatching{val node=JSONObject(n.outbound);node.optString("type").uppercase()+(if(showAddress)" · ${node.optString("server")}:${node.optInt("server_port")}" else "")}.getOrDefault("")}
                                UiCard { Row(Modifier.fillMaxWidth().heightIn(min=68.dp).padding(start=12.dp),verticalAlignment=Alignment.CenterVertically) {
                                    RadioButton(data.selectedNodeId==n.id,onClick={if(enabled)vm.service.selectNode(n.id)},enabled=enabled)
                                    Column(Modifier.weight(1f).clickable(enabled=enabled){vm.service.selectNode(n.id)}.testTag("node_${n.id}").padding(vertical=12.dp)) {
                                        Text(n.name,fontSize=14.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                                        Text(subtitle,fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(uiText(if(n.id in testing)"测试中" else nodeTestLabel(n)),fontSize=11.sp,color=MaterialTheme.colorScheme.primary,modifier=Modifier.testTag("node_latency_${n.id}").clickable(enabled=enabled){vm.service.testNodes(listOf(n.id))})
                                    IconButton(onClick={info=n},modifier=Modifier.testTag("node_info_${n.id}")){Icon(painterResource(R.drawable.zb_ref_ic_baseline_info_24),"节点详情")}
                                    UiMenu(listOf("编辑" to {nodeForm(n)},"测试延迟" to {vm.service.testNodes(listOf(n.id))},"速度测试" to {speedNode=n.id},"分享" to {share(shareText(listOf(n)))},"二维码" to {qr=shareText(listOf(n))},"复制" to {copy(shareText(listOf(n)))},"节点区域" to {form("节点区域",listOf("hk/us/kr/jp/sg/tw（留空自动）" to data.setting("nodeRegion.${n.id}"))){val key="nodeRegion.${n.id}";val region=it[0].trim().lowercase(java.util.Locale.ROOT);validatePreference(key,region);vm.setting(key,region)}},"上移" to {moveNode(vm,n)},"删除" to {if(data.bool("confirmProfileDelete",true))confirm="删除节点 ${n.name}？" to {vm.deleteNode(n.id)}else vm.deleteNode(n.id)}).filter{enabled || it.first !in listOf("测试延迟","速度测试")},"node_menu_${n.id}")
                                } }
                            }
                            if(nodes.isEmpty())item {Box(Modifier.fillMaxWidth().height(220.dp),contentAlignment=Alignment.Center){Text(if(data.nodes.isEmpty())"暂无节点，点右上角添加节点或订阅" else "未找到匹配节点",fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
                            if(runtime.error.isNotBlank())item{Text(runtime.error,color=MaterialTheme.colorScheme.error)}
                        }
                        1->{item{SmartPanel(data,vm,{title,fields,save->form(title,fields,save)},onRules={subpage="rules"},onMerges={subpage="merges"},onOpen={subpage=it})}}
                        2->{item{SettingsHub(onOpen={subpage=it},clashApi=data.bool("clashApi"))}}
                    }
                }
            }
            if(floatingHomeBar)Column(Modifier.align(Alignment.BottomCenter).onSizeChanged{homeBarHeight=with(density){it.height.toDp()}}) {
                if(dirty && runtime.state==2)ApplyChangesRow({vm.service.reload()})
                HomeToolbar(runtime.state,{page=it},::toggle)
            }
            }
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter).testTag("busy"))
            if(subpage.isNotBlank())CompositionLocalProvider(LocalUiListState provides subpageLists.getOrPut(subpage){androidx.compose.foundation.lazy.LazyListState()}) {SettingsDestination(subpage.substringAfterLast('>'),data,vm,{subpage=subpage.substringBeforeLast('>',"")},{subpage="$subpage>$it"},::form,
                backup={scope->backupScope=scope;backupFile.launch("zanebox-backup.zip")},restore={restoreFile.launch(arrayOf("application/zip","application/octet-stream"))},
                groupEdit=::groupForm,ruleEdit=::ruleForm,mergeEdit=::mergeForm,subscriptionEdit={subscriptionOptions=it},share=::share,copy=::copy,qr=::showQr,exportNodes=::exportNodes,toggle=::toggle)}
            if(selector)ChoiceDialog("跳转分组",data.browseGroupId.toString(),listOf("0" to "全部分组")+data.groups.map{it.id.toString() to it.name},{selector=false}){vm.setting("browseGroupId",it)}
            data.groups.firstOrNull{it.id==menuGroupId}?.let {group->
                val actions=listOf((if(group.enabled)"停用分组" else "启用分组") to {vm.edit{d->d.copy(groups=d.groups.map{if(it.id==group.id)it.copy(enabled=!it.enabled)else it})}})+
                    (if(group.subscriptionUrl.isNotBlank())listOf("更新订阅" to {vm.updateGroup(group)})else emptyList())+
                    listOf("编辑" to {groupForm(group)},"订阅选项" to {subscriptionOptions=group})+proxyActions(group)+
                    listOf("分享分组" to {share(shareText(data.nodes.filter{it.groupId==group.id}))},"删除" to {if(data.bool("confirmProfileDelete",true))confirm="删除分组及其全部节点？" to {vm.deleteGroup(group.id)}else vm.deleteGroup(group.id)})
                UiAlertDialog(onDismissRequest={menuGroupId=null},title={Text(group.name)},text={LazyColumn(Modifier.heightIn(max=440.dp).testTag("home_group_menu")) {
                    items(actions,key={it.first}){(label,action)->TextButton(onClick={menuGroupId=null;action()},modifier=Modifier.fillMaxWidth()){Text(uiText(label))}}
                }},confirmButton={TextButton(onClick={menuGroupId=null}){Text(uiText("取消"))}})
            }
            data.groups.firstOrNull{it.id==proxyGroupId}?.let {group->if(groupProxy.isNotBlank())ChoiceDialog(if(groupProxy=="front")"前置代理" else "后置代理（落地）",
                (if(groupProxy=="front")group.frontProxy else group.landingProxy).toString(),
                listOf("0" to "无")+data.nodes.filter { JSONObject(it.outbound).optString("type") !in listOf("chain","custom") }.map{it.id.toString() to it.name},
                {groupProxy="";proxyGroupId=null}) { id->val front=groupProxy=="front";vm.edit{d->d.copy(groups=d.groups.map{if(it.id!=group.id)it else if(front)it.copy(frontProxy=id.toLong())else it.copy(landingProxy=id.toLong())})};groupProxy="";proxyGroupId=null }
            }
    if(nodeEdit)NodeEditor(editNode,data,initialProtocol=initialProtocol,{nodeEdit=false},saveError=editorSaveError,saving=editorSaving){value->if(editorSaving)return@NodeEditor;editorSaving=true;
                vm.saveNode(value,onSaved={editorSaving=false;nodeEdit=false},onError={editorSaving=false;editorSaveError=it})
            }
            if(groupEdit)GroupEditor(editGroup,data,{groupEdit=false},saveError=editorSaveError,saving=editorSaving){value->if(editorSaving)return@GroupEditor;editorSaving=true;val g=value.copy(id=editGroup?.id ?: vm.store.nextId(),order=editGroup?.order ?: data.groups.size);vm.saveGroup(g,g.subscriptionUrl.isNotBlank() && (editGroup==null || editGroup?.subscriptionUrl!=g.subscriptionUrl),onSaved={editorSaving=false;groupEdit=false},onError={editorSaving=false;editorSaveError=it})}
            if(ruleEdit)RuleEditor(editRule,data,{ruleEdit=false},saveError=editorSaveError,saving=editorSaving){value->if(editorSaving)return@RuleEditor;editorSaving=true;vm.edit(onSaved={editorSaving=false;ruleEdit=false},onError={editorSaving=false;editorSaveError=it}){d->val r=value.copy(id=editRule?.id ?: vm.store.nextId(),order=editRule?.order ?: d.rules.size);d.copy(rules=d.rules.filter{it.id!=r.id}+r)}}
            if(mergeEdit)MergeEditor(editMerge,data,{mergeEdit=false},saveError=editorSaveError,saving=editorSaving){value->if(editorSaving)return@MergeEditor;editorSaving=true;vm.edit(onSaved={editorSaving=false;mergeEdit=false},onError={editorSaving=false;editorSaveError=it}){d->val m=value.copy(id=editMerge?.id ?: vm.store.nextId());d.copy(merges=d.merges.filter{it.id!=m.id}+m)}}
            editor?.let{TextEditPage(it,{editor=null})}
            info?.let{NodeInfo(it,data,vm){info=null}}
            subscriptionOptions?.let{SubscriptionOptionsDialog(it,vm){subscriptionOptions=null}}
            qr?.let{QrDialog(it,vm){qr=null}}
            speedNode?.let{SpeedDialog(vm,it){speedNode=null}}
            confirm?.let{(text,action)->UiAlertDialog(onDismissRequest={confirm=null},title={Text(uiText("确认操作"))},text={Text(text)},confirmButton={TextButton(onClick={action();confirm=null}){Text(uiText("确认"))}},dismissButton={TextButton(onClick={confirm=null}){Text(uiText("取消"))}})}
            pending?.let{PendingImportDialog(it,vm::confirmImport,vm::cancelImport)}
        }
        }
    }
}

private fun moveNode(vm:AppViewModel,n:Node) {vm.edit{d->val list=d.nodes.filter{it.groupId==n.groupId}.sortedBy{it.order}.toMutableList();val i=list.indexOfFirst{it.id==n.id};if(i>0)java.util.Collections.swap(list,i,i-1);val order=list.mapIndexed{j,v->v.id to j}.toMap();d.copy(nodes=d.nodes.map{if(it.id in order)it.copy(order=order.getValue(it.id))else it})}}

@Composable private fun PendingImportDialog(pending:PendingImport,onConfirm:()->Unit,onDismiss:()->Unit) {
    val (title,body,action)=when(pending) {
        is PendingImport.Subscription->Triple("添加外部订阅？","名称：${pending.name}\n地址：${pending.url.take(200)}\n\n确认后会立即下载该订阅。","添加")
        is PendingImport.Nodes->Triple("导入外部节点？","将导入 ${pending.report.nodes.size} 个节点"+(if(pending.report.skipped>0)"，跳过 ${pending.report.skipped} 条无法识别内容" else "")+"：\n"+pending.report.nodes.take(5).joinToString("\n"){"· "+it.name.take(60)},"导入")
        is PendingImport.Backup->Triple("用外部备份替换全部数据？","备份含 ${pending.data.groups.size} 个组、${pending.data.nodes.size} 个节点、${pending.data.rules.size} 条规则。\n恢复会覆盖当前节点、订阅和设置。","替换")
    }
    UiAlertDialog(onDismissRequest=onDismiss,title={Text(title)},text={Text(body)},confirmButton={TextButton(onClick=onConfirm,modifier=Modifier.testTag("import_confirm")){Text(action)}},dismissButton={TextButton(onClick=onDismiss,modifier=Modifier.testTag("import_cancel")){Text(uiText("取消"))}})
}
