@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.ui.ExperimentalComposeUiApi::class,androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.zane.zanebox.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal val LocalUiSnackbar=staticCompositionLocalOf<SnackbarHostState?> { null }
internal val LocalUiBusy=staticCompositionLocalOf { false }
internal val LocalUiListState=staticCompositionLocalOf<androidx.compose.foundation.lazy.LazyListState?> { null }
internal val LocalUiReload=staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable internal fun ApplyChangesRow(onReload:()->Unit,modifier:Modifier=Modifier) {
    Surface(color=MaterialTheme.colorScheme.surface) {
        Row(modifier.fillMaxWidth().clickable(onClick=onReload).padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(uiText("重载代理服务以应用修改"),Modifier.weight(1f),fontSize=14.sp)
            Spacer(Modifier.width(12.dp))
            Button(onClick=onReload,modifier=Modifier.testTag("apply_changes")){Text(uiText("重启服务"))}
        }
    }
}

/** Uses the platform switch shape from the AnyBox settings instead of the M3 outlined track. */
@Composable internal fun UiSwitch(checked:Boolean,onCheckedChange:((Boolean)->Unit)?,modifier:Modifier=Modifier,enabled:Boolean=true) {
    val primary=MaterialTheme.colorScheme.primary
    val dark=MaterialTheme.colorScheme.onSurface.luminance()>.5f
    Box(modifier.size(width=48.dp,height=36.dp).then(if(onCheckedChange==null)Modifier.semantics { role=Role.Switch;toggleableState=if(checked)androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off } else Modifier.toggleable(checked,enabled=enabled,role=Role.Switch,onValueChange=onCheckedChange))) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory={android.widget.Switch(it).apply{showText=false;splitTrack=false;isClickable=false;isFocusable=false;importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO}},
            modifier=Modifier.fillMaxSize(),
            update={view->
                view.setOnCheckedChangeListener(null);view.isChecked=checked;view.isEnabled=enabled
                val states=arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf())
                view.thumbTintList=android.content.res.ColorStateList(states,intArrayOf(primary.toArgb(),if(dark)0xFFBDBDBD.toInt() else 0xFFEEEEEE.toInt()))
                view.trackTintList=android.content.res.ColorStateList(states,intArrayOf(primary.copy(alpha=.4f).toArgb(),if(dark)0xFF666666.toInt() else 0xFFBDBDBD.toInt()))
                view.isClickable=false;view.isFocusable=false
            },
        )
    }
}

@Composable internal fun UiCard(modifier:Modifier=Modifier, content:@Composable ColumnScope.()->Unit) {
    Surface(modifier.fillMaxWidth(),shape=MaterialTheme.shapes.large,
        color=MaterialTheme.colorScheme.surface,
        border=BorderStroke(.5.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))) {
        Column(content=content)
    }
}

