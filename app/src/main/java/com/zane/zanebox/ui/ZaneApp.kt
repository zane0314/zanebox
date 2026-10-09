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
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
internal fun homeRuntimeSnapshots(source:Flow<RuntimeSnapshot>)=source.distinctUntilChangedBy {listOf(it.state,it.generation,it.error,it.pendingManual)}

@Composable fun ZaneApp(vm:AppViewModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val runtime by remember(vm){homeRuntimeSnapshots(vm.service.snapshot)}.collectAsStateWithLifecycle(initialValue=vm.service.snapshot.value)
    val testing by vm.service.testingNodes.collectAsStateWithLifecycle()
    val autoNodeId by vm.service.autoNodeId.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val pending by vm.pendingImport.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
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
    var selectingNodes by remember { mutableStateOf(false) }
    var selectedNodes by remember { mutableStateOf(emptySet<Long>()) }
    val snackbar=remember { SnackbarHostState() }
    val subpageLists=remember{mutableMapOf<String,androidx.compose.foundation.lazy.LazyListState>()}
    val context=LocalContext.current
    LaunchedEffect(vm) {
        vm.message.filter { it.isNotBlank() }.collectLatest { message ->
            vm.message.compareAndSet(message,"")
            if(message.contains("应用修改"))dirty=true
            val accessibility=context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            val timeout=if(android.os.Build.VERSION.SDK_INT>=29)accessibility.getRecommendedTimeoutMillis(1000,android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT).toLong() else 1000L
            kotlinx.coroutines.withTimeoutOrNull(timeout) { snackbar.showSnackbar(message,duration=SnackbarDuration.Indefinite) }
        }
    }
    LaunchedEffect(data.setting("hideFromRecentApps")) {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).appTasks.forEach{runCatching{it.setExcludeFromRecents(data.bool("hideFromRecentApps"))}}
    }
    LaunchedEffect(data.setting("launcherIcon","prism")) { runCatching{applyLauncherIcon(context,data.setting("launcherIcon","prism"))}.onFailure{vm.message.value=it.message ?: "图标切换失败"} }
    LaunchedEffect(runtime.generation,runtime.state,runtime.pendingManual) { if(runtime.state==2)dirty=runtime.pendingManual }
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
            val permission=if(data.setting("serviceMode")=="vpn")android.net.VpnService.prepare(context) else null
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
        "添加订阅" to {groupForm()},"订阅管理" to {subpage="groups"},"跳转分组…" to {selector=true},"导出节点" to {exportNodes(null)})+if(data.bool("showBottomBar"))emptyList() else listOf("设置" to {page=2},"智能分流" to {page=1},"连接 / 断开" to {toggle()})
    val orderedGroups=remember(data.groups){data.groups.sortedBy{it.order}}
    val homeGroupIds=remember(orderedGroups){listOf(0L)+orderedGroups.map{it.id}}
    val pagerGroups=rememberUpdatedState(homeGroupIds)
    val pager=rememberPagerState(initialPage=homeGroupIds.indexOf(data.browseGroupId).coerceAtLeast(0)){pagerGroups.value.size}
    val pageScope=rememberCoroutineScope()
    val latestBrowse by rememberUpdatedState(data.browseGroupId)
    LaunchedEffect(data.browseGroupId,homeGroupIds) {
        if(!pager.isScrollInProgress && homeGroupIds.getOrNull(pager.settledPage)!=data.browseGroupId)
            pager.scrollToPage(homeGroupIds.indexOf(data.browseGroupId).coerceAtLeast(0))
    }
    LaunchedEffect(pager,homeGroupIds) {
        snapshotFlow{pager.settledPage}.drop(1).collect { index ->
            homeGroupIds.getOrNull(index)?.takeIf{it!=latestBrowse}?.let{vm.setting("browseGroupId",it.toString())}
        }
    }
    val sortSettings=data.settings.filterKeys{it.startsWith("sort_group_") || it.startsWith("sort_mode_group_") || it.startsWith("legacy.preference.anybox_nodes.sort_group_")}
    val groupNodes=remember(data.nodes,data.groups,search,sortSettings) {
        val filtered=data.nodes.filter{search.isBlank() || it.name.contains(search,true)}.groupBy{it.groupId}
        val grouped=orderedGroups.associate{it.id to filtered[it.id].orEmpty().sortedWith(nodeComparator(nodeSortMode(data,it)))}
        grouped+(0L to orderedGroups.filter{it.enabled}.flatMap{grouped[it.id].orEmpty()})
    }
    LaunchedEffect(data.browseGroupId,search) { selectingNodes=false;selectedNodes=emptySet() }
    LaunchedEffect(data.nodes) { selectedNodes=selectedNodes.intersect(data.nodes.map{it.id}.toSet()) }
    fun deleteNodes(ids:Set<Long>) {
        if(ids.isEmpty())return
        val chains=com.zane.zanebox.subscription.nodeRemovalIds(data.nodes,ids).size-ids.size
        val action={vm.deleteNodes(ids);selectedNodes=emptySet();selectingNodes=false}
        if(ids.size==1 && chains==0 && !data.bool("confirmProfileDelete"))action()
        else confirm=("删除 ${ids.size} 个节点？"+if(chains>0)"同时删除依赖它们的 $chains 个代理链。" else "") to action
    }
    BackHandler(selectingNodes) { selectingNodes=false;selectedNodes=emptySet() }
    BackHandler(page!=0 && subpage.isBlank()) {page=0}
    ZaneTheme(data) {
        CompositionLocalProvider(LocalUiSnackbar provides snackbar,LocalUiBusy provides busy,LocalUiReload provides (if(dirty && runtime.state==2)({vm.service.reload()})else null)) {
        Box(Modifier.fillMaxSize().semantics{testTagsAsResourceId=true}) {
            UiBackdrop(Modifier.fillMaxSize(),home=page==0)
            NativeHomeAppearance(page==0) {
            val floatingHomeBar=page==0 && data.bool("showBottomBar")
            var homeBarHeight by remember {mutableStateOf(0.dp)}
            val density=androidx.compose.ui.platform.LocalDensity.current
            val homeBarOverlap=(homeBarHeight-WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()).coerceAtLeast(0.dp)
            Scaffold(containerColor=androidx.compose.ui.graphics.Color.Transparent,contentColor=MaterialTheme.colorScheme.onSurface,
                topBar={if(page!=0)TopAppBar(expandedHeight=56.dp,title={Text(uiText(if(page==1)"智能分流" else "设置"),fontSize=20.sp,modifier=Modifier.padding(start=16.dp))},
                    navigationIcon={IconButton(onClick={page=0},modifier=Modifier.testTag("main_back")){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回")}},
                    actions={if(page==1)SmartMenu(data,vm){subpage=it}},colors=TopAppBarDefaults.topAppBarColors(containerColor=androidx.compose.ui.graphics.Color.Transparent))},
                snackbarHost={if(subpage.isBlank() && !nodeEdit && !groupEdit && !ruleEdit && !mergeEdit && editor==null && info==null && subscriptionOptions==null && speedNode==null)SnackbarHost(snackbar,Modifier.padding(bottom=if(floatingHomeBar)homeBarOverlap else 0.dp))},
                bottomBar={if(!floatingHomeBar && dirty && runtime.state==2)ApplyChangesRow({vm.service.reload()},Modifier.navigationBarsPadding())}) {padding->
                @Composable fun HomeHeader(homeGroupId:Long) {
                    val chipState=rememberLazyListState()
                    LaunchedEffect(homeGroupId,homeGroupIds) {
                        if(data.groups.isNotEmpty())chipState.animateScrollToItem(homeGroupIds.indexOf(homeGroupId).coerceAtLeast(0))
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=2.dp)) {
                        Row(Modifier.fillMaxWidth().testTag("home_header").padding(top=2.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text(uiText("已选")+"："+(if(data.bool("homeAutoSelect"))uiText("自动选择") else data.nodes.firstOrNull{it.id==data.selectedNodeId}?.name ?: uiText("未选择节点")),
                                fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f).testTag("home_selected_node"))
                            FilledIconButton(onClick={searching=!searching},modifier=Modifier.size(48.dp).testTag("search_toggle"),colors=IconButtonDefaults.filledIconButtonColors(containerColor=if(MaterialTheme.colorScheme.background.luminance()>.5f)androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.surface.copy(alpha=1f))){Icon(painterResource(R.drawable.zb_ref_abc_ic_search_api_material),"搜索节点")}
                            Spacer(Modifier.width(6.dp))
                            Box {var add by remember{mutableStateOf(false)}
                                FilledIconButton(onClick={add=true},modifier=Modifier.size(48.dp).testTag("add_nodes"),colors=IconButtonDefaults.filledIconButtonColors(containerColor=if(MaterialTheme.colorScheme.background.luminance()>.5f)androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.surface.copy(alpha=1f))){Icon(painterResource(R.drawable.zb_ref_ic_action_note_add),"添加节点")}
                                DropdownMenu(add,{add=false}){addActions.forEach{(title,action)->DropdownMenuItem(text={Text(uiText(title))},onClick={add=false;action()})}}
                            }
                            Spacer(Modifier.width(6.dp));UiMenu(moreActions,"node_menu",circle=true)
                        }
                        if(searching) {
                            OutlinedTextField(search,{search=it},label={Text(uiText("搜索节点"))},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("node_search"),trailingIcon={IconButton(onClick={search="";searching=false}){Icon(Icons.Outlined.Close,"关闭搜索")}})
                            Spacer(Modifier.height(10.dp))
                        }
                        if(data.groups.isNotEmpty()) {
                            LazyRow(modifier=Modifier.testTag("home_groups"),state=chipState,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                item{HomeGroupChip(uiText("全部"),homeGroupId==0L,{pageScope.launch{pager.animateScrollToPage(0)}},modifier=Modifier.testTag("group_all"))}
                                items(orderedGroups,key={it.id}){g->HomeGroupChip(g.name,homeGroupId==g.id,{pageScope.launch{pager.animateScrollToPage(homeGroupIds.indexOf(g.id))}},onLongClick={menuGroupId=g.id},active=g.enabled,modifier=Modifier.testTag("group_${g.id}"))}
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))
                        Spacer(Modifier.height(8.dp))
                    }
                }
                @Composable fun PageContent(homeGroupId:Long) {
                val listState=rememberLazyListState()
                val visibleNodes=groupNodes[homeGroupId].orEmpty()
                val nodesById=remember(visibleNodes){visibleNodes.associateBy{it.id}}
                val drag=rememberDragSort(visibleNodes.map{it.id},listState,canMove={from,to->nodesById[from]?.groupId==nodesById[to]?.groupId}){vm.reorderNodes(it.filterIsInstance<Long>())}
                val nodes=drag.order.mapNotNull{nodesById[it]}
                LazyColumn(Modifier.fillMaxSize().padding(if(page==0)PaddingValues(0.dp)else padding).consumeWindowInsets(if(page==0)PaddingValues(0.dp)else padding).testTag(if(page!=0 || homeGroupId==pagerGroups.value.getOrNull(pager.currentPage))"page_list" else "home_page_$homeGroupId"),state=listState,
                    contentPadding=PaddingValues(start=16.dp,end=16.dp,top=if(page==0)2.dp else 0.dp,bottom=(if(floatingHomeBar)homeBarOverlap else 0.dp)+(if(page==0)8.dp else 16.dp)),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    when(page) {
                        0->{
                            if(homeGroupId==0L && search.isBlank())item {
                                UiCard { UiRow("自动选择",data.nodes.firstOrNull{it.id==autoNodeId}?.let { "当前：${it.name} · 每 10 分钟测速" } ?: "选择延迟最低的可用节点 · 每 10 分钟测速",Icons.Outlined.AutoMode,onClick=if(activeNodes.any{JSONObject(it.outbound).optString("type")!="custom"})({vm.service.selectAuto()})else null,modifier=Modifier.testTag("home_auto"),chevron=false,trailing={RadioButton(data.bool("homeAutoSelect"),onClick={vm.service.selectAuto()},enabled=activeNodes.any{JSONObject(it.outbound).optString("type")!="custom"})}) }
                            }
                            if(selectingNodes && nodes.isNotEmpty())item {
                                NodeSelectionBar(selectingNodes,selectedNodes.size,"home",{selectingNodes=!selectingNodes;selectedNodes=emptySet()},{selectedNodes=nodes.map{it.id}.toSet()},{deleteNodes(selectedNodes)})
                            }
                            items(nodes,key={it.id},contentType={"node"}){n->
                                val enabled=n.groupId in enabledIds
                                val showAddress=data.bool("alwaysShowAddress")
                                val subtitle=remember(n.outbound,showAddress){runCatching{val node=JSONObject(n.outbound);node.optString("type").uppercase()+(if(showAddress)" · ${node.optString("server")}:${node.optInt("server_port")}" else "")}.getOrDefault("")}
                                UiCard(Modifier.animateItem(placementSpec=if(drag.dragging==n.id)null else spring()).then(if(selectingNodes)Modifier else drag.modifier(n.id))) { Row(Modifier.fillMaxWidth().heightIn(min=68.dp).padding(start=12.dp),verticalAlignment=Alignment.CenterVertically) {
                                    if(selectingNodes)Checkbox(n.id in selectedNodes,{checked->selectedNodes=if(checked)selectedNodes+n.id else selectedNodes-n.id},modifier=Modifier.testTag("home_node_check_${n.id}"))
                                    else RadioButton(!data.bool("homeAutoSelect") && data.selectedNodeId==n.id,onClick={if(enabled)vm.service.selectNode(n.id)},enabled=enabled)
                                    Column(Modifier.weight(1f).clickable(enabled=selectingNodes || enabled,onClick={if(selectingNodes)selectedNodes=if(n.id in selectedNodes)selectedNodes-n.id else selectedNodes+n.id else vm.service.selectNode(n.id)}).testTag("node_${n.id}").padding(vertical=12.dp)) {
                                        Text(n.name,fontSize=14.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                                        Text(subtitle,fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(uiText(if(n.id in testing)"测试中" else nodeTestLabel(n)),fontSize=11.sp,color=MaterialTheme.colorScheme.primary,modifier=Modifier.testTag("node_latency_${n.id}").clickable(enabled=enabled){vm.service.testNodes(listOf(n.id))})
                                    IconButton(onClick={info=n},modifier=Modifier.testTag("node_info_${n.id}")){Icon(painterResource(R.drawable.zb_ref_ic_baseline_info_24),"节点详情")}
                                    UiMenu(listOf("编辑" to {nodeForm(n)},"测试延迟" to {vm.service.testNodes(listOf(n.id))},"速度测试" to {speedNode=n.id},"分享" to {share(shareText(listOf(n)))},"二维码" to {qr=shareText(listOf(n))},"复制" to {copy(shareText(listOf(n)))},"节点区域" to {form("节点区域",listOf("hk/us/kr/jp/sg/tw（留空自动）" to data.setting("nodeRegion.${n.id}"))){val key="nodeRegion.${n.id}";val region=it[0].trim().lowercase(java.util.Locale.ROOT);validatePreference(key,region);vm.setting(key,region)}},"多选" to {selectingNodes=true;selectedNodes=selectedNodes+n.id},"删除" to {deleteNodes(setOf(n.id))}).filter{enabled || it.first !in listOf("测试延迟","速度测试")},"node_menu_${n.id}")
                                } }
                            }
                            if(nodes.isEmpty())item {Box(Modifier.fillMaxWidth().height(220.dp),contentAlignment=Alignment.Center){Text(if(data.nodes.isEmpty())"暂无节点，点右上角添加节点或订阅" else "未找到匹配节点",fontSize=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
                            if(runtime.error.isNotBlank())item{Text(runtime.error,color=MaterialTheme.colorScheme.error)}
                        }
                        2->{item{SettingsHub(onOpen={subpage=it},clashApi=data.bool("clashApi"))}}
                    }
                }
                }
                if(page==0)Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                    HomeHeader(pagerGroups.value.getOrNull(pager.currentPage) ?: data.browseGroupId)
                    HorizontalPager(pager,beyondViewportPageCount=2,key={pagerGroups.value.getOrNull(it) ?: -(it+1L)},userScrollEnabled=!selectingNodes,modifier=Modifier.weight(1f).fillMaxWidth().testTag("home_pager")){index->Box(Modifier.fillMaxSize().then(if(index==pager.currentPage)Modifier else Modifier.clearAndSetSemantics{})){pagerGroups.value.getOrNull(index)?.let{PageContent(it)}}}
                } else if(page==1)SmartPanel(data,vm,{title,fields,save->form(title,fields,save)},onRules={subpage="rules"},onMerges={subpage="merges"},onOpen={subpage=it},modifier=Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding))
                else PageContent(data.browseGroupId)
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
                    listOf("分享分组" to {share(shareText(data.nodes.filter{it.groupId==group.id}))},"删除" to {if(data.bool("confirmProfileDelete"))confirm="删除分组及其全部节点、依赖代理链？" to {vm.deleteGroup(group.id)}else vm.deleteGroup(group.id)})
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
            if(groupEdit)GroupEditor(editGroup,data,{groupEdit=false},saveError=editorSaveError,saving=editorSaving){value->if(editorSaving)return@GroupEditor;editorSaving=true;val g=value.copy(id=editGroup?.id ?: 0,order=editGroup?.order ?: data.groups.size);vm.saveGroup(g,g.subscriptionUrl.isNotBlank() && (editGroup==null || editGroup?.subscriptionUrl!=g.subscriptionUrl),onSaved={editorSaving=false;groupEdit=false},onError={editorSaving=false;editorSaveError=it})}
            if(ruleEdit)RuleEditor(editRule,data,{ruleEdit=false},saveError=editorSaveError,saving=editorSaving){value->if(editorSaving)return@RuleEditor;editorSaving=true;vm.edit(onSaved={editorSaving=false;ruleEdit=false},onError={editorSaving=false;editorSaveError=it}){d->val r=value.copy(id=editRule?.id ?: vm.store.nextId(),order=editRule?.order ?: d.rules.size);d.copy(rules=d.rules.filter{it.id!=r.id}+r)}}
            if(mergeEdit)MergeEditor(editMerge,data,{mergeEdit=false},saveError=editorSaveError,saving=editorSaving){value->if(editorSaving)return@MergeEditor;editorSaving=true;vm.edit(onSaved={editorSaving=false;mergeEdit=false},onError={editorSaving=false;editorSaveError=it}){d->val m=value.copy(id=editMerge?.id ?: vm.store.nextId());d.copy(merges=d.merges.filter{it.id!=m.id}+m)}}
            editor?.let{TextEditPage(it,{editor=null})}
            info?.let{NodeInfo(it,data,vm){info=null}}
            subscriptionOptions?.let{SubscriptionOptionsDialog(it,vm){subscriptionOptions=null}}
            qr?.let{QrDialog(it,vm){qr=null}}
            speedNode?.let{SpeedDialog(vm,it){speedNode=null}}
            confirm?.let{(text,action)->UiAlertDialog(onDismissRequest={confirm=null},title={Text(uiText("确认操作"))},text={Text(text)},confirmButton={TextButton(onClick={action();confirm=null}){Text(uiText("确认"))}},dismissButton={TextButton(onClick={confirm=null}){Text(uiText("取消"))}})}
            pending?.let{ImportDialog(it,data,busy || importing,{name,groupId->vm.confirmImport(name,groupId)},vm::cancelImport)}
        }
        }
    }
}
