package com.zane.zanebox.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zane.zanebox.config.*
import com.zane.zanebox.data.AppData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.repeatOnLifecycle

@Composable internal fun rememberInstalledPackageNames():Set<String>? {
    val context=LocalContext.current
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val installed by produceState<Set<String>?>(null,context,lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            value=withContext(Dispatchers.IO){context.packageManager.getInstalledApplications(0).map{it.packageName}.toSet()}
            kotlinx.coroutines.awaitCancellation()
        }
    }
    return installed
}

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
    Row(Modifier.fillMaxWidth().testTag("app_${app.packageName}").toggleable(selected,role=Role.Checkbox,onValueChange=onSelected).heightIn(min=72.dp).padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
        InstalledAppIcon(app.packageName)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(app.name,fontSize=14.sp,lineHeight=18.sp,fontWeight=FontWeight.SemiBold)
            Text(app.packageName,fontSize=11.sp,lineHeight=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Icon(if(selected)Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,null,Modifier.size(28.dp),tint=if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
    }
}

// List layout and common-app matching adapted from satelite-one (MIT); keep Links' explicit save/apply flow.
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable internal fun AppsEditor(initial:Set<String>,onDismiss:()->Unit,modeValue:String="exclude",onMode:((String)->Unit)?=null,
    enabledValue:Boolean=false,onEnabled:((Boolean)->Unit)?=null,scopeData:AppData?=null,title:String="选择应用",save:(Set<String>)->Unit) {
    var selected by remember{mutableStateOf(initial)}
    var query by remember{mutableStateOf("")}
    var showSystem by remember{mutableStateOf(false)}
    var mode by remember(modeValue){mutableStateOf(modeValue)}
    var enabled by remember(enabledValue){mutableStateOf(enabledValue)}
    var note by remember{mutableStateOf("")}
    val context=LocalContext.current
    val clipboard=context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? ClipboardManager
    var loading by remember{mutableStateOf(true)}
    var loadError by remember{mutableStateOf("")}
    val apps by produceState<List<InstalledApp>>(emptyList()) {
        try {
            value=withContext(Dispatchers.IO) {
                val pm=context.packageManager
                pm.getInstalledApplications(0).mapNotNull { info->
                    runCatching { InstalledApp(pm.getApplicationLabel(info).toString().ifBlank{info.packageName},info.packageName,(info.flags and ApplicationInfo.FLAG_SYSTEM)!=0,info.packageName==context.packageName || pm.getLaunchIntentForPackage(info.packageName)!=null) }.getOrNull()
                }
            }
        } catch(e:kotlinx.coroutines.CancellationException) { throw e }
        catch(_:Exception) { loadError="无法读取应用列表，请退出后重试；原选择已保留" }
        finally { loading=false }
    }
    val installed=remember(apps){apps.map{it.packageName}.toSet()}
    val eligible=remember(installed,scopeData){if(scopeData==null)installed else filterVpnPackages(scopeData,installed)}
    val effective=selected.intersect(eligible)
    val inactive=selected-effective
    val visible=remember(apps,selected,query,showSystem,scopeData){visibleApps(apps,selected,query.trim(),showSystem,scopeData)}
    val perApp=onMode!=null
    fun add(packages:Set<String>,message:String) {
        val added=packages-selected
        selected=selected+packages
        note="$message ${added.size} 个应用"
    }
    val ready=!loading && loadError.isBlank()
    UiPageList(if(perApp)"分应用代理" else title,onDismiss,action={TextButton(onClick={save(selected)},modifier=Modifier.testTag("apps_save")){Text(uiText("保存"))}}) {
        item(key="apps_header"){UiCard {
            if(perApp) {
                UiRow("启用分应用代理",if(mode=="include")"仅所选应用进入 VPN" else "所选应用绕过 VPN",chevron=false,trailing={
                    UiSwitch(enabled,{enabled=it;onEnabled?.invoke(it)},modifier=Modifier.testTag("apps_enabled"))
                })
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("include" to "白名单模式","exclude" to "黑名单模式").forEach{(value,label)->
                        FilterChip(mode==value,{mode=value;onMode?.invoke(value)},label={Text(uiText(label))},modifier=Modifier.weight(1f).testTag("apps_mode_$value"))
                    }
                }
            }
            UiRow("已选择 ${effective.size} 个应用",if(perApp)"保存名单后，连接时通过“应用修改”生效" else "保存当前应用策略；名单与分应用代理独立",chevron=false,modifier=Modifier.testTag("apps_selection_count"))
            if(scopeData?.bool("perAppEnabled")==true)Text(uiText(if(scopeData.setting("perAppMode","exclude")=="include")"仅可选择分应用白名单中的应用" else "分应用黑名单中的应用不可选择"),Modifier.padding(horizontal=16.dp,vertical=4.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(inactive.isNotEmpty())Text("${inactive.size} 个历史选择因未安装或超出范围暂不生效，已保留",Modifier.padding(horizontal=16.dp,vertical=4.dp).testTag("apps_inactive"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(note.isNotBlank())Text(note,Modifier.padding(horizontal=16.dp,vertical=8.dp).testTag("apps_note"),style=MaterialTheme.typography.bodySmall)
        }}
        item(key="apps_search_field"){OutlinedTextField(query,{query=it},placeholder={Text(uiText("搜索应用名称或包名"))},singleLine=true,shape=MaterialTheme.shapes.large,modifier=Modifier.fillMaxWidth().testTag("apps_search"))}
        item(key="apps_actions"){
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal=2.dp),verticalAlignment=Alignment.CenterVertically){Text(uiText("显示系统应用"),Modifier.weight(1f));UiSwitch(showSystem,{showSystem=it},modifier=Modifier.testTag("apps_system"))}
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick={add(commonAppPackages(apps,context.packageName,perApp && mode=="exclude").intersect(eligible),"已自动添加")},enabled=ready,modifier=Modifier.testTag("apps_auto")){Text(uiText("自动选择"))}
                    TextButton(onClick={add(visible.map{it.packageName}.toSet(),"已添加")},enabled=ready,modifier=Modifier.testTag("apps_select_visible")){Text(uiText("全选可见"))}
                    TextButton(onClick={selected=emptySet();note="已清空选择，保存后生效"},modifier=Modifier.testTag("apps_clear")){Text(uiText("清空"))}
                }
                Text(uiText(if(perApp && mode=="exclude")"自动选择常用代理应用以外的应用，作为绕过名单" else "自动勾选 Google、Instagram、Discord、ChatGPT、Grok 等及已安装配套组件"),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick={clipboard?.setPrimaryClip(ClipData.newPlainText("Links-apps",selected.sorted().joinToString("\n")));note="已复制 ${selected.size} 个应用包名"},modifier=Modifier.testTag("apps_copy")){Text(uiText("复制"))}
                    TextButton(onClick={
                        val text=clipboard?.primaryClip?.let{clip->if(clip.itemCount>0)clip.getItemAt(0).coerceToText(context).toString() else ""}.orEmpty()
                        val pasted=parseAppPaste(text,installed,scopeData)
                        val added=pasted.accepted-selected
                        selected=selected+pasted.accepted
                        note="已粘贴选择 ${added.size} 个应用，跳过 ${pasted.skipped} 个无效、未安装或范围外条目"
                    },enabled=ready,modifier=Modifier.testTag("apps_import")){Text(uiText("粘贴并选择"))}
                    TextButton(onClick={val packages=visible.map{it.packageName}.toSet();selected=(selected-packages)+(packages-selected)},enabled=ready,modifier=Modifier.testTag("apps_invert")){Text(uiText("反选可见"))}
                }
            }
        }
        if(loading)item(key="apps_loading"){UiRow("正在加载应用列表",chevron=false)}
        if(loadError.isNotBlank())item(key="apps_error"){Text(loadError,color=MaterialTheme.colorScheme.error)}
        if(ready && visible.isEmpty())item(key="apps_empty"){Text(uiText("没有符合条件的应用"),Modifier.testTag("apps_empty"))}
        items(visible,key={it.packageName}){app->UiCard(Modifier.animateItem()) {InstalledAppRow(app,app.packageName in selected){checked->selected=if(checked)selected+app.packageName else selected-app.packageName}}}
    }
}
