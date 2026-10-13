package com.zane.zanebox.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.zane.zanebox.R
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView

@Composable internal fun HomeToolbar(state:Int,onPage:(Int)->Unit,onToggle:()->Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom=4.dp).height(127.dp).testTag("home_toolbar")) {
        AndroidView(factory={ZaneHomeToolbar(it).apply{importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO}},modifier=Modifier.fillMaxSize())
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val sideWidth=maxOf(96f,48f*androidx.compose.ui.platform.LocalDensity.current.fontScale+16f).dp.coerceAtMost((maxWidth-120.dp)/2)
            listOf(1 to .2f,2 to .8f).forEach { (page,fraction)->
                Column(Modifier.offset(x=24.dp+(maxWidth-48.dp)*fraction-sideWidth/2,y=52.dp).size(width=sideWidth,height=58.dp)
                    .clickable{onPage(page)}.testTag("tab_$page").padding(top=4.dp),
                    horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                    Icon(painterResource(if(page==2)R.drawable.zb_ref_ic_baseline_tune_24 else R.drawable.zb_ref_ic_anybox_smart_routing),null,Modifier.size(26.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(uiText(if(page==2)"设置" else "智能分流"),fontSize=12.sp,lineHeight=12.sp,maxLines=1,
                        style=androidx.compose.ui.text.TextStyle(platformStyle=androidx.compose.ui.text.PlatformTextStyle(includeFontPadding=false)))
                }
            }
        }
        val label=uiText(listOf("连接","连接中…","断开","断开中…","连接").getOrElse(state){"同步中"})
        val description=uiText("代理开关")+"："+label
        Box(Modifier.align(Alignment.TopCenter).offset(y=10.dp).size(82.dp).testTag("connect_toggle")
            .semantics{contentDescription=description}.clip(CircleShape).clickable(enabled=state!=1 && state!=3,onClick=onToggle)) {
            AndroidView(factory={ZanePowerButton(it).apply{isClickable=false;isFocusable=false}},update={it.render(state,false)},modifier=Modifier.fillMaxSize())
        }
        Text(label,Modifier.align(Alignment.TopCenter).offset(y=92.dp).testTag("tab_0").clickable(enabled=state!=1 && state!=3,onClick=onToggle),fontSize=12.sp,lineHeight=16.sp)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun HomeGroupChip(text:String,selected:Boolean,onClick:()->Unit,onLongClick:(()->Unit)?=null,active:Boolean=true,modifier:Modifier=Modifier) {
    val linear=skinPalette.name=="linear"
    Surface(color=if(selected && linear)skinSurfaceElevated else if(selected)MaterialTheme.colorScheme.primaryContainer else if(linear)skinBackground else MaterialTheme.colorScheme.surface,shape=if(linear)MaterialTheme.shapes.extraSmall else MaterialTheme.shapes.medium,
        modifier=modifier.semantics{this.selected=selected}.alpha(if(active)1f else .45f).combinedClickable(onClick=onClick,onLongClick=onLongClick)) {
        Box(Modifier.heightIn(min=if(linear)48.dp else 0.dp),contentAlignment=Alignment.Center){Text(text,Modifier.padding(horizontal=14.dp,vertical=10.dp),fontSize=12.sp,lineHeight=16.sp,maxLines=1)}
    }
}

@Composable internal fun WhiteConnectionCard(selected:String,subtitle:String,state:Int,serviceMode:String,routeMode:String,onToggle:()->Unit) {
    val status=uiText(listOf("代理未连接","连接中…","代理已连接","断开中…","连接失败").getOrElse(state){"同步中"})
    val action=uiText(listOf("连接","连接中…","断开","断开中…","重试连接").getOrElse(state){"同步中"})
    val description=uiText("代理开关")+"："+action
    UiCard(Modifier.testTag("white_connection_card")) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(status,fontSize=12.sp,color=if(state==2)skinSuccess else if(state==4)skinError else skinSecondaryText,modifier=Modifier.testTag("connection_status"))
            Text(selected,fontSize=18.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("home_selected_node"))
            if(subtitle.isNotBlank())Text(subtitle,fontSize=12.sp,color=skinSecondaryText,maxLines=1,overflow=TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(uiText(if(serviceMode=="vpn")"VPN 模式" else "本地代理")+" · "+uiText(when(routeMode){"global"->"全局";"direct"->"直连";else->"智能分流"}),fontSize=12.sp,color=skinSecondaryText,modifier=Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Button(onClick=onToggle,enabled=state!=1 && state!=3,shape=MaterialTheme.shapes.extraSmall,
                    modifier=Modifier.heightIn(min=48.dp).testTag("connect_toggle").semantics{contentDescription=description}) {
                    Icon(Icons.Outlined.PowerSettingsNew,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text(action)
                }
            }
        }
    }
}

@Composable internal fun WhiteNavigation(page:Int,onPage:(Int)->Unit) {
    Surface(color=skinSurface) {
        Column { HorizontalDivider(color=skinDivider)
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=8.dp,vertical=6.dp)) {
                listOf("节点" to Icons.Outlined.Dns,"智能分流" to Icons.Outlined.AccountTree,"设置" to Icons.Outlined.Tune).forEachIndexed { index,(label,icon)->
                    Surface(color=if(page==index)skinSurfaceElevated else skinSurface,shape=MaterialTheme.shapes.extraSmall,
                        modifier=Modifier.weight(1f).semantics{selected=page==index;role=Role.Tab}.clickable{onPage(index)}.testTag(if(index==0)"home_tab" else "tab_$index")) {
                        Column(Modifier.heightIn(min=64.dp).padding(vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Icon(icon,null,Modifier.size(24.dp),tint=if(page==index)skinPrimary else skinSecondaryText)
                            Text(uiText(label),fontSize=12.sp,color=if(page==index)skinPrimary else skinSecondaryText,maxLines=1)
                        }
                    }
                }
            }
        }
    }
}