@Composable internal fun UiRow(title:String,subtitle:String="",icon:ImageVector?=null,
    modifier:Modifier=Modifier,onClick:(()->Unit)?=null,trailing:(@Composable ()->Unit)?=null,iconRes:Int=0,minHeight:Dp=68.dp,titleSize:androidx.compose.ui.unit.TextUnit=13.sp,chevron:Boolean=true,reserveIcon:Boolean=false,summaryLines:Int=1,onLongClick:(()->Unit)?=null) {
    Row(modifier.fillMaxWidth().then(if(onLongClick!=null)Modifier.combinedClickable(onClick=onClick ?: {},onLongClick=onLongClick) else if(onClick==null) Modifier else Modifier.clickable(onClick=onClick))
        .heightIn(min=minHeight).padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
        if(iconRes!=0 || icon!=null) { if(iconRes!=0)Icon(painterResource(iconRes),null,Modifier.size(24.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant) else Icon(icon!!,null,Modifier.size(24.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.width(14.dp)) } else if(reserveIcon)Spacer(Modifier.width(38.dp))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(uiText(title),fontSize=titleSize,lineHeight=18.sp,fontWeight=FontWeight.SemiBold)
            if(subtitle.isNotBlank()) Text(uiText(subtitle),fontSize=11.sp,lineHeight=14.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=summaryLines,overflow=TextOverflow.Ellipsis)
        }
        if(trailing!=null)trailing() else if(onClick!=null && chevron)Icon(Icons.Outlined.ChevronRight,null,Modifier.size(20.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun UiSection(title:String) {
    Text(uiText(title),Modifier.padding(start=2.dp,top=10.dp,bottom=6.dp),fontSize=12.sp,lineHeight=16.sp,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.onSurface)
}

@Composable internal fun UiMenu(actions:List<Pair<String,()->Unit>>,tag:String="more_menu",circle:Boolean=false) {
    var open by remember { mutableStateOf(false) }
    Box {
        if(circle)FilledIconButton(onClick={open=true},modifier=Modifier.size(48.dp).testTag(tag),colors=IconButtonDefaults.filledIconButtonColors(containerColor=androidx.compose.ui.graphics.Color.White)){Icon(Icons.Outlined.MoreVert,"更多操作")}
        else IconButton(onClick={open=true},modifier=Modifier.testTag(tag)) {Icon(Icons.Outlined.MoreVert,"更多操作")}
        DropdownMenu(expanded=open,onDismissRequest={open=false}) {
            actions.forEach { (title,action)-> DropdownMenuItem(text={Text(uiText(title))},onClick={open=false;action()}) }
        }
    }
}

@Composable internal fun UiPage(title:String,onDismiss:()->Unit,action:(@Composable RowScope.()->Unit)?=null,
    content:@Composable (PaddingValues)->Unit) {
    val density=androidx.compose.ui.platform.LocalDensity.current
    val navigationInsets=WindowInsets.navigationBars
    val snackbar=LocalUiSnackbar.current
    val busy=LocalUiBusy.current
    val reload=LocalUiReload.current
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides density) {
        val view=androidx.compose.ui.platform.LocalView.current
        val dark=MaterialTheme.colorScheme.onSurface.luminance()>.5f
        SideEffect { ((view.parent as? androidx.compose.ui.window.DialogWindowProvider) ?: (view as? androidx.compose.ui.window.DialogWindowProvider))?.window?.let{window->
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window,false)
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.setDimAmount(0f)
            if(android.os.Build.VERSION.SDK_INT>=28)window.attributes=window.attributes.apply {
                layoutInDisplayCutoutMode=if(android.os.Build.VERSION.SDK_INT>=30)android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                if(android.os.Build.VERSION.SDK_INT>=30)setFitInsetsTypes(0)
            }
            configureSystemBars(window,view,dark)
        } }
        Box(Modifier.fillMaxSize()) {
        UiBackdrop(Modifier.fillMaxSize(),home=false)
        Column(Modifier.fillMaxSize().semantics{testTagsAsResourceId=true}.windowInsetsPadding(WindowInsets.safeDrawing.union(navigationInsets))) {
        Scaffold(contentColor=MaterialTheme.colorScheme.onSurface,modifier=Modifier.weight(1f),contentWindowInsets=WindowInsets(0,0,0,0),containerColor=androidx.compose.ui.graphics.Color.Transparent,
            topBar={TopAppBar(windowInsets=WindowInsets(0,0,0,0),expandedHeight=56.dp,title={Text(uiText(title),fontSize=20.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(start=16.dp))},navigationIcon={
                IconButton(onClick=onDismiss,modifier=Modifier.testTag("page_back")){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回")}
            },actions={action?.invoke(this)},colors=TopAppBarDefaults.topAppBarColors(containerColor=androidx.compose.ui.graphics.Color.Transparent))},
            content=content)
        snackbar?.let { SnackbarHost(it,Modifier.fillMaxWidth().testTag("page_feedback_$title")) }
        reload?.let{ApplyChangesRow(it)}
        }
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth().systemBarsPadding().align(Alignment.TopCenter).testTag("page_busy"))
        }
        }
    }
}

@Composable internal fun UiPageList(title:String,onDismiss:()->Unit,action:(@Composable RowScope.()->Unit)?=null,
    content:LazyListScope.()->Unit) {
    UiPage(title,onDismiss,action) {padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("subpage_list"),state=LocalUiListState.current ?: androidx.compose.foundation.lazy.rememberLazyListState(),
            contentPadding=PaddingValues(start=14.dp,end=14.dp,top=12.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
    }
}

/** Dialog windows provide their own density; keep the application's chosen font scale in every slot. */
@Composable internal fun UiAlertDialog(onDismissRequest:()->Unit,confirmButton:@Composable ()->Unit,modifier:Modifier=Modifier,
    dismissButton:(@Composable ()->Unit)?=null,title:(@Composable ()->Unit)?=null,text:(@Composable ()->Unit)?=null,properties:DialogProperties=DialogProperties()) {
    val density=androidx.compose.ui.platform.LocalDensity.current
    AlertDialog(onDismissRequest=onDismissRequest,modifier=modifier,properties=properties,
        title=title?.let{slot->{CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides density){slot()}}},
        text=text?.let{slot->{CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides density){slot()}}},
        dismissButton=dismissButton?.let{slot->{CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides density){slot()}}},
        confirmButton={CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides density){confirmButton()}})
}

@Composable internal fun ChoiceDialog(title:String,value:String,choices:List<Pair<String,String>>,onDismiss:()->Unit,onChoose:(String)->Unit) {
    UiAlertDialog(onDismissRequest=onDismiss,title={Text(uiText(title))},text={
        LazyColumn(Modifier.heightIn(max=440.dp)) { items(choices.size) {i-> val (key,label)=choices[i]
            Row(Modifier.fillMaxWidth().clickable {onChoose(key);onDismiss()}.heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
                RadioButton(selected=value==key,onClick=null);Spacer(Modifier.width(8.dp));Text(uiText(label))
            }
        } }
    },confirmButton={TextButton(onClick=onDismiss){Text(uiText("取消"))}})
}

internal fun bytes(value:Long):String = when {
    value>=1073741824 -> "%.1f GB".format(value/1073741824.0)
    value>=1048576 -> "%.1f MB".format(value/1048576.0)
    value>=1024 -> "%.1f KB".format(value/1024.0)
    else -> "$value B"
}

@Suppress("DEPRECATION")
internal fun configureSystemBars(window:android.view.Window,view:android.view.View,dark:Boolean) {
    window.statusBarColor=android.graphics.Color.TRANSPARENT;window.navigationBarColor=android.graphics.Color.TRANSPARENT
    if(android.os.Build.VERSION.SDK_INT>=29) { window.isStatusBarContrastEnforced=false;window.isNavigationBarContrastEnforced=false }
    androidx.core.view.WindowCompat.getInsetsController(window,view).apply { isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark }
}
